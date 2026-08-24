package org.onetwoone.gateway.call;

import android.os.SystemClock;
import android.util.Log;

import org.onetwoone.gateway.core.ControlThread;
import org.onetwoone.gateway.core.GatewayControlThread;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Deletes finished pjsua2 {@code Call} objects on the control thread, at a moment we choose.
 * AUDIT H7, second half.
 *
 * <h3>Why this exists, and why the old comment had it backwards</h3>
 * Three places used to say some version of <em>"DON'T delete the call object - PJSIP manages
 * the native lifecycle; it will be GC'd eventually."</em> The second clause is what makes the
 * first one wrong. A pjsua2 {@code Call} is constructed with {@code swigCMemOwn = true} and
 * {@code director_connect(..., true, true)} - a <b>weak</b> director global ref. The Java
 * object is therefore perfectly collectible, and when it is collected its {@code finalize()}
 * calls {@code delete_Call} on the <b>FinalizerDaemon thread, which is not registered with
 * pjlib</b> - the same class of thread behind every historical tombstone in this project.
 *
 * <p>So the "never delete" policy never prevented deletion. It deferred it to an unpredictable
 * moment on the worst available thread. This class does the same deletion at a chosen moment
 * on the {@link GatewayControlThread}, which <em>is</em> pjlib-registered. That is strictly
 * better even though the moment is a heuristic.
 *
 * <h3>This is a heuristic, and it is labelled one deliberately</h3>
 * ROADMAP rule 4 asks for liveness proven with no window. <b>That cannot be satisfied here</b>
 * and this class does not pretend otherwise:
 *
 * <ul>
 *   <li>"The callback is posted, so the handler runs after the pjsua worker returned" is
 *       <b>false</b>. {@code Handler.post} orders queue entry, not stack unwind; the two run
 *       concurrently, and AUDIT E5 measured the pjsua worker sitting inside
 *       {@code pjsua_media_channel_deinit} for up to <b>50 seconds</b> around exactly this
 *       point. Nothing may be built on that argument.
 *   <li>The only <em>checkable</em> liveness signal the bindings expose is
 *       {@link Doomed#getId()} {@code == } {@link #PJSUA_INVALID_ID}: once pjsua releases the
 *       call slot, the id is invalidated. It is evaluated here, on the control thread, which is
 *       pjlib-registered. It is evidence, not proof - it says pjsua has let go of the slot, not
 *       that every worker has unwound.
 *   <li>Deleting too early does <b>not</b> present as an exception. pjmedia assertion failures
 *       are {@code abort()}. It presents as a tombstone, which is why the 500-cycle soak is
 *       the safety test and not just the leak test.
 * </ul>
 *
 * <p>Given that, the rules are conservative:
 * <ol>
 *   <li>A grave is never opened before {@link #MIN_GRAVE_MS} has passed.
 *   <li>A call is deleted <b>only</b> when its id has gone invalid. It is never forced.
 *   <li>A call whose id is still valid after {@link #MAX_WAIT_MS} is <b>abandoned</b>, not
 *       deleted: the reference is dropped and the finalizer inherits it, which is exactly the
 *       old behaviour. Refusing to delete is always an option; deleting a live call is not.
 * </ol>
 *
 * <p>The probe itself cannot crash: {@code Call::getId()} is a plain field read on a C++ object
 * this process owns (pjsua only holds it as {@code user_data}), and this class keeps a strong
 * reference for as long as it intends to probe, so the finalizer cannot free it underneath.
 *
 * <h3>Threading</h3>
 * Control thread only, asserted. The list is plain (not synchronised) because of that.
 *
 * <h3>If this has to be reverted</h3>
 * Revert the {@code bury(...)} call sites only. Keep this class and keep
 * {@code GatewayCall}'s {@code callsCreated}/{@code callsDeleted} counters - knowing the rate
 * at which {@code Call} objects are created and finalised is worth having on its own, and with
 * the burials gone the counters simply report what the finalizer does instead.
 */
@ControlThread
public final class CallGraveyard {

    private static final String TAG = "CallGrave";

    /**
     * {@code org.pjsip.pjsua2.pjsua_invalid_id_const_.PJSUA_INVALID_ID}, restated so this class
     * has no compile-time dependency on pjsua2 and can therefore be unit-tested on the JVM.
     */
    public static final int PJSUA_INVALID_ID = -1;

    /** No grave is opened before this. Nothing magic about it; it is a "not immediately". */
    static final long MIN_GRAVE_MS = 2_000L;

    /** How often the graveyard re-probes. */
    static final long SWEEP_INTERVAL_MS = 2_000L;

    /**
     * After this, a call whose id is still valid is abandoned to the finalizer rather than
     * deleted. Sized to clear AUDIT E5's measured 50 s worst case for
     * {@code pjsua_media_channel_deinit} with margin.
     */
    static final long MAX_WAIT_MS = 60_000L;

    /**
     * Insurance for a 24/7 device whose control thread has quit with graves outstanding: the
     * sweep would never run again and the list would only grow. In normal operation it holds
     * at most one or two entries.
     */
    static final int MAX_GRAVES = 64;

    /**
     * What the graveyard needs of a {@code Call}. {@code GatewayCall} satisfies it through
     * methods it inherits from pjsua2's {@code Call}; the seam exists so this class can be
     * driven by a JVM test, which cannot construct a real {@code Call}.
     */
    public interface Doomed {
        /** {@link #PJSUA_INVALID_ID} once pjsua has released the call slot. */
        int getId();

        /** The SWIG destructor. Idempotent. */
        void delete();
    }

    /** Injectable so tests do not have to move a real clock. */
    interface Clock {
        long nowMs();
    }

    private static final class Grave {
        final Doomed call;
        final String why;
        final long buriedAtMs;

        Grave(Doomed call, String why, long buriedAtMs) {
            this.call = call;
            this.why = why;
            this.buriedAtMs = buriedAtMs;
        }
    }

    private final GatewayControlThread control;
    private final Clock clock;
    private final List<Grave> graves = new ArrayList<>();
    private final Runnable sweepTask = this::sweep;

    private boolean sweepArmed;
    private long deleted;
    private long abandoned;

    public CallGraveyard(GatewayControlThread control) {
        this(control, SystemClock::elapsedRealtime);
    }

    // Visible for testing.
    CallGraveyard(GatewayControlThread control, Clock clock) {
        if (control == null) {
            throw new IllegalArgumentException("CallGraveyard needs the control thread");
        }
        this.control = control;
        this.clock = clock;
    }

    /**
     * Take ownership of a finished call.
     *
     * <p>The caller must already have {@code dispose()}d it and dropped every reference to it -
     * after this returns, the only thing that may touch the object is the sweep.
     *
     * <p>Idempotent by identity: the same call reaching two burial sites (a
     * {@code hangupSipCall} followed by its own {@code DISCONNECTED}) buries it once.
     *
     * @param why short reason, for the log only
     */
    @ControlThread
    public void bury(Doomed call, String why) {
        control.assertOnControlThread("CallGraveyard.bury");
        if (call == null) {
            return;
        }
        for (Grave g : graves) {
            if (g.call == call) {
                return;
            }
        }
        if (graves.size() >= MAX_GRAVES) {
            // Only reachable if the sweep has stopped running, i.e. the control looper quit.
            Log.w(TAG, "Graveyard full (" + MAX_GRAVES + ") - abandoning the oldest grave to"
                    + " the finalizer; is the control thread still alive?");
            graves.remove(0);
            abandoned++;
        }
        graves.add(new Grave(call, why, clock.nowMs()));
        Log.d(TAG, "Buried a call (" + why + "), " + graves.size() + " awaiting deletion");
        armSweep();
    }

    private void armSweep() {
        if (sweepArmed || graves.isEmpty()) {
            return;
        }
        sweepArmed = true;
        control.postDelayed(sweepTask, SWEEP_INTERVAL_MS);
    }

    /**
     * Probe every grave and delete the ones pjsua has let go of.
     *
     * <p>Package-visible so a test can drive it without a real looper; production only ever
     * reaches it through {@link #armSweep()}.
     */
    @ControlThread
    void sweep() {
        control.assertOnControlThread("CallGraveyard.sweep");
        sweepArmed = false;

        long now = clock.nowMs();
        Iterator<Grave> it = graves.iterator();
        while (it.hasNext()) {
            Grave g = it.next();
            long ageMs = now - g.buriedAtMs;
            if (ageMs < MIN_GRAVE_MS) {
                continue;
            }

            int id = g.call.getId();
            if (id == PJSUA_INVALID_ID) {
                // pjsua has released the slot. Delete here, on the pjlib-registered control
                // thread, rather than leaving it to the FinalizerDaemon.
                it.remove();
                deleted++;
                Log.d(TAG, "Deleting a call " + ageMs + " ms after burial (" + g.why + ")");
                g.call.delete();
            } else if (ageMs >= MAX_WAIT_MS) {
                // Refusing to delete is always safe; deleting a call pjsua still tracks is not.
                it.remove();
                abandoned++;
                Log.w(TAG, "Call still holds pjsua slot " + id + " after " + ageMs
                        + " ms (" + g.why + ") - abandoning it to the finalizer");
            }
        }

        armSweep();
    }

    /** How many calls this graveyard has deleted. Control thread. */
    @ControlThread
    long getDeletedCount() {
        return deleted;
    }

    /** How many it gave up on, i.e. left to the finalizer. Visible for testing. */
    @ControlThread
    long getAbandonedCount() {
        return abandoned;
    }

    /** How many are waiting to be probed. Visible for testing. */
    @ControlThread
    int getPendingCount() {
        return graves.size();
    }
}
