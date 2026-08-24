package org.onetwoone.gateway.core;

/**
 * The mechanism that lets service destroy <b>cancel</b> in-flight lifecycle work instead of
 * waiting for it (GW-26, AUDIT H8c).
 *
 * <h3>Why a bounded join is not enough</h3>
 * {@code PjsipSipService.onDestroy} may only wait on the control thread for a bounded time
 * ({@link GatewayControlThread#quitSafely(long)} — the single main-blocks-on-control
 * exception). When that bound expires the control thread is <em>abandoned</em>, and an
 * abandoned thread parked inside {@code SipEndpointManager.createEndpointOnMainThread}'s 30 s
 * latch does not stay parked: the runnable it waits for was queued behind {@code onDestroy}
 * on main, so the latch resolves the instant {@code onDestroy} returns. The thread then walks
 * on to {@code accountManager.createAccount(service)} and <b>registers a fresh SIP account for
 * a service that no longer exists</b>, against the {@code static} Endpoint. Its callbacks post
 * to a looper that has been quit and are dropped, so nothing ever tears that account down.
 *
 * <p>{@code Looper.quitSafely()} does not help: it still drains messages that are already due,
 * so anything {@code onDestroy} posts <em>will</em> run on the abandoned thread. "Post it and
 * quit" is not cancellation.
 *
 * <h3>The contract</h3>
 * A unit of cancellable work calls {@link #begin()} once, at entry, and keeps the
 * {@link Token} in a local. It then calls {@link Token#throwIfCancelled(String)} before every
 * step that blocks or that mutates process-wide state, and hands the token to any callee that
 * blocks on its behalf. Teardown calls {@link #cancel()}.
 *
 * <p>The {@link Token} indirection is what lets {@code SipEndpointManager} be handed the right
 * to <em>ask</em> whether it has been cancelled without being handed the right to cancel.
 *
 * <h3>Why terminal, rather than a reusable generation</h3>
 * PHASE-2-PLAN §2.7 calls for a "cancellation generation". A generation — a counter that
 * {@link #begin()} snapshots and {@code cancel()} bumps — would let one unit of work be
 * cancelled while a later one stays live. That is the wrong shape here, and dangerously so:
 * the work being cancelled is often <b>still queued</b> rather than running, and a queued task
 * calls {@code begin()} only when the control thread finally dequeues it — <em>after</em>
 * destroy has cancelled. It would then snapshot the new generation, find itself live, and
 * create the very account cancellation exists to prevent.
 *
 * <p>So cancellation is terminal: this object belongs to one service instance, that instance is
 * destroyed exactly once, and after {@link #cancel()} nothing it hands out is ever live again.
 * A new service instance gets a new {@code LifecycleCancellation}.
 *
 * <h3>Threading</h3>
 * {@link #cancel()} is called from main; {@link #begin()} and {@link Token#isCancelled()} from
 * the control thread. One {@code volatile} boolean is the whole of the synchronisation: a
 * cancel that lands during a check is either seen by that check or by the next one, and there
 * is always a next one before anything irreversible happens.
 *
 * <p>Cancellation is advisory, not preemptive. It bounds how far a doomed unit of work gets;
 * it cannot unwind a step already in progress. What makes that sufficient is ordering: the
 * teardown task is queued on the same thread, so anything the doomed init did manage to create
 * is torn down by the task that follows it.
 */
public final class LifecycleCancellation {

    /** Thrown by {@link Token#throwIfCancelled(String)}. Never a reason to retry. */
    public static final class CancelledException extends Exception {
        public CancelledException(String what) {
            super(what + " cancelled by service teardown");
        }
    }

    /** The handle a unit of work holds: it can ask, it cannot cancel. */
    public static final class Token {
        /** Null for {@link #NEVER}. */
        private final LifecycleCancellation owner;

        private Token(LifecycleCancellation owner) {
            this.owner = owner;
        }

        /** True once {@link LifecycleCancellation#cancel()} has run. */
        public boolean isCancelled() {
            return owner != null && owner.cancelled;
        }

        /**
         * @param what the step being abandoned, for the log line
         * @throws CancelledException if teardown has cancelled this work
         */
        public void throwIfCancelled(String what) throws CancelledException {
            if (isCancelled()) {
                throw new CancelledException(what);
            }
        }
    }

    /**
     * A token that is never cancelled, for callers with no lifecycle to cancel against — unit
     * tests, and the no-argument convenience overloads.
     */
    public static final Token NEVER = new Token(null);

    private volatile boolean cancelled;

    /**
     * Issue a token for a unit of work starting now.
     *
     * <p>Already-cancelled after teardown, deliberately — see "Why terminal" above. A task that
     * was still sitting in the control queue when destroy ran reaches this line afterwards, and
     * must not be born live.
     */
    public Token begin() {
        return new Token(this);
    }

    /** Cancel this service instance's lifecycle work, permanently. Idempotent. */
    public void cancel() {
        cancelled = true;
    }

    /** Diagnostics and tests. */
    public boolean isCancelled() {
        return cancelled;
    }
}
