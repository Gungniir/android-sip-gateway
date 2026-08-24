package org.onetwoone.gateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.config.GatewayConfig;
import org.onetwoone.gateway.core.GatewayControlThread;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GW-21 — the inbound SMS pipeline has one owner.
 *
 * <p>Three properties, one per hazard the change had to clear:
 * <ul>
 *   <li><b>AUDIT G1.</b> Nothing on the path runs on main any more. The observer is built over
 *       the control looper, so {@code onChange} → {@code processInbox} → the send →
 *       {@code markAsRead} all happen on {@code GatewayControl}.</li>
 *   <li><b>The cursor.</b> {@code onIncomingSms} dispatches <i>inline</i> on the control thread
 *       and blocks on a SIP round-trip, so forwarding from inside the cursor loop would hold a
 *       cross-process cursor open across one network round-trip per row. No cursor may be open
 *       while a message is being handed over.</li>
 *   <li><b>Teardown.</b> {@code onDestroy} calls {@code stop()} on main, which may land while a
 *       scan is running on the control thread — a race the pre-GW-21 code could not have. The
 *       scan must stop handing messages over, and must not strand the rest of its batch marked
 *       in flight.</li>
 * </ul>
 *
 * <p>Two control-thread shapes are used deliberately. Tests that need a genuinely foreign
 * thread build the production form, which owns a real {@code HandlerThread} (the established
 * pattern — see {@code GatewayControlThreadTest}); the debounce test injects Robolectric's
 * paused main looper so "one scan or two?" is a decidable question rather than a timing one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SmsPipelineThreadingTest {

    private static final long TIMEOUT_S = 10L;
    private static final Uri SMS_URI = Uri.parse("content://sms");

    private Application app;
    private FakeInbox inbox;
    private GatewayControlThread control;

    @Before
    public void setUp() {
        ShadowLog.clear();
        app = RuntimeEnvironment.getApplication();

        inbox = new FakeInbox();
        ShadowContentResolver.registerProviderInternal("sms", inbox);

        resetConfig();
    }

    @After
    public void tearDown() {
        if (control != null) {
            control.quitSafely(TIMEOUT_S * 1000);
        }
    }

    private void resetConfig() {
        try {
            java.lang.reflect.Field instance = GatewayConfig.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        GatewayConfig.init(app);
    }

    /** A real, foreign control thread — the production form. */
    private GatewayControlThread realControlThread() {
        control = new GatewayControlThread(null);
        return control;
    }

    /** Robolectric's paused main looper standing in as the control looper. */
    private GatewayControlThread mainLooperControlThread() {
        control = new GatewayControlThread(Looper.getMainLooper(), null);
        return control;
    }

    // ------------------------------------------------------------------
    // AUDIT G1 — off main
    // ------------------------------------------------------------------

    /**
     * The whole pipeline, from the scan to the callback, runs on the control thread. Before
     * GW-21 the {@code ContentObserver} carried the main handler and every one of these
     * callbacks — each of which does a blocking SIP send — landed on the UI thread.
     */
    @Test
    public void theInboundPipelineRunsOnTheControlThreadAndNeverOnMain() throws Exception {
        inbox.addUnread(1, "+79990000001", "one");
        inbox.addUnread(2, "+79990000002", "two");

        GatewayControlThread ctl = realControlThread();
        Recorder recorder = new Recorder(2);
        SmsHandler handler = new SmsHandler(app, ctl, recorder);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.start();
        assertTrue("both messages forwarded", recorder.done.await(TIMEOUT_S, TimeUnit.SECONDS));
        drain(ctl);

        assertEquals(2, recorder.ids.size());
        assertEquals("every forward ran on the control thread: " + recorder.threads,
                Collections.singleton(GatewayControlThread.THREAD_NAME),
                new java.util.HashSet<>(recorder.threads));
        assertNotEquals("...which is not main",
                Looper.getMainLooper().getThread().getName(),
                GatewayControlThread.THREAD_NAME);
    }

    /**
     * The observer itself. {@code onChange} must arrive on the control looper, not on main —
     * that one constructor argument is the whole of GW-21.
     */
    @Test
    public void theObserverDispatchesOnTheControlLooper() throws Exception {
        GatewayControlThread ctl = realControlThread();
        Recorder recorder = new Recorder(1);
        SmsHandler handler = new SmsHandler(app, ctl, recorder);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.start();
        drain(ctl);                       // the start task, and its (empty) first scan
        assertTrue(recorder.ids.isEmpty());

        inbox.addUnread(9, "+79990000009", "arrived");
        app.getContentResolver().notifyChange(SMS_URI, null);

        // The change is dispatched onto the control looper, where it schedules the debounced
        // scan. Drain that hop first, then move the clock past the window rather than sleep.
        drain(ctl);
        ShadowSystemClock.advanceBy(Duration.ofMillis(500));
        drain(ctl);

        assertTrue("the observer's scan forwarded the message",
                recorder.done.await(TIMEOUT_S, TimeUnit.SECONDS));
        assertEquals("...and it ran on the control thread, not main",
                GatewayControlThread.THREAD_NAME, recorder.threads.get(0));
    }

    // ------------------------------------------------------------------
    // The cursor is closed before anything is sent
    // ------------------------------------------------------------------

    /**
     * No inbox cursor may be open while a message is being forwarded.
     *
     * <p>{@code onIncomingSms} reaches {@code PjsipSipService} through {@code runOrPost}, which
     * dispatches inline now that the caller is always the control thread — so a send inside the
     * cursor loop would be a blocking network round-trip per row with a cross-process cursor
     * held open. This fails against the pre-GW-21 shape and passes against the
     * collect-then-forward one.
     */
    @Test
    public void noCursorIsOpenWhileAMessageIsForwarded() {
        inbox.addUnread(40, "+79990000040", "a");
        inbox.addUnread(41, "+79990000041", "b");
        inbox.addUnread(42, "+79990000042", "c");

        GatewayControlThread ctl = mainLooperControlThread();
        List<Integer> openAtCallback = new ArrayList<>();
        SmsHandler handler = new SmsHandler(app, ctl, new SmsHandler.SmsCallback() {
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                openAtCallback.add(inbox.openCursors.get());
            }

            @Override
            public void onSmsSendStatus(String d, String s, String e) {
            }
        });
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();

        assertEquals(3, openAtCallback.size());
        assertEquals("the inbox cursor must be closed before any send: " + openAtCallback,
                java.util.Arrays.asList(0, 0, 0), openAtCallback);
    }

    /**
     * ...and the batch is marked in flight <b>before</b> any of it is sent, which is the
     * invariant the collect/forward split had to preserve. All three ids are already suppressed
     * when the first one is handed over.
     */
    @Test
    public void theWholeBatchIsMarkedInFlightBeforeTheFirstSend() {
        inbox.addUnread(50, "+79990000050", "a");
        inbox.addUnread(51, "+79990000051", "b");

        GatewayControlThread ctl = mainLooperControlThread();
        List<java.util.Set<Long>> seen = new ArrayList<>();
        SmsHandler[] box = new SmsHandler[1];
        box[0] = new SmsHandler(app, ctl, new SmsHandler.SmsCallback() {
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                seen.add(box[0].getInFlightIdsForTest());
            }

            @Override
            public void onSmsSendStatus(String d, String s, String e) {
            }
        });
        box[0].setReadFlagWriteEnabledForTest(false);

        box[0].processInbox();

        assertEquals(2, seen.size());
        assertTrue("id 51 is already suppressed when 50 is sent", seen.get(0).contains(51L));
        assertTrue(seen.get(0).contains(50L));
    }

    // ------------------------------------------------------------------
    // Teardown against an in-flight scan
    // ------------------------------------------------------------------

    /**
     * {@code onDestroy} calls {@code stop()} on main while a scan is running on the control
     * thread. Three things must hold: the scan stops handing messages over, the tail of its
     * batch is <b>released</b> rather than left marked in flight forever, and the observer is
     * unregistered — on the control thread, so it cannot race the scan.
     */
    @Test
    public void stopDuringAnInFlightScanReleasesTheUnsentTail() throws Exception {
        inbox.addUnread(60, "+79990000060", "first");
        inbox.addUnread(61, "+79990000061", "second");
        inbox.addUnread(62, "+79990000062", "third");

        GatewayControlThread ctl = realControlThread();

        CountDownLatch insideFirstCallback = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Recorder recorder = new Recorder(3) {
            @Override
            public void onIncomingSms(String from, String body, long smsId, int simSlot) {
                super.onIncomingSms(from, body, smsId, simSlot);
                insideFirstCallback.countDown();
                try {
                    releaseCallback.await(TIMEOUT_S, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        SmsHandler handler = new SmsHandler(app, ctl, recorder);
        handler.setReadFlagWriteEnabledForTest(false);
        handler.start();

        assertTrue("the control thread is inside the first forward",
                insideFirstCallback.await(TIMEOUT_S, TimeUnit.SECONDS));

        // This is onDestroy's call, from main, mid-scan. It must not block on the control
        // thread and must not unregister the observer from here.
        handler.stop();
        releaseCallback.countDown();

        // quitSafely drains what is already queued, which is where the posted unregister runs.
        ctl.quitSafely(TIMEOUT_S * 1000);
        control = null;

        assertEquals("only the message already in flight was forwarded: " + recorder.ids,
                1, recorder.ids.size());
        assertEquals(60L, (long) recorder.ids.get(0));

        assertEquals("the unsent tail is released, not stranded in flight",
                Collections.singleton(60L), handler.getInFlightIdsForTest());
        assertFalse("the observer is unregistered", handler.isObserverRegisteredForTest());

        // And the tail is genuinely still deliverable: nothing persisted it as forwarded.
        assertTrue(handler.getConfirmedIdsForTest().isEmpty());
    }

    /** A stop that lands before the posted start must not leave an observer behind. */
    @Test
    public void stopBeforeStartRunsRegistersNothing() throws Exception {
        GatewayControlThread ctl = realControlThread();
        SmsHandler handler = new SmsHandler(app, ctl, new Recorder(1));

        handler.start();
        handler.stop();
        ctl.quitSafely(TIMEOUT_S * 1000);
        control = null;

        assertFalse(handler.isObserverRegisteredForTest());
    }

    // ------------------------------------------------------------------
    // Debounce
    // ------------------------------------------------------------------

    /**
     * A burst of inbox changes collapses into one scan. Every {@code markAsRead} mutates the
     * provider and re-triggers {@code onChange}, so without this each forwarded message cost a
     * redundant full inbox query.
     */
    @Test
    public void aBurstOfInboxChangesCollapsesIntoOneScan() {
        GatewayControlThread ctl = mainLooperControlThread();
        SmsHandler handler = new SmsHandler(app, ctl, new Recorder(1));
        handler.setReadFlagWriteEnabledForTest(false);

        handler.start();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the start-up scan", 1, inbox.inboxQueries.get());

        for (int i = 0; i < 6; i++) {
            app.getContentResolver().notifyChange(SMS_URI, null);
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));

        assertEquals("six changes, one extra scan", 2, inbox.inboxQueries.get());
    }

    /**
     * The window is anchored on the first change of a burst, not restarted by each one, so a
     * steady stream of provider writes cannot push the scan out indefinitely.
     */
    @Test
    public void aSteadyStreamOfChangesCannotStarveTheScan() {
        GatewayControlThread ctl = mainLooperControlThread();
        SmsHandler handler = new SmsHandler(app, ctl, new Recorder(1));
        handler.setReadFlagWriteEnabledForTest(false);

        handler.start();
        shadowOf(Looper.getMainLooper()).idle();
        int afterStart = inbox.inboxQueries.get();

        // A change every 100 ms for 1 s. A restart-on-every-change debounce would never fire.
        for (int i = 0; i < 10; i++) {
            app.getContentResolver().notifyChange(SMS_URI, null);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        }

        assertTrue("the scan still happened", inbox.inboxQueries.get() > afterStart);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Run the control thread's queue to empty, from the test thread. */
    private static void drain(GatewayControlThread ctl) throws InterruptedException {
        CountDownLatch idle = new CountDownLatch(1);
        ctl.post(idle::countDown);
        assertTrue(idle.await(TIMEOUT_S, TimeUnit.SECONDS));
    }

    /** Records which thread each forward arrived on. */
    private static class Recorder implements SmsHandler.SmsCallback {
        final List<Long> ids = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch done;

        Recorder(int expected) {
            this.done = new CountDownLatch(expected);
        }

        @Override
        public void onIncomingSms(String from, String body, long smsId, int simSlot) {
            ids.add(smsId);
            threads.add(Thread.currentThread().getName());
            done.countDown();
        }

        @Override
        public void onSmsSendStatus(String destination, String status, String errorMessage) {
        }
    }

    /**
     * A {@code content://sms} provider that counts how many cursors it has handed out and not
     * had closed, which is what makes "the cursor was closed before the send" observable.
     */
    private static final class FakeInbox extends ContentProvider {
        private final Map<Long, Row> rows = Collections.synchronizedMap(new LinkedHashMap<>());

        final AtomicInteger openCursors = new AtomicInteger();
        final AtomicInteger inboxQueries = new AtomicInteger();

        void addUnread(long id, String address, String body) {
            rows.put(id, new Row(id, address, body));
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection,
                            String[] selectionArgs, String sortOrder) {
            String[] columns = projection == null
                    ? new String[]{"_id", "address", "body", "date", "read", "sub_id"}
                    : projection;
            MatrixCursor cursor = new MatrixCursor(columns);

            List<Row> matched = new ArrayList<>();
            Long single = idOf(uri);
            if (single != null) {
                Row row = rows.get(single);
                if (row != null) {
                    matched.add(row);
                }
            } else {
                inboxQueries.incrementAndGet();
                synchronized (rows) {
                    for (Row row : rows.values()) {
                        if (selection == null || !selection.contains("read") || !row.read) {
                            matched.add(row);
                        }
                    }
                }
            }

            for (Row row : matched) {
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    values[i] = row.get(columns[i]);
                }
                cursor.addRow(values);
            }

            openCursors.incrementAndGet();
            return new CursorWrapper(cursor) {
                @Override
                public void close() {
                    openCursors.decrementAndGet();
                    super.close();
                }
            };
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;                    // not the default SMS app, as on both devices
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return "vnd.android.cursor.dir/sms";
        }

        private static Long idOf(Uri uri) {
            String last = uri.getLastPathSegment();
            try {
                return last == null ? null : Long.parseLong(last);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static final class Row {
            final long id;
            final String address;
            final String body;
            final long date = System.currentTimeMillis();
            boolean read;

            Row(long id, String address, String body) {
                this.id = id;
                this.address = address;
                this.body = body;
            }

            Object get(String column) {
                switch (column) {
                    case "_id":
                        return id;
                    case "address":
                        return address;
                    case "body":
                        return body;
                    case "date":
                        return date;
                    case "read":
                        return read ? 1 : 0;
                    case "sub_id":
                        return 1;
                    default:
                        return null;
                }
            }
        }
    }
}
