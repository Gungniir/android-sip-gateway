package org.onetwoone.gateway.ui;

/**
 * A one-shot payload for a {@code LiveData} that carries events rather than state (GW-41,
 * plan §4 hazard H-c).
 *
 * <p><b>The problem this exists for.</b> {@code LiveData} is a state holder: it replays its
 * current value to every observer that starts observing, and it re-delivers it to every
 * observer that becomes active again. That is exactly right for "the SIP server is
 * {@code pbx.example.org}" and exactly wrong for "a toast that says <em>Restarting…</em>",
 * because the second one is true once. {@code MainViewModel.toastMessage} was a
 * {@code MutableLiveData<String>}, so the last toast fired again on every configuration
 * change - and this wave introduces two ways to cause one that the old screen did not have:
 * a night-mode switch, and a restructured screen whose observers are attached and detached
 * as the activity is recreated.
 *
 * <p>It was latent rather than harmless. "Disconnected" re-appearing after a rotation is a
 * message about a thing that did not just happen, on the one screen whose whole job is
 * telling the truth about what the gateway is doing.
 *
 * <p><b>The contract.</b> The first caller of {@link #getContentIfNotHandled()} gets the
 * payload; every caller after that gets {@code null}. {@link #peek()} reads it without
 * consuming, for a test or a log.
 *
 * <p><b>Threading.</b> Not synchronised, deliberately. Events are posted with
 * {@code setValue} and consumed in observers, both of which are main-thread-only, and adding
 * a lock here would suggest a cross-thread use that is not supported.
 *
 * @param <T> the payload type
 */
public final class Event<T> {

    private final T content;
    private boolean handled;

    public Event(T content) {
        this.content = content;
    }

    /**
     * The payload, once. Returns {@code null} on every call after the first, which is what
     * makes a replayed {@code LiveData} value a no-op rather than a repeated toast.
     */
    public T getContentIfNotHandled() {
        if (handled) {
            return null;
        }
        handled = true;
        return content;
    }

    /** The payload, without consuming it. For tests and logging - never for display. */
    public T peek() {
        return content;
    }

    /** Whether {@link #getContentIfNotHandled()} has already been called. */
    public boolean isHandled() {
        return handled;
    }
}
