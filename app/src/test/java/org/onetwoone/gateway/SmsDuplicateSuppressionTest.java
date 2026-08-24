package org.onetwoone.gateway;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SMS duplicate suppression — AUDIT <b>H13</b> (GW-27).
 *
 * <p>The bug these pin down: the gateway re-forwarded its <b>entire inbox</b> to the PBX on
 * every process start. {@code processInbox} selects on {@code read = 0}, the app could not
 * write that flag (not the default SMS app; the root fallback ran a {@code sqlite3} that is
 * on neither device, exit 127; and {@code execRoot} reported that failure as success), and
 * the only other defence was an in-memory {@code HashSet} that starts empty every time.
 *
 * <p>{@link #restartDoesNotReForwardWithTheReadFlagWriteFaultInjected()} is the one that
 * matters. It disables the flag write entirely — modelling a device or Android version
 * where the app simply cannot write provider state — and requires <b>zero</b> duplicates
 * across a restart anyway. If suppression ever goes back to depending on the flag, that
 * test fails and nothing else here does.
 *
 * <p>The inbox is a fake {@link ContentProvider} registered on the {@code sms} authority, so
 * these run on the JVM with no device. It refuses {@code update} by default, which is
 * exactly what the real provider does to an app that is not the default SMS app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SmsDuplicateSuppressionTest {

    /**
     * A plausible SMS {@code date}, and deliberately an <b>old</b> one: the merlinx fixture
     * is a pile of messages that had been sitting unread for a long time before the gateway
     * got to them. If the suppression record's TTL were keyed on the SMS date rather than
     * on when the forward was confirmed, every one of these would be pruned the instant it
     * was recorded and the bug would come straight back.
     */
    private static final long OLD_SMS_DATE =
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(120);

    private Application app;
    private FakeSmsProvider inbox;

    /**
     * GW-21 gave the inbound pipeline one owner, so {@code processInbox} now asserts it is on
     * the control thread. These tests drive it directly, so the control thread <em>is</em>
     * Robolectric's main looper — {@code isCurrent()} is then true on the test thread and
     * {@code runOrPost} dispatches inline, which is exactly the production shape.
     * {@code SmsPipelineThreadingTest} is where the real, foreign control thread is used.
     */
    private GatewayControlThread control;

    @Before
    public void setUp() {
        ShadowLog.clear();
        app = RuntimeEnvironment.getApplication();
        control = new GatewayControlThread(Looper.getMainLooper(), null);

        inbox = new FakeSmsProvider();
        ShadowContentResolver.registerProviderInternal("sms", inbox);

        resetConfig();
    }

    /** Rebuild the config singleton over the same (persistent) SharedPreferences. */
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

    /** A fresh handler over the same persisted state — i.e. the process restarted. */
    private SmsHandler restart(Collector collector) {
        resetConfig();
        return new SmsHandler(app, control, collector);
    }

    // ------------------------------------------------------------------
    // The reported bug
    // ------------------------------------------------------------------

    /**
     * <b>The acceptance test for H13.</b> With the read-flag write fault-injected off, a
     * restart must still forward zero duplicates — proving the persisted record, not the
     * provider flag, is what carries correctness.
     */
    @Test
    public void restartDoesNotReForwardWithTheReadFlagWriteFaultInjected() {
        inbox.addUnread(3, "+79990000003", "one", OLD_SMS_DATE);
        inbox.addUnread(4, "+79990000004", "two", OLD_SMS_DATE + 1000);
        inbox.addUnread(5, "+79990000005", "three", OLD_SMS_DATE + 2000);

        Collector first = new Collector();
        SmsHandler handler = new SmsHandler(app, control, first);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();
        assertEquals("all three forwarded on the first pass", 3, first.ids.size());
        for (long id : first.ids) {
            handler.markAsRead(id);          // what a successful SIP MESSAGE triggers
        }

        // The fault injection has to be real, or the test proves nothing: every row must
        // still be unread, exactly as on merlinx.
        assertEquals("the flag write really did not happen", 3, inbox.unreadCount());

        Collector afterRestart = new Collector();
        SmsHandler restarted = restart(afterRestart);
        restarted.setReadFlagWriteEnabledForTest(false);
        restarted.processInbox();

        assertEquals("a restart must re-forward nothing: " + afterRestart.ids,
                0, afterRestart.ids.size());
        assertEquals("and the inbox is untouched", 3, inbox.unreadCount());
    }

    /** Re-registration replays the inbox scan; it must not replay the messages. */
    @Test
    public void reRegistrationScansDoNotReForward() {
        inbox.addUnread(8, "+79990000008", "flap", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();
        assertEquals(1, collector.ids.size());
        handler.markAsRead(8);

        for (int i = 0; i < 5; i++) {
            handler.processInbox();          // five re-REGISTERs
        }
        assertEquals("five re-registrations forwarded nothing new", 1, collector.ids.size());
    }

    /** The ordinary path: the flag write works, and it is enough on its own. */
    @Test
    public void restartDoesNotReForwardWhenTheReadFlagIsWritten() {
        inbox.acceptUpdates = true;
        inbox.addUnread(10, "+79990000010", "hello", OLD_SMS_DATE);

        Collector first = new Collector();
        SmsHandler handler = new SmsHandler(app, control, first);
        handler.processInbox();
        assertEquals(1, first.ids.size());

        assertTrue("the resolver write is verified, not assumed", handler.markAsRead(10));
        assertEquals("the row actually flipped", 0, inbox.unreadCount());

        Collector afterRestart = new Collector();
        restart(afterRestart).processInbox();
        assertEquals(0, afterRestart.ids.size());
    }

    /**
     * A provider that reports a successful update but does not change the row is the exact
     * shape of the bug (a write that claims to have worked). It must not be believed, and
     * the failure must name the id.
     */
    @Test
    public void aLyingUpdateIsNotBelievedAndTheFailureNamesTheId() {
        inbox.acceptUpdates = true;
        inbox.applyUpdates = false;          // "1 row updated", nothing changed
        inbox.addUnread(11, "+79990000011", "liar", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.processInbox();

        // Root is unavailable here too (no `su`, no `content`), so every route fails.
        assertFalse("an unverified write is a failed write", handler.markAsRead(11));
        assertEquals("the row is still unread", 1, inbox.unreadCount());
        assertTrue("the error must name the SMS id", loggedErrorMentions("id=11"));

        // ...and the message is still suppressed, because the record does not need the flag.
        Collector afterRestart = new Collector();
        restart(afterRestart).processInbox();
        assertEquals(0, afterRestart.ids.size());
    }

    /**
     * On a device that can never write the flag, the gateway stops trying rather than
     * spawning a doomed {@code su} per message — and says so once, loudly.
     */
    @Test
    public void theFlagWriteIsGivenUpOnAfterRepeatedFailures() {
        for (long id = 30; id <= 34; id++) {
            inbox.addUnread(id, "+7999000003" + (id - 30), "nope", OLD_SMS_DATE);
        }

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.processInbox();
        assertEquals(5, collector.ids.size());

        for (long id = 30; id <= 34; id++) {
            assertFalse("nothing can write the flag here", handler.markAsRead(id));
        }
        assertTrue("it says so once it has given up",
                loggedErrorMentions("stop trying until it restarts"));

        // Given up or not, every one of them is still suppressed across a restart.
        Collector afterRestart = new Collector();
        restart(afterRestart).processInbox();
        assertEquals(0, afterRestart.ids.size());
    }

    // ------------------------------------------------------------------
    // Persist-after-success
    // ------------------------------------------------------------------

    /** A crash between the callback and the send must retry, not drop. */
    @Test
    public void nothingIsPersistedUntilTheForwardSucceeds() {
        inbox.addUnread(12, "+79990000012", "in flight", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);
        handler.processInbox();

        assertEquals(1, collector.ids.size());
        assertEquals("in flight is not persisted",
                "", GatewayConfig.getInstance().getProcessedSmsRecord());

        // The process dies here. The replacement must offer the message again.
        Collector afterCrash = new Collector();
        restart(afterCrash).processInbox();
        assertEquals("an un-forwarded SMS survives a crash", 1, afterCrash.ids.size());
        assertEquals(12L, (long) afterCrash.ids.get(0));
    }

    @Test
    public void aSuccessfulForwardIsPersisted() {
        inbox.addUnread(6, "+79990000006", "done", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);
        handler.processInbox();
        handler.markAsRead(6);

        String record = GatewayConfig.getInstance().getProcessedSmsRecord();
        assertTrue("record carries id and stamp, got: " + record, record.startsWith("6:"));

        // The stamp is when the forward was confirmed, not the SMS's own (120-day-old)
        // date - otherwise the TTL would discard it immediately. See PROCESSED_ID_TTL_MS.
        long stamp = Long.parseLong(record.substring(2));
        assertTrue("stamped now, not with the SMS date (" + stamp + ")",
                stamp > System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5));

        // And it therefore survives a restart, which is the whole point.
        Collector afterRestart = new Collector();
        assertTrue(restart(afterRestart).getConfirmedIdsForTest().contains(6L));
    }

    // ------------------------------------------------------------------
    // Bounded growth
    // ------------------------------------------------------------------

    @Test
    public void thePersistedRecordIsBoundedBySize() {
        // All well inside the TTL, so it is the size cap under test and not the age pass.
        long stamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        StringBuilder seed = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            if (i > 0) {
                seed.append(',');
            }
            seed.append(i).append(':').append(stamp + i);
        }
        GatewayConfig.getInstance().setProcessedSmsRecord(seed.toString());

        SmsHandler handler = new SmsHandler(app, control, new Collector());
        int kept = handler.getConfirmedIdsForTest().size();
        assertTrue("10k ids must be pruned down, kept " + kept, kept <= 1000);

        // The prune is written back, so it does not have to be redone on every start.
        assertTrue("the pruned record is persisted",
                GatewayConfig.getInstance().getProcessedSmsRecord().length()
                        < seed.length() / 2);

        // Most recent survive, oldest go.
        assertTrue(handler.getConfirmedIdsForTest().contains(9999L));
        assertFalse(handler.getConfirmedIdsForTest().contains(0L));
    }

    @Test
    public void thePersistedRecordIsBoundedByAge() {
        long ancient = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31);
        long recent = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        GatewayConfig.getInstance().setProcessedSmsRecord("70:" + ancient + ",71:" + recent);

        SmsHandler handler = new SmsHandler(app, control, new Collector());
        assertFalse("a 31-day-old id is dropped", handler.getConfirmedIdsForTest().contains(70L));
        assertTrue("a 1-day-old id is kept", handler.getConfirmedIdsForTest().contains(71L));
    }

    @Test
    public void aMalformedRecordDoesNotBreakStartup() {
        long stamp = System.currentTimeMillis();
        GatewayConfig.getInstance().setProcessedSmsRecord(",,42:" + stamp + ",garbage,7:,:9,88");

        SmsHandler handler = new SmsHandler(app, control, new Collector());
        assertTrue("the well-formed id survives", handler.getConfirmedIdsForTest().contains(42L));
        assertTrue("a bare id is kept as a suppression", handler.getConfirmedIdsForTest().contains(88L));
    }

    // ------------------------------------------------------------------
    // Bounded retry
    // ------------------------------------------------------------------

    /**
     * The registration-gated retry. An SMS that arrives before the first REGISTER is
     * un-processed so the post-REGISTER scan picks it up — and that scan happens seconds
     * later, so the first retry must not be delayed by the backoff.
     */
    @Test
    public void theFirstRetryIsNotDelayed() {
        inbox.addUnread(20, "+79990000020", "not registered yet", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();
        assertEquals(1, collector.ids.size());
        handler.unprocessSms(20);            // "not registered, will retry after registration"

        handler.processInbox();              // the post-REGISTER scan, immediately after
        assertEquals("the registration retry is not swallowed by the backoff",
                2, collector.ids.size());
    }

    /** A second failure does back off, so a repeatedly failing message is not re-offered. */
    @Test
    public void laterRetriesBackOff() {
        inbox.addUnread(21, "+79990000021", "flaky", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();
        handler.unprocessSms(21);            // failure 1 - retry immediately
        handler.processInbox();
        handler.unprocessSms(21);            // failure 2 - back off
        assertEquals(2, collector.ids.size());

        handler.processInbox();
        assertEquals("still backed off", 2, collector.ids.size());

        ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(11));
        handler.processInbox();
        assertEquals("offered again once the backoff elapsed", 3, collector.ids.size());
    }

    /** After the cap it is given up on, loudly, and stops being offered. */
    @Test
    public void theRetryIsCapped() {
        inbox.addUnread(22, "+79990000022", "hopeless", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);

        for (int i = 0; i < 5; i++) {
            handler.processInbox();
            handler.unprocessSms(22);
            ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(11));
        }
        assertEquals("offered exactly MAX_FORWARD_ATTEMPTS times", 5, collector.ids.size());

        handler.processInbox();
        assertEquals("and never again", 5, collector.ids.size());
        assertTrue("giving up is an error naming the id", loggedErrorMentions("id=22"));
        assertTrue("it is suppressed from then on",
                handler.getConfirmedIdsForTest().contains(22L));
    }

    /** A confirmed id must never be walked back into the retry queue. */
    @Test
    public void unprocessCannotResurrectAConfirmedId() {
        inbox.addUnread(23, "+79990000023", "done", OLD_SMS_DATE);

        Collector collector = new Collector();
        SmsHandler handler = new SmsHandler(app, control, collector);
        handler.setReadFlagWriteEnabledForTest(false);

        handler.processInbox();
        handler.markAsRead(23);
        handler.unprocessSms(23);            // a late failure report for a delivered message

        handler.processInbox();
        assertEquals(1, collector.ids.size());
        assertEquals(0, handler.getForwardAttemptsForTest(23));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean loggedErrorMentions(String needle) {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("SmsHandler")) {
            if (item.type >= android.util.Log.ERROR && item.msg != null
                    && item.msg.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static final class Collector implements SmsHandler.SmsCallback {
        final List<Long> ids = new ArrayList<>();

        @Override
        public void onIncomingSms(String from, String body, long smsId, int simSlot) {
            ids.add(smsId);
        }

        @Override
        public void onSmsSendStatus(String destination, String status, String errorMessage) {
        }
    }

    /**
     * A minimal {@code content://sms} provider.
     *
     * <p>{@link #acceptUpdates} defaults to false because that is the truth on both test
     * devices: the gateway is not the default SMS app, so the provider refuses its writes.
     * {@link #applyUpdates} separates "reported a row" from "changed a row", which is the
     * distinction the verify-don't-assume rule exists for.
     */
    private static final class FakeSmsProvider extends ContentProvider {
        private final Map<Long, Row> rows = new LinkedHashMap<>();

        boolean acceptUpdates = false;
        boolean applyUpdates = true;

        void addUnread(long id, String address, String body, long date) {
            rows.put(id, new Row(id, address, body, date));
        }

        int unreadCount() {
            int n = 0;
            for (Row row : rows.values()) {
                if (!row.read) {
                    n++;
                }
            }
            return n;
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
                for (Row row : rows.values()) {
                    if (selection == null || !selection.contains("read") || !row.read) {
                        matched.add(row);
                    }
                }
                Collections.sort(matched, (a, b) -> Long.compare(a.date, b.date));
            }

            for (Row row : matched) {
                Object[] values = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) {
                    values[i] = row.get(columns[i]);
                }
                cursor.addRow(values);
            }
            return cursor;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection,
                          String[] selectionArgs) {
            Long id = idOf(uri);
            if (id == null || !rows.containsKey(id) || !acceptUpdates) {
                return 0;
            }
            if (applyUpdates && values != null && values.containsKey("read")) {
                rows.get(id).read = values.getAsInteger("read") != 0;
            }
            return 1;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            Long id = idOf(uri);
            return id != null && rows.remove(id) != null ? 1 : 0;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return "vnd.android.cursor.dir/sms";
        }

        /** {@code content://sms/<id>} -> the id; {@code content://sms/inbox} -> null. */
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
            final long date;
            boolean read;

            Row(long id, String address, String body, long date) {
                this.id = id;
                this.address = address;
                this.body = body;
                this.date = date;
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
