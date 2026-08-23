package org.onetwoone.gateway.core;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.onetwoone.gateway.BuildConfig;

/**
 * The one thread that owns call, audio-bridge and SIP-account lifecycle state.
 *
 * <p>It is <em>not</em> the main looper: the operations it serialises block for seconds by
 * nature - root shell-outs, ALSA open with retry, SIP REGISTER, {@code hangupAllCalls} - and
 * must never run on main. It is not a pjsua worker either: returning from a pjsua callback
 * immediately is what removes the re-entrancy hazard of calling back into pjsua from inside
 * its own callback.
 *
 * <h3>The one-directional blocking rule</h3>
 * <b>The control thread may block on main. Main must NEVER block waiting on the control
 * thread.</b> {@code SipEndpointManager.createEndpoint()} hops to main to construct the
 * {@code Endpoint} (pjsua auto-registers only the thread that loaded the native library,
 * which is main) and waits on a 30 s latch; after GW-10 the waiter is this thread, and that
 * is fine. Any main-thread code that needs a result from here must take a callback, not a
 * latch / {@code Future.get} / {@code join}. The single deliberate exception is
 * {@link #quitSafely(long)} at service destroy - see its javadoc.
 *
 * <h3>pjlib registration</h3>
 * GW-10 §1 assumed the thread could register with pjlib "at construction". It cannot: this
 * thread is started in {@code Service.onCreate}, and the pjsua endpoint is created <em>by a
 * task posted onto this very thread</em>, so at construction there is nothing to register
 * with. Registration is therefore done lazily and exactly once, from
 * {@link #ensurePjlibRegistered()}:
 * <ul>
 *   <li>every task is dispatched through {@link #dispatch(Runnable)}, which asks the
 *       registrar before running the task and stops asking as soon as one attempt
 *       succeeds. Before the endpoint exists the registrar returns false and the task runs
 *       unregistered - which is safe, because with no endpoint there is no pjsua to call;
 *   <li>SIP init calls {@link #registerWithPjlib()} explicitly the moment it has created
 *       the endpoint, so the <em>rest of that same task</em> may call pjsua2 freely. The two
 *       paths share one flag, so the thread is handed to pjlib once and only once.
 * </ul>
 * The steady-state cost is one non-volatile boolean read, on this thread, per task.
 *
 * <h3>What must never post here</h3>
 * The pjmedia RT callbacks ({@code GsmAudioPort.onFrameRequested} /
 * {@code onFrameReceived}) run every 20 ms and must never post, block, or take a lock this
 * thread can hold across I/O. See GW-01 / GW-23.
 */
public final class GatewayControlThread {
    /**
     * Deliberately NOT "GatewayControl": {@code GatewayControlReceiver} already logs under
     * that tag, and two unrelated components sharing one tag makes the Phase 1 verification
     * logs ambiguous. The <em>thread</em> is still named {@link #THREAD_NAME}.
     */
    private static final String TAG = "GwControlThread";

    /** The pjlib thread name and the {@link HandlerThread} name. */
    public static final String THREAD_NAME = "GatewayControl";

    /** How long {@link #quitSafely()} waits for the queue to drain. */
    public static final long DEFAULT_QUIT_TIMEOUT_MS = 1500L;

    /**
     * How the control thread hands itself to pjlib. Exactly
     * {@code SipEndpointManager::registerThread} in production; a fake in tests, which is
     * also the only way to prove "registered exactly once".
     */
    public interface PjlibRegistrar {
        /** @return true once the thread is known to pjlib; false while no endpoint exists. */
        boolean register(String threadName);
    }

    /** Non-null only when this object created the thread, i.e. not in the injected-looper form. */
    private final HandlerThread ownedThread;
    private final Looper looper;
    private final Handler handler;
    private final PjlibRegistrar registrar;

    /**
     * Confined to the control thread - read and written only inside {@link #dispatch} and
     * {@link #registerWithPjlib()}, both of which run there. Deliberately not volatile:
     * making it visible elsewhere would invite a second registration from another thread.
     */
    private boolean pjlibRegistered;

    /**
     * Production form: creates and starts the {@code GatewayControl} thread.
     *
     * @param registrar how to register with pjlib once an endpoint exists; may be null in
     *                  tests that do not care
     */
    public GatewayControlThread(PjlibRegistrar registrar) {
        this.registrar = registrar;
        this.ownedThread = new HandlerThread(THREAD_NAME);
        this.ownedThread.start();
        this.looper = this.ownedThread.getLooper();
        this.handler = new Handler(this.looper);
        Log.i(TAG, "Control thread started");
    }

    /**
     * Test form: run on a caller-supplied looper so a JVM test can drive the queue with
     * {@code shadowOf(looper).idle()} (plan §2.5). The looper is not owned, so
     * {@link #quitSafely(long)} leaves it alone.
     */
    public GatewayControlThread(Looper looper, PjlibRegistrar registrar) {
        this.registrar = registrar;
        this.ownedThread = null;
        this.looper = looper;
        this.handler = new Handler(looper);
    }

    // ========== Queue ==========

    /** Queue {@code task} on the control thread. Never runs it inline, even from here. */
    public void post(Runnable task) {
        postDelayed(task, 0L);
    }

    /**
     * Queue {@code task} to run after {@code delayMs}.
     *
     * <p>The task is posted with itself as the message token so
     * {@link #removeCallbacks(Runnable)} still works even though what actually reaches the
     * looper is the {@link #dispatch} wrapper, not the caller's runnable.
     */
    public void postDelayed(Runnable task, long delayMs) {
        if (task == null) {
            return;
        }
        handler.postAtTime(() -> dispatch(task), task, SystemClock.uptimeMillis() + delayMs);
    }

    /** Cancel a task queued by {@link #post} / {@link #postDelayed} that has not run yet. */
    public void removeCallbacks(Runnable task) {
        if (task == null) {
            return;
        }
        handler.removeCallbacksAndMessages(task);
    }

    /**
     * Run {@code task} inline when the caller is already the control thread, otherwise queue
     * it. This is what stops a re-entrant lifecycle command (a control-thread handler calling
     * a public entry point that routes back here) from deadlocking behind itself, and it
     * keeps the synchronous semantics such a caller already had.
     */
    public void runOrPost(Runnable task) {
        if (task == null) {
            return;
        }
        if (isCurrent()) {
            dispatch(task);
        } else {
            post(task);
        }
    }

    private void dispatch(Runnable task) {
        ensurePjlibRegistered();
        task.run();
    }

    // ========== Identity ==========

    /** True when the calling thread is the control thread. */
    public boolean isCurrent() {
        return Looper.myLooper() == looper;
    }

    /** The control looper, so tests can drive it and callers can build their own handlers. */
    public Looper getLooper() {
        return looper;
    }

    /** True while the thread is alive (always true for an injected looper). */
    public boolean isAlive() {
        return ownedThread == null || ownedThread.isAlive();
    }

    /**
     * Guard for every lifecycle mutator routed through this thread - the mechanism that
     * keeps the threading model from eroding.
     *
     * <p>Throws in debug builds (same shape as {@code DeviceMuteManager.assertOffMain}) and
     * logs loudly in release (same shape as {@code PjsipSipService.assertMainThread}): a
     * violation is a wrong-thread bug to fix, not a reason to kill a live gateway on a
     * user's phone.
     */
    public void assertOnControlThread(String what) {
        if (isCurrent()) {
            return;
        }
        String message = what + " must run on " + THREAD_NAME + ", not "
                + Thread.currentThread().getName();
        if (BuildConfig.DEBUG) {
            throw new IllegalStateException(message);
        }
        Log.e(TAG, message);
    }

    /** @see #assertOnControlThread(String) */
    public void assertOnControlThread() {
        assertOnControlThread("This operation");
    }

    // ========== pjlib ==========

    /**
     * Hand this thread to pjlib now, if it has not been handed over already. Called by SIP
     * init the instant it has created the endpoint, so the remainder of that same task may
     * call pjsua2.
     *
     * @return true if the control thread is registered with pjlib
     */
    public boolean registerWithPjlib() {
        assertOnControlThread("registerWithPjlib");
        return ensurePjlibRegistered();
    }

    /** True once pjlib knows this thread. Control-thread only. */
    public boolean isRegisteredWithPjlib() {
        assertOnControlThread("isRegisteredWithPjlib");
        return pjlibRegistered;
    }

    private boolean ensurePjlibRegistered() {
        if (pjlibRegistered || registrar == null) {
            return pjlibRegistered;
        }
        if (registrar.register(THREAD_NAME)) {
            pjlibRegistered = true;
            Log.i(TAG, "Control thread registered with pjlib");
        }
        return pjlibRegistered;
    }

    // ========== Shutdown ==========

    /** @see #quitSafely(long) */
    public void quitSafely() {
        quitSafely(DEFAULT_QUIT_TIMEOUT_MS);
    }

    /**
     * Stop the control thread once the work already queued on it has drained, then wait up
     * to {@code joinTimeoutMs} for it to die.
     *
     * <p>This is the <em>one</em> place main waits on the control thread, and it is
     * deliberately bounded. The pathological interleaving is real: if the control thread is
     * parked inside {@code createEndpointOnMainThread}'s 30 s latch when the service is
     * destroyed, the runnable it is waiting for is queued behind {@code onDestroy} on main
     * and cannot run while main sits in this join. Both waits are bounded, so this resolves
     * after {@code joinTimeoutMs} rather than deadlocking - main gives up, logs, and the
     * control thread's latch then times out on its own. Do not turn this into an unbounded
     * join, and do not add a second main-blocks-on-control wait anywhere else.
     */
    public void quitSafely(long joinTimeoutMs) {
        if (ownedThread == null) {
            // Injected looper (tests): not ours to quit.
            return;
        }
        ownedThread.quitSafely();
        try {
            ownedThread.join(joinTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ownedThread.isAlive()) {
            Log.w(TAG, "Control thread still running after " + joinTimeoutMs
                    + " ms - abandoning it");
        } else {
            Log.i(TAG, "Control thread stopped");
        }
    }
}
