package org.onetwoone.gateway.diag;

import android.util.Log;

import org.pjsip.pjsua2.LogEntry;
import org.pjsip.pjsua2.LogWriter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Routes PJSIP's native log output into logcat and into a small in-memory ring buffer.
 *
 * Without an explicit writer PJSIP writes to stdout, which is discarded on Android, so
 * the SIP messages (and the SDP we need to inspect for one-way audio) never show up
 * anywhere. With {@code LogConfig.setMsgLogging(1)} and level >= 4, every SIP message
 * passes through {@link #write(LogEntry)}.
 *
 * The singleton MUST stay reachable for the whole process lifetime: it is a SWIG
 * director, and letting it be garbage collected while native PJSIP still holds the
 * pointer crashes the process.
 */
public class PjsipLogWriter extends LogWriter {
    private static final String TAG = "PjsipLog";

    /** Strong reference - see class docs. Never null this out. */
    private static PjsipLogWriter instance;

    private static final int MAX_LINES = 500;

    private final Deque<String> lines = new ArrayDeque<>(MAX_LINES);

    private PjsipLogWriter() {
        super();
    }

    public static synchronized PjsipLogWriter get() {
        if (instance == null) {
            instance = new PjsipLogWriter();
        }
        return instance;
    }

    @Override
    public void write(LogEntry entry) {
        String msg;
        int level;
        try {
            msg = entry.getMsg();
            level = entry.getLevel();
        } catch (Exception e) {
            return;
        }
        if (msg == null) {
            return;
        }

        // PJSIP messages already carry their own trailing newline.
        String trimmed = msg.endsWith("\n") ? msg.substring(0, msg.length() - 1) : msg;

        Log.println(toAndroidPriority(level), TAG, trimmed);

        synchronized (lines) {
            if (lines.size() >= MAX_LINES) {
                lines.removeFirst();
            }
            lines.addLast(trimmed);
        }
    }

    /** Last {@code n} log lines, oldest first, newline-joined. */
    public String recent(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            int skip = Math.max(0, lines.size() - n);
            int i = 0;
            for (String line : lines) {
                if (i++ < skip) continue;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Buffered lines containing {@code substring}, oldest first. Handy for pulling the
     * SDP out of the buffer ("m=audio", "a=sendrecv", "a=crypto").
     */
    public String recentMatching(String substring) {
        StringBuilder sb = new StringBuilder();
        synchronized (lines) {
            for (String line : lines) {
                if (line.contains(substring)) {
                    sb.append(line).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public void clear() {
        synchronized (lines) {
            lines.clear();
        }
    }

    private static int toAndroidPriority(int pjsipLevel) {
        switch (pjsipLevel) {
            case 0:
            case 1:
                return Log.ERROR;
            case 2:
                return Log.WARN;
            case 3:
                return Log.INFO;
            default:
                return Log.DEBUG;
        }
    }
}
