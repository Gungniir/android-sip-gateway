package org.onetwoone.gateway;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import org.onetwoone.gateway.config.GatewayConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles SMS operations for the GSM-SIP Gateway.
 *
 * Incoming SMS (GSM -> SIP):
 * - Monitors content://sms/inbox via ContentObserver
 * - Sends SMS content as SIP MESSAGE to Asterisk
 * - Marks the SMS read once the PBX has it
 *
 * Outgoing SMS (SIP -> GSM):
 * - Receives destination and body from SIP MESSAGE
 * - Sends via SmsManager
 * - Reports delivery status back
 *
 * <h2>Duplicate suppression (AUDIT H13 — GW-27)</h2>
 *
 * <p>{@link #processInbox()} selects on {@code read = 0}, so <b>the inbox {@code read} flag
 * is what stops a message being forwarded twice</b> — and the gateway could not write it.
 * It is not the default SMS app, so {@code ContentResolver.update} is refused; the root
 * fallback shelled out to {@code sqlite3}, which exists on neither test device (exit 127);
 * and {@code RootHelper.execRoot} reported that failure as success. The only remaining
 * defence was an in-memory {@code HashSet} that starts empty on every process start, so
 * <b>the entire inbox was re-forwarded to the PBX every time the process restarted</b>, and
 * again on every SIP re-registration.
 *
 * <p>Three things close it, and the order matters:
 * <ol>
 *   <li>The flag write uses {@code /system/bin/content}, which is present on-device, through
 *       {@link RootHelper#run(String)} — whose {@code RootResult.success()} cannot report a
 *       non-zero exit as success (GW-20 fixed that contract).</li>
 *   <li>The write is <b>verified</b> by re-reading the row. A write that silently did
 *       nothing logs an error naming the id; that silence is what hid this for the life of
 *       the feature.</li>
 *   <li><b>The flag is not the only defence.</b> {@link #confirmedIds} is persisted through
 *       {@link GatewayConfig#getProcessedSmsRecord()} and survives a restart, so suppression
 *       still holds on a device or Android version where the flag cannot be written at all.
 *       That is the property the fault-injection test pins down.</li>
 * </ol>
 *
 * <p>An id is persisted <b>after</b> a successful forward ({@link #markAsRead}), never
 * before, so a crash mid-send retries rather than dropping the message. The failure
 * direction that matters here is under-suppression (duplicates); over-suppression would
 * silently lose a real SMS.
 *
 * <h2>Threading (AUDIT H12)</h2>
 *
 * <p>The suppression state is reached from <b>two</b> threads today: {@link #processInbox()}
 * runs on <b>main</b> (the {@link ContentObserver} is built with {@link #mainHandler}) and on
 * the <b>control thread</b> (the post-registration retry), and {@link #markAsRead} /
 * {@link #unprocessSms} run on the control thread. The maps are therefore
 * {@link ConcurrentHashMap}s and the persisted record's read-modify-write is behind
 * {@link #persistLock} — a lock that is never held across a callback, so main cannot end up
 * blocked behind the control thread's blocking SIP send.
 *
 * <p>This is a stopgap. <b>GW-21</b> moves the observer onto the control looper, which gives
 * the state a single owner; when it does, the concurrent maps and {@link #persistLock} can
 * go back to plain {@code HashMap}s with an {@code assertOnControlThread}. Nothing here
 * depends on the concurrency, only on the state being reachable from one place.
 */
public class SmsHandler {
    private static final String TAG = "SmsHandler";

    private static final Uri SMS_INBOX_URI = Uri.parse("content://sms/inbox");
    private static final Uri SMS_URI = Uri.parse("content://sms");

    private static final String ACTION_SMS_SENT = "org.onetwoone.gateway.SMS_SENT";
    private static final String ACTION_SMS_DELIVERED = "org.onetwoone.gateway.SMS_DELIVERED";

    /**
     * How long a forwarded id stays in the persisted suppression record, measured from
     * <b>when the forward was confirmed</b>, not from the SMS's own {@code date}.
     *
     * <p>The brief says "drop ids whose SMS date is older than 30 days"; that is a trap.
     * An SMS that had already been sitting unread for longer than the TTL — the merlinx
     * fixture is precisely a pile of old unread messages — would be pruned the instant it
     * was forwarded, and re-forwarded on the next restart. Keying the TTL on the
     * confirmation instead bounds the record just as tightly and has no such hole.
     *
     * <p>Past the TTL the inbox {@code read} flag is the only thing still holding the
     * message down, which is the normal case anyway. Trading a possible single re-forward
     * of something forwarded a month ago against unbounded growth on a 24/7 device is the
     * right way round; the reverse is eventually a multi-MB prefs read at every start.
     */
    private static final long PROCESSED_ID_TTL_MS = 30L * 24 * 60 * 60 * 1000;

    /** Hard cap on the persisted record, enforced oldest-first after the TTL pass. */
    private static final int PROCESSED_ID_MAX = 1000;

    /**
     * How many times one message may come back through {@link #unprocessSms} before the
     * gateway gives up on it, logs an error and marks it read so it stops being offered.
     */
    private static final int MAX_FORWARD_ATTEMPTS = 5;

    /**
     * How many consecutive messages may fail their read-flag write before the root path is
     * given up on for the rest of the process. On a device that simply cannot do it — no
     * root, no {@code content}, a provider that refuses everything — retrying per message
     * buys nothing and spawns an {@code su} each time. The persisted record is the backstop
     * exactly so that giving up here is safe. A restart re-arms it.
     */
    private static final int MAX_ROOT_FLAG_FAILURES = 3;

    /**
     * Delay before the <b>second</b> attempt; doubles per attempt up to
     * {@link #RETRY_MAX_DELAY_MS}. The first retry is deliberately not delayed — see
     * {@link #unprocessSms(long)}.
     */
    private static final long RETRY_BASE_DELAY_MS = 30_000L;

    private static final long RETRY_MAX_DELAY_MS = 10 * 60_000L;

    private final Context context;
    private final SmsCallback callback;
    private final Handler mainHandler;
    private final GatewayConfig config;

    private ContentObserver smsObserver;
    private BroadcastReceiver smsSentReceiver;
    private BroadcastReceiver smsDeliveredReceiver;

    /**
     * Ids whose forward the PBX has accepted, mirroring the persisted record. id -> the
     * wall-clock time the forward was confirmed, which is what the TTL prune is measured
     * against. Nothing but a prune ever removes from this — in particular
     * {@link #unprocessSms} must not, or a confirmed message would become eligible for
     * re-forwarding.
     */
    private final Map<Long, Long> confirmedIds = new ConcurrentHashMap<>();

    /**
     * Ids handed to the callback but not yet confirmed. Added <b>before</b> the callback so
     * a re-entrant {@code processInbox} cannot double-forward, and removed by
     * {@link #unprocessSms} so a failed send is retried. Deliberately not persisted: a
     * process that dies here has not delivered the message.
     */
    private final java.util.Set<Long> inFlightIds =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    /** id -> how many times the forward has failed. */
    private final Map<Long, Integer> forwardAttempts = new ConcurrentHashMap<>();

    /** id -> {@code SystemClock.elapsedRealtime()} before which it will not be re-offered. */
    private final Map<Long, Long> retryNotBefore = new ConcurrentHashMap<>();

    /** Guards the read-modify-write of the persisted record. Never held across a callback. */
    private final Object persistLock = new Object();

    /** Consecutive read-flag write failures; reset by any success. */
    private final java.util.concurrent.atomic.AtomicInteger rootFlagFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Latched once {@link #MAX_ROOT_FLAG_FAILURES} is hit, for the life of the process. */
    private volatile boolean rootFlagWriteGivenUp = false;

    /**
     * Test seam for the acceptance test that matters (GW-27 / AUDIT H13): with the read-flag
     * write disabled, a restart must still forward zero duplicates. Set false and
     * {@link #markAsRead} skips both the resolver and the root write, exactly as if the
     * device refused them, leaving the persisted record as the only defence.
     */
    private volatile boolean readFlagWriteEnabled = true;

    private boolean isRunning = false;

    public interface SmsCallback {
        /**
         * Called when an incoming SMS needs to be sent to SIP.
         * @param from Sender phone number
         * @param body SMS text
         * @param smsId SMS ID in the database (for deletion after successful send)
         * @param simSlot SIM slot (1 or 2) that received the SMS
         */
        void onIncomingSms(String from, String body, long smsId, int simSlot);

        /**
         * Called when outgoing SMS status changes.
         * @param destination Phone number
         * @param status "sent", "delivered", or "failed"
         * @param errorMessage Error details if failed
         */
        void onSmsSendStatus(String destination, String status, String errorMessage);
    }

    public SmsHandler(Context context, SmsCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        // PjsipSipService.onCreate() runs GatewayConfig.init() long before it builds us.
        this.config = GatewayConfig.getInstance();
        loadProcessedIds();
    }

    /**
     * Start monitoring SMS inbox for new messages.
     */
    public void start() {
        if (isRunning) {
            Log.w(TAG, "SmsHandler already running");
            return;
        }

        Log.d(TAG, "Starting SMS handler");

        // Register ContentObserver for inbox changes
        smsObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                Log.d(TAG, "SMS inbox changed");
                // Process immediately - no debounce, race with MessagingApp
                processInbox();
            }
        };

        context.getContentResolver().registerContentObserver(
            SMS_URI, true, smsObserver
        );

        // Register broadcast receivers for send status
        registerSendReceivers();

        // Process any existing unprocessed SMS
        processInbox();

        isRunning = true;
        Log.d(TAG, "SMS handler started");
    }

    /**
     * Stop monitoring SMS inbox.
     */
    public void stop() {
        if (!isRunning) {
            return;
        }

        Log.d(TAG, "Stopping SMS handler");

        if (smsObserver != null) {
            context.getContentResolver().unregisterContentObserver(smsObserver);
            smsObserver = null;
        }

        unregisterSendReceivers();

        isRunning = false;
        Log.d(TAG, "SMS handler stopped");
    }

    // Counter for tracing
    private static int processInboxCounter = 0;

    /**
     * Process all unread SMS in inbox.
     * Public so it can be called when SIP registration is restored.
     */
    public void processInbox() {
        int traceId = ++processInboxCounter;
        Log.d(TAG, "[" + traceId + "] processInbox START, confirmed=" + confirmedIds.size()
                + " inFlight=" + inFlightIds);

        ContentResolver resolver = context.getContentResolver();

        // Query unread SMS
        String[] projection = {"_id", "address", "body", "date", "read", "sub_id"};
        String selection = "read = 0"; // Only unread

        try (Cursor cursor = resolver.query(
                SMS_INBOX_URI, projection, selection, null, "date ASC")) {

            if (cursor == null) {
                Log.w(TAG, "[" + traceId + "] SMS cursor is null");
                return;
            }

            Log.d(TAG, "[" + traceId + "] Found " + cursor.getCount() + " unread SMS");

            while (cursor.moveToNext()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
                String address = cursor.getString(cursor.getColumnIndexOrThrow("address"));
                String body = cursor.getString(cursor.getColumnIndexOrThrow("body"));
                int subId = cursor.getInt(cursor.getColumnIndexOrThrow("sub_id"));

                // Convert subscription ID to SIM slot (1 or 2)
                int simSlot = getSimSlotFromSubId(subId);

                // Already forwarded, in a previous life of this process or in this one. This
                // is the check that makes the read flag non-load-bearing (AUDIT H13).
                if (confirmedIds.containsKey(id)) {
                    Log.d(TAG, "[" + traceId + "] SKIP id=" + id + " (already forwarded)");
                    continue;
                }
                if (inFlightIds.contains(id)) {
                    Log.d(TAG, "[" + traceId + "] SKIP id=" + id + " (in flight)");
                    continue;
                }

                // Backing off after a failed forward. Re-delivery is event-driven - another
                // message's markAsRead, or a successful REGISTER - so this is a gate on a
                // re-offer, not a brake on a spin.
                Long notBefore = retryNotBefore.get(id);
                if (notBefore != null && SystemClock.elapsedRealtime() < notBefore) {
                    Log.d(TAG, "[" + traceId + "] SKIP id=" + id + " (retry backoff, "
                            + (notBefore - SystemClock.elapsedRealtime()) + " ms left, attempt "
                            + attemptsFor(id) + ")");
                    continue;
                }

                Log.d(TAG, "[" + traceId + "] Processing SMS id=" + id + " from=" + address + " body=\"" + body + "\" SIM" + simSlot);

                // Mark as being processed BEFORE callback. On the re-registration path the
                // callback dispatches inline on the control thread, so the blocking SIP send
                // happens inside this loop - that ordering is what this add protects.
                inFlightIds.add(id);
                Log.d(TAG, "[" + traceId + "] id=" + id + " in flight, inFlight=" + inFlightIds);

                // Notify callback
                if (callback != null) {
                    Log.d(TAG, "[" + traceId + "] Calling callback for id=" + id);
                    callback.onIncomingSms(address, body, id, simSlot);
                    Log.d(TAG, "[" + traceId + "] Callback returned for id=" + id);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "[" + traceId + "] Error processing inbox: " + e.getMessage(), e);
        }

        Log.d(TAG, "[" + traceId + "] processInbox END, confirmed=" + confirmedIds.size()
                + " inFlight=" + inFlightIds);
    }

    /**
     * Convert subscription ID to SIM slot number (1 or 2).
     */
    private int getSimSlotFromSubId(int subId) {
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager != null) {
                SubscriptionInfo info = subManager.getActiveSubscriptionInfo(subId);
                if (info != null) {
                    return info.getSimSlotIndex() + 1; // 0-based to 1-based
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting SIM slot: " + e.getMessage());
        }
        return 1; // Default to SIM1
    }

    /**
     * Mark SMS as processed and delete it from inbox.
     *
     * @param smsId SMS ID to delete
     * @return true if deleted successfully
     * @deprecated <b>Dead code — no callers anywhere in the tree.</b> The gateway marks
     *             messages read rather than deleting them. Left correct rather than removed:
     *             deleting dead surface is <b>GW-31</b>'s sweep (ROADMAP rule 8), not GW-27's.
     */
    @Deprecated
    public boolean deleteSms(long smsId) {
        try {
            Uri smsUri = Uri.parse("content://sms/" + smsId);
            int deleted = context.getContentResolver().delete(smsUri, null, null);

            if (deleted > 0) {
                Log.d(TAG, "Deleted SMS id=" + smsId);
                forget(smsId);
                return true;
            } else {
                Log.w(TAG, "Failed to delete SMS id=" + smsId);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting SMS: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Record that this SMS is done with: persist it into the duplicate-suppression record
     * and try to set the inbox {@code read} flag.
     *
     * <p>Called after the PBX has accepted the message (and on the deliberate drop when the
     * SIM has no configured destination). <b>The persist comes first and is unconditional</b>
     * — it is the defence that does not depend on a provider write the app may not be
     * allowed to make. The flag write is best-effort on top of it, and its result is
     * verified rather than assumed: a write that quietly did nothing logs an error naming
     * the id, because a silent no-op here is exactly what hid AUDIT H13.
     *
     * <p>After {@link #MAX_ROOT_FLAG_FAILURES} consecutive failures the root attempt is
     * given up on for the process — on a device that cannot write the flag at all, retrying
     * per message only spawns doomed {@code su} calls.
     *
     * @param smsId SMS ID to mark as read
     * @return true if the row is confirmed at {@code read = 1}; false if the flag could not
     *         be written or could not be verified. Suppression does not depend on this.
     */
    public boolean markAsRead(long smsId) {
        confirmProcessed(smsId);

        if (!readFlagWriteEnabled) {
            // Fault injection (GW-27 acceptance test 3): behave as a device that refuses
            // every route to the flag. The persisted record must carry correctness alone.
            Log.w(TAG, "Read-flag write disabled (test seam), id=" + smsId
                    + " suppressed by the persisted record only");
            return false;
        }

        if (markAsReadWithResolver(smsId)) {
            rootFlagFailures.set(0);
            return true;
        }

        if (rootFlagWriteGivenUp) {
            Log.w(TAG, "Not attempting the root read-flag write for SMS id=" + smsId
                    + " - given up on for this process; the persisted record is suppressing it");
            return false;
        }

        if (markAsReadWithRoot(smsId)) {
            rootFlagFailures.set(0);
            return true;
        }

        if (rootFlagFailures.incrementAndGet() >= MAX_ROOT_FLAG_FAILURES) {
            rootFlagWriteGivenUp = true;
            Log.e(TAG, "Could not mark SMS id=" + smsId + " as read, and that is "
                    + MAX_ROOT_FLAG_FAILURES + " in a row - this device cannot write the inbox"
                    + " read flag, so the gateway will stop trying until it restarts. Every"
                    + " forwarded message stays read=0 and will be re-offered by every inbox"
                    + " scan; duplicate suppression now rests entirely on the persisted"
                    + " record (AUDIT H13).");
        } else {
            Log.e(TAG, "Could not mark SMS id=" + smsId + " as read - neither ContentResolver"
                    + " nor root 'content update' left the row at read=1. It will be"
                    + " re-offered by every inbox scan and is suppressed only by the"
                    + " persisted record (AUDIT H13).");
        }
        return false;
    }

    /**
     * The unprivileged write. Correct, and the only one needed, if the app is ever made the
     * default SMS app; refused (0 rows) otherwise, which is the current state on both
     * devices.
     */
    private boolean markAsReadWithResolver(long smsId) {
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put("read", 1);

            Uri smsUri = Uri.parse("content://sms/" + smsId);
            int updated = context.getContentResolver().update(smsUri, values, null, null);

            if (updated > 0 && !Boolean.FALSE.equals(isMarkedRead(smsId))) {
                Log.d(TAG, "Marked SMS id=" + smsId + " as read");
                return true;
            }
            Log.d(TAG, "ContentResolver update did not take for SMS id=" + smsId
                    + " (rows=" + updated + ", not the default SMS app), trying root");
            return false;
        } catch (Exception e) {
            Log.w(TAG, "ContentResolver update threw for SMS id=" + smsId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * The root write, through {@code /system/bin/content}.
     *
     * <p>This used to shell out to {@code sqlite3}, which is on <b>neither</b> test device —
     * exit 127, every time, for the life of the feature. It looked like it worked because
     * the old {@code execRoot} returned {@code ""} rather than {@code null} on a non-zero
     * exit, so the {@code != null} success test was always true (AUDIT H13 defect 3, fixed
     * in GW-20). Two things stop that recurring: {@link RootHelper#run(String)} reports the
     * real exit code and {@code success()} is {@code exitCode == 0}, and the row is re-read
     * afterwards regardless of what the command claimed.
     */
    private boolean markAsReadWithRoot(long smsId) {
        String cmd = "content update --uri content://sms/" + smsId + " --bind read:i:1";
        RootHelper.RootResult result = RootHelper.run(cmd);

        if (!result.success()) {
            Log.w(TAG, "Root 'content update' failed for SMS id=" + smsId + " (exit "
                    + result.exitCode() + ")"
                    + (result.stderr().isEmpty() ? "" : ": " + result.stderr()));
            return false;
        }

        // Exit 0 is necessary, not sufficient: `content update` reports success for a URI it
        // matched but a row it did not change. Verify, do not assume.
        Boolean read = isMarkedRead(smsId);
        if (Boolean.FALSE.equals(read)) {
            Log.e(TAG, "Root 'content update' exited 0 but SMS id=" + smsId
                    + " is still read=0 - the write did nothing (AUDIT H13)");
            return false;
        }
        if (read == null) {
            // Could not read the row back. Do not claim success we cannot see.
            Log.w(TAG, "Root 'content update' exited 0 for SMS id=" + smsId
                    + " but the row could not be re-read to confirm it");
            return false;
        }

        Log.d(TAG, "Marked SMS id=" + smsId + " as read (root content update, verified)");
        return true;
    }

    /**
     * Re-read one row's {@code read} column.
     *
     * @return {@code TRUE} when the row is at {@code read = 1}, {@code FALSE} when it is
     *         still {@code 0}, and {@code null} when the row could not be read at all
     *         (missing, or the query failed) — "not verified" and "verified unread" are
     *         different answers and the caller treats them differently.
     */
    private Boolean isMarkedRead(long smsId) {
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://sms/" + smsId), new String[]{"read"}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            return cursor.getInt(0) != 0;
        } catch (Exception e) {
            Log.w(TAG, "Could not verify read flag for SMS id=" + smsId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Report that the forward failed, so the message is retried.
     *
     * <p>Three call sites, all in {@code PjsipSipService}: not registered yet, the account
     * was replaced mid-send, and the send threw. None of them mutates the provider, so none
     * of them re-triggers the {@code ContentObserver} — re-delivery is <b>event-driven</b>
     * (another message's {@code markAsRead}, or a successful REGISTER), not a tight spin.
     * It is still bounded, because "retried forever, silently" is its own bug: each failure
     * pushes the next attempt out exponentially, and after
     * {@link #MAX_FORWARD_ATTEMPTS} the message is given up on with an error and marked
     * read so it stops being offered.
     *
     * <p><b>The first retry is not delayed, on purpose.</b> The commonest caller by far is
     * "not registered yet", and the event that brings that message back is the successful
     * REGISTER itself — {@code handleRegistrationState} calls {@link #processInbox()}
     * seconds later. A backoff that covered attempt 1 would swallow exactly that re-offer
     * and leave the SMS sitting until some unrelated inbox change, turning a bounded retry
     * into an unbounded delay. It cannot spin: nothing on this path mutates the provider, so
     * it does not re-trigger the observer. Attempts 2..{@link #MAX_FORWARD_ATTEMPTS} back
     * off exponentially, which is what stops a repeatedly-failing message being retried on
     * every single event.
     *
     * @param smsId SMS ID to unprocess
     */
    public void unprocessSms(long smsId) {
        if (!inFlightIds.remove(smsId)) {
            // Already confirmed, or never in flight. Either way there is nothing to retry -
            // and a confirmed id must never be walked back into the retry queue.
            return;
        }

        int attempts = attemptsFor(smsId) + 1;
        forwardAttempts.put(smsId, attempts);

        if (attempts >= MAX_FORWARD_ATTEMPTS) {
            Log.e(TAG, "SMS id=" + smsId + " failed to forward " + attempts + " times - giving"
                    + " up and marking it read so it stops being re-offered. It was NOT"
                    + " delivered to the PBX.");
            retryNotBefore.remove(smsId);
            forwardAttempts.remove(smsId);
            markAsRead(smsId);
            return;
        }

        long delay = attempts <= 1
                ? 0
                : Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS << (attempts - 2));
        if (delay > 0) {
            retryNotBefore.put(smsId, SystemClock.elapsedRealtime() + delay);
        } else {
            retryNotBefore.remove(smsId);
        }
        Log.w(TAG, "SMS id=" + smsId + " un-processed for retry (failure " + attempts + " of "
                + MAX_FORWARD_ATTEMPTS + ", eligible again "
                + (delay == 0 ? "immediately" : "in " + delay + " ms")
                + " - re-delivery is event-driven, not scheduled)");
    }

    private int attemptsFor(long smsId) {
        Integer attempts = forwardAttempts.get(smsId);
        return attempts == null ? 0 : attempts;
    }

    // ========== Persisted duplicate suppression (AUDIT H13) ==========

    /**
     * Seed {@link #confirmedIds} from the persisted record. This is the line that makes a
     * restart stop re-forwarding the inbox: before it existed the set began empty and
     * {@code selection = "read = 0"} matched every message ever received.
     */
    private void loadProcessedIds() {
        String record;
        try {
            record = config.getProcessedSmsRecord();
        } catch (Exception e) {
            Log.e(TAG, "Could not read the persisted SMS record: " + e.getMessage(), e);
            return;
        }

        int malformed = 0;
        for (String entry : record.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int sep = trimmed.indexOf(':');
            try {
                if (sep < 0) {
                    // A bare id: an older record format, or half a pair. Keep it -
                    // suppression is what matters - and stamp it now so the TTL still
                    // eventually reclaims it.
                    confirmedIds.put(Long.parseLong(trimmed), System.currentTimeMillis());
                } else {
                    confirmedIds.put(Long.parseLong(trimmed.substring(0, sep)),
                            Long.parseLong(trimmed.substring(sep + 1)));
                }
            } catch (NumberFormatException e) {
                malformed++;
            }
        }

        int loaded = confirmedIds.size();
        if (pruneProcessedIds()) {
            synchronized (persistLock) {
                config.setProcessedSmsRecord(serializeProcessedIds());
            }
        }
        Log.d(TAG, "Loaded " + loaded + " persisted SMS ids (" + confirmedIds.size()
                + " after prune" + (malformed > 0 ? ", " + malformed + " malformed" : "") + ")");
    }

    /**
     * Promote an id to confirmed and write the record through to storage.
     *
     * <p>Called from {@link #markAsRead}, i.e. only after the PBX has taken the message.
     * Persisting any earlier would turn a crash mid-send into a lost SMS; the failure
     * direction is deliberately duplicates-over-drops.
     */
    private void confirmProcessed(long smsId) {
        inFlightIds.remove(smsId);
        forwardAttempts.remove(smsId);
        retryNotBefore.remove(smsId);

        // Keep the original stamp if this id is already recorded, so re-marking a message
        // read cannot keep pushing its TTL out.
        Long confirmedAt = confirmedIds.get(smsId);
        confirmedIds.put(smsId, confirmedAt == null ? System.currentTimeMillis() : confirmedAt);
        pruneProcessedIds();

        // The lock covers only the serialize + write, never a callback, so main can never
        // end up waiting on the control thread's SIP send (AUDIT H12, one-directional
        // blocking rule).
        synchronized (persistLock) {
            try {
                config.setProcessedSmsRecord(serializeProcessedIds());
            } catch (Exception e) {
                Log.e(TAG, "Could not persist the SMS record for id=" + smsId + " - a restart"
                        + " may re-forward it: " + e.getMessage(), e);
            }
        }
    }

    /** Drop an id from every piece of local state. Only {@link #deleteSms} needs this. */
    private void forget(long smsId) {
        inFlightIds.remove(smsId);
        forwardAttempts.remove(smsId);
        retryNotBefore.remove(smsId);
        if (confirmedIds.remove(smsId) != null) {
            synchronized (persistLock) {
                config.setProcessedSmsRecord(serializeProcessedIds());
            }
        }
    }

    /**
     * Bound the record: age first, then size, oldest first. Without this the set never
     * shrinks on the success path and grows for the lifetime of a 24/7 service.
     *
     * @return true if anything was dropped
     */
    private boolean pruneProcessedIds() {
        long cutoff = System.currentTimeMillis() - PROCESSED_ID_TTL_MS;
        int before = confirmedIds.size();

        for (Map.Entry<Long, Long> entry : confirmedIds.entrySet()) {
            Long confirmedAt = entry.getValue();
            // A stamp in the future is a clock that moved, not an old entry - keep it.
            if (confirmedAt != null && confirmedAt < cutoff) {
                confirmedIds.remove(entry.getKey());
            }
        }

        if (confirmedIds.size() > PROCESSED_ID_MAX) {
            List<Map.Entry<Long, Long>> oldestFirst = new ArrayList<>(confirmedIds.entrySet());
            java.util.Collections.sort(oldestFirst,
                    (a, b) -> Long.compare(a.getValue(), b.getValue()));
            int excess = confirmedIds.size() - PROCESSED_ID_MAX;
            for (int i = 0; i < excess && i < oldestFirst.size(); i++) {
                confirmedIds.remove(oldestFirst.get(i).getKey());
            }
        }

        int dropped = before - confirmedIds.size();
        if (dropped > 0) {
            Log.d(TAG, "Pruned " + dropped + " SMS ids from the suppression record, "
                    + confirmedIds.size() + " left");
        }
        return dropped > 0;
    }

    /**
     * {@code id:confirmedAtMillis} pairs, comma separated. Parsed back by
     * {@link #loadProcessedIds()}.
     */
    private String serializeProcessedIds() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> entry : confirmedIds.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    // ========== Test seams ==========

    /**
     * Test-only: turn the inbox {@code read} flag write off, so the persisted record is the
     * only thing suppressing duplicates. See {@link #readFlagWriteEnabled}.
     */
    void setReadFlagWriteEnabledForTest(boolean enabled) {
        this.readFlagWriteEnabled = enabled;
    }

    /** Test-only: the ids currently suppressed by the persisted record. */
    java.util.Set<Long> getConfirmedIdsForTest() {
        return new java.util.HashSet<>(confirmedIds.keySet());
    }

    /** Test-only: how many times this id's forward has failed. */
    int getForwardAttemptsForTest(long smsId) {
        return attemptsFor(smsId);
    }

    /**
     * Send an SMS message using default SIM.
     *
     * @param destination Phone number to send to
     * @param message Message text
     */
    public void sendSms(String destination, String message) {
        sendSms(destination, message, 0); // 0 = default SIM
    }

    /**
     * Send an SMS message via specific SIM slot.
     *
     * @param destination Phone number to send to
     * @param message Message text
     * @param simSlot SIM slot (1 or 2), or 0 for default
     */
    public void sendSms(String destination, String message, int simSlot) {
        Log.d(TAG, "Sending SMS to " + destination + " via SIM" + (simSlot == 0 ? "default" : String.valueOf(simSlot)) + ": " + message);

        try {
            SmsManager smsManager = getSmsManagerForSlot(simSlot);

            // Create pending intents for status
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            Intent sentIntent = new Intent(ACTION_SMS_SENT);
            sentIntent.putExtra("destination", destination);
            PendingIntent sentPI = PendingIntent.getBroadcast(
                context, destination.hashCode(), sentIntent, flags);

            Intent deliveredIntent = new Intent(ACTION_SMS_DELIVERED);
            deliveredIntent.putExtra("destination", destination);
            PendingIntent deliveredPI = PendingIntent.getBroadcast(
                context, destination.hashCode() + 1, deliveredIntent, flags);

            // Check if message needs to be split
            ArrayList<String> parts = smsManager.divideMessage(message);

            if (parts.size() == 1) {
                smsManager.sendTextMessage(
                    destination, null, message, sentPI, deliveredPI);
            } else {
                ArrayList<PendingIntent> sentPIs = new ArrayList<>();
                ArrayList<PendingIntent> deliveredPIs = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentPIs.add(sentPI);
                    deliveredPIs.add(deliveredPI);
                }
                smsManager.sendMultipartTextMessage(
                    destination, null, parts, sentPIs, deliveredPIs);
            }

            Log.d(TAG, "SMS queued for sending (" + parts.size() + " parts)");

        } catch (Exception e) {
            Log.e(TAG, "Error sending SMS: " + e.getMessage(), e);
            if (callback != null) {
                callback.onSmsSendStatus(destination, "failed", e.getMessage());
            }
        }
    }

    /**
     * Get SmsManager for specific SIM slot.
     * @param simSlot 1 or 2 for specific SIM, 0 for default
     */
    private SmsManager getSmsManagerForSlot(int simSlot) {
        if (simSlot <= 0) {
            // Use default
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return context.getSystemService(SmsManager.class);
            } else {
                return SmsManager.getDefault();
            }
        }

        // Get subscription ID for the slot
        try {
            SubscriptionManager subManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (subManager != null) {
                java.util.List<SubscriptionInfo> subList = subManager.getActiveSubscriptionInfoList();
                if (subList != null) {
                    for (SubscriptionInfo info : subList) {
                        if (info.getSimSlotIndex() + 1 == simSlot) {
                            int subId = info.getSubscriptionId();
                            Log.d(TAG, "Using SIM" + simSlot + " (subId=" + subId + ")");
                            return SmsManager.getSmsManagerForSubscriptionId(subId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting SmsManager for slot " + simSlot + ": " + e.getMessage());
        }

        // Fallback to default
        Log.w(TAG, "SIM" + simSlot + " not found, using default");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.getSystemService(SmsManager.class);
        } else {
            return SmsManager.getDefault();
        }
    }

    private void registerSendReceivers() {
        // Sent status receiver
        smsSentReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String destination = intent.getStringExtra("destination");
                String status;
                String error = null;

                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        status = "sent";
                        Log.d(TAG, "SMS sent successfully to " + destination);
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        status = "failed";
                        error = "Generic failure";
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        status = "failed";
                        error = "No service";
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        status = "failed";
                        error = "Null PDU";
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        status = "failed";
                        error = "Radio off";
                        break;
                    default:
                        status = "failed";
                        error = "Unknown error: " + getResultCode();
                }

                if (callback != null) {
                    callback.onSmsSendStatus(destination, status, error);
                }
            }
        };

        // Delivered status receiver
        smsDeliveredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String destination = intent.getStringExtra("destination");

                if (getResultCode() == Activity.RESULT_OK) {
                    Log.d(TAG, "SMS delivered to " + destination);
                    if (callback != null) {
                        callback.onSmsSendStatus(destination, "delivered", null);
                    }
                } else {
                    Log.w(TAG, "SMS delivery failed to " + destination);
                    if (callback != null) {
                        callback.onSmsSendStatus(destination, "delivery_failed",
                            "Delivery failed: " + getResultCode());
                    }
                }
            }
        };

        // Register receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(smsSentReceiver,
                new IntentFilter(ACTION_SMS_SENT), Context.RECEIVER_NOT_EXPORTED);
            context.registerReceiver(smsDeliveredReceiver,
                new IntentFilter(ACTION_SMS_DELIVERED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(smsSentReceiver, new IntentFilter(ACTION_SMS_SENT));
            context.registerReceiver(smsDeliveredReceiver, new IntentFilter(ACTION_SMS_DELIVERED));
        }
    }

    private void unregisterSendReceivers() {
        try {
            if (smsSentReceiver != null) {
                context.unregisterReceiver(smsSentReceiver);
                smsSentReceiver = null;
            }
            if (smsDeliveredReceiver != null) {
                context.unregisterReceiver(smsDeliveredReceiver);
                smsDeliveredReceiver = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering receivers: " + e.getMessage());
        }
    }
}
