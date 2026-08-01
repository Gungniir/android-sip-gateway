package org.onetwoone.gateway.call;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.onetwoone.gateway.GatewayInCallService;

import java.util.ArrayDeque;

/**
 * Replays DTMF digits pressed on the SIP leg onto the GSM leg, so a SIP caller can walk
 * through a voice menu on the far end of the GSM call.
 *
 * Telecom's playDtmfTone()/stopDtmfTone() is a level rather than a one-shot, so digits are
 * queued and paced here: each one is held for {@link #TONE_MS}, then a gap follows so the
 * far end sees two presses instead of one long tone.
 *
 * All queue state is touched on a single handler thread (the main looper in production),
 * so {@link #enqueue} and {@link #clear} are safe to call from PJSIP callback threads.
 */
public class GsmDtmfSender {
    private static final String TAG = "GsmDtmf";

    /** How long each digit is held. Most IVRs want >= 100ms; 200ms is comfortably safe. */
    private static final long TONE_MS = 200;

    /** Silence between digits. */
    private static final long GAP_MS = 100;

    /** Backstop against a misbehaving PBX flooding us with digits. */
    private static final int MAX_QUEUED = 32;

    /** Where digits go. Split out so the pacing logic is testable without Telecom. */
    public interface Target {
        /** @return true if the tone started; false if there is no usable GSM call */
        boolean playTone(char digit);

        void stopTone();
    }

    private static final Target TELECOM_TARGET = new Target() {
        @Override
        public boolean playTone(char digit) {
            GatewayInCallService inCall = GatewayInCallService.getInstance();
            return inCall != null && inCall.playDtmfTone(digit);
        }

        @Override
        public void stopTone() {
            GatewayInCallService inCall = GatewayInCallService.getInstance();
            if (inCall != null) {
                inCall.stopDtmfTone();
            }
        }
    };

    private final Handler handler;
    private final Target target;
    private final ArrayDeque<Character> queue = new ArrayDeque<>();
    private boolean playing;

    public GsmDtmfSender() {
        this(new Handler(Looper.getMainLooper()), TELECOM_TARGET);
    }

    // Visible for testing.
    GsmDtmfSender(Handler handler, Target target) {
        this.handler = handler;
        this.target = target;
    }

    /**
     * Queue digits for the GSM leg. Unsupported characters are dropped.
     */
    public void enqueue(String digits) {
        if (digits == null || digits.isEmpty()) {
            return;
        }

        handler.post(() -> {
            for (int i = 0; i < digits.length(); i++) {
                char digit = Character.toUpperCase(digits.charAt(i));

                if (!isSupported(digit)) {
                    Log.w(TAG, "Ignoring unsupported DTMF digit: " + digit);
                    continue;
                }

                if (queue.size() >= MAX_QUEUED) {
                    Log.w(TAG, "DTMF queue full, dropping '" + digit + "'");
                    break;
                }

                queue.add(digit);
            }

            if (!playing) {
                playNext();
            }
        });
    }

    /**
     * Drop anything still queued and release a tone that is still on.
     * Call this when the calls are torn down.
     */
    public void clear() {
        handler.post(() -> {
            if (queue.isEmpty() && !playing) {
                return;
            }

            Log.d(TAG, "Clearing DTMF queue (" + queue.size() + " pending)");
            queue.clear();
            handler.removeCallbacksAndMessages(null);

            if (playing) {
                target.stopTone();
                playing = false;
            }
        });
    }

    private void playNext() {
        Character digit = queue.poll();
        if (digit == null) {
            playing = false;
            return;
        }

        if (!target.playTone(digit)) {
            // No usable GSM call - drop the rest rather than replaying stale digits later.
            queue.clear();
            playing = false;
            return;
        }

        playing = true;
        Log.d(TAG, "DTMF -> GSM: " + digit);

        handler.postDelayed(() -> {
            target.stopTone();
            handler.postDelayed(this::playNext, GAP_MS);
        }, TONE_MS);
    }

    private static boolean isSupported(char digit) {
        return (digit >= '0' && digit <= '9')
                || (digit >= 'A' && digit <= 'D')
                || digit == '*'
                || digit == '#';
    }
}
