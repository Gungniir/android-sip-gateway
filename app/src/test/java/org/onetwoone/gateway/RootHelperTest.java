package org.onetwoone.gateway;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The {@link RootHelper} result contract — AUDIT H1, and the {@code execRoot} half of H13
 * and B1e (GW-20).
 *
 * <p>The bug all three share: {@code execRoot} logged a non-zero exit and then returned
 * {@code output.toString().trim()} anyway, so a command that did not exist (exit 127) came
 * back as {@code ""} — never {@code null} — and every caller testing
 * {@code execRoot(...) != null} read that as success. The whole SMS inbox was re-forwarded
 * on every restart because of it, and every Qualcomm mixer "original" was fabricated.
 *
 * <p>These exercise {@link RootHelper#exec(String[], int)} against {@code /bin/sh} rather
 * than {@code su}, so they run on the JVM with no device and no root: what is under test is
 * the capture/timeout/exit-code machinery, which is identical on both paths
 * ({@code run(cmd)} is exactly {@code exec({"su","-c",cmd})}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class RootHelperTest {

    private static final String SH = "/bin/sh";
    private static final int TIMEOUT = 10_000;

    @Before
    public void setUp() {
        ShadowLog.clear();
        RootHelper.resetSelinuxStateForTest();
    }

    private static RootHelper.RootResult sh(String script, int timeoutMs) {
        return RootHelper.exec(new String[]{SH, "-c", script}, timeoutMs);
    }

    // ------------------------------------------------------------------
    // Exit code vs output - the H13 / B1e core
    // ------------------------------------------------------------------

    @Test
    public void successCarriesStdoutAndExitZero() {
        RootHelper.RootResult r = sh("echo hello", TIMEOUT);

        assertTrue("exit 0 must be success", r.success());
        assertEquals(0, r.exitCode());
        assertEquals("hello", r.stdout());
        assertEquals("", r.stderr());
        assertEquals("hello", r.outputOrNull());
    }

    /**
     * The exact shape of H13: {@code sqlite3} is not installed, the shell exits 127 and
     * prints to stderr. The old contract returned "" and the caller logged
     * "Marked SMS as read".
     */
    @Test
    public void missingBinaryIsNeverReportedAsSuccess() {
        RootHelper.RootResult r = sh("no_such_binary_zzz update --uri content://sms/1", TIMEOUT);

        assertFalse("exit 127 must not be success", r.success());
        assertEquals(127, r.exitCode());
        assertEquals("", r.stdout());
        assertTrue("stderr must be captured, not dropped: " + r.stderr(),
                r.stderr().contains("no_such_binary_zzz"));
        assertNull("a failed command must not hand back an empty-string 'result'",
                r.outputOrNull());
    }

    /** A command can fail loudly *and* have printed something first. Still not success. */
    @Test
    public void nonZeroExitWithOutputIsStillFailure() {
        RootHelper.RootResult r = sh("echo partial; echo boom >&2; exit 3", TIMEOUT);

        assertFalse(r.success());
        assertEquals(3, r.exitCode());
        assertEquals("partial", r.stdout());
        assertEquals("boom", r.stderr());
        assertNull(r.outputOrNull());
    }

    /** B1e's read path: a control read that fails must not look like a value. */
    @Test
    public void failedReadIsDistinguishableFromAnEmptyReading() {
        RootHelper.RootResult failed = sh("exit 1", TIMEOUT);
        RootHelper.RootResult empty = sh("true", TIMEOUT);

        assertEquals("", failed.stdout());
        assertEquals("", empty.stdout());
        // Identical stdout, opposite verdicts. That distinction is the whole point.
        assertFalse(failed.success());
        assertTrue(empty.success());
    }

    @Test
    public void multiLineOutputIsPreservedAndTrimmed() {
        RootHelper.RootResult r = sh("printf 'a\\nb\\nc\\n'", TIMEOUT);

        assertTrue(r.success());
        assertEquals("a\nb\nc", r.stdout());
    }

    @Test
    public void executableThatCannotBeStartedIsAFailure() {
        RootHelper.RootResult r =
                RootHelper.exec(new String[]{"/nonexistent/definitely/not/here"}, TIMEOUT);

        assertFalse(r.success());
        assertEquals(RootHelper.EXIT_NOT_STARTED, r.exitCode());
        assertNull(r.outputOrNull());
    }

    // ------------------------------------------------------------------
    // Timeout - never partial output, and no 2 s tax on top
    // ------------------------------------------------------------------

    @Test
    public void timeoutYieldsAFailedResultWithNoPartialOutput() {
        // Prints, then hangs. The old code would join the readers after the process
        // timeout and could return the half-written builder.
        RootHelper.RootResult r = sh("echo started; sleep 30", 400);

        assertFalse(r.success());
        assertEquals(RootHelper.EXIT_TIMED_OUT, r.exitCode());
        assertEquals("a timed-out command must not return the output it managed to print",
                "", r.stdout());
        assertNull(r.outputOrNull());
    }

    /**
     * The ordering fix: the old implementation ran {@code join(1000)} on both readers
     * <em>before</em> testing whether the process had finished, so a hung command cost
     * {@code timeoutMs + 2000 ms}. Budget generously — this is asserting an ordering, not
     * a benchmark.
     */
    @Test
    public void timeoutDoesNotPayForTheReaderJoinsOnTopOfIt() {
        long start = System.nanoTime();
        RootHelper.RootResult r = sh("sleep 30", 300);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(RootHelper.EXIT_TIMED_OUT, r.exitCode());
        assertTrue("timeout path took " + elapsedMs + " ms; the old join-first ordering "
                        + "would have added ~2000 ms", elapsedMs < 1800);
    }

    @Test
    public void timedOutProcessIsKilled() throws Exception {
        // A marker file the child would create if it were still alive after the timeout.
        java.io.File marker = java.io.File.createTempFile("roothelper", ".marker");
        assertTrue(marker.delete());

        RootHelper.RootResult r =
                sh("sleep 1; touch '" + marker.getAbsolutePath() + "'", 200);
        assertEquals(RootHelper.EXIT_TIMED_OUT, r.exitCode());

        Thread.sleep(1500);
        assertFalse("the process outlived its timeout - destroyForcibly did not run",
                marker.exists());
    }

    // ------------------------------------------------------------------
    // Pipe drain - execRootCode used to deadlock here
    // ------------------------------------------------------------------

    @Test
    public void largeOutputDoesNotDeadlockOnAFullPipe() {
        // Well past a 64 KiB pipe buffer on both streams at once.
        RootHelper.RootResult r = sh(
                "i=0; while [ $i -lt 4000 ]; do "
                        + "echo 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'; "
                        + "echo 'yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy' >&2; "
                        + "i=$((i+1)); done",
                TIMEOUT);

        assertTrue("exit " + r.exitCode() + " - a full pipe deadlocked the drain", r.success());
        assertEquals(4000 * 41 - 1, r.stdout().length());
        assertEquals(4000 * 41 - 1, r.stderr().length());
    }

    // ------------------------------------------------------------------
    // Concurrency - the StringBuilder handoff (H1)
    // ------------------------------------------------------------------

    /**
     * H1's actual crash: the caller read a {@code StringBuilder} a reader thread was still
     * appending to. Hammer the capture from several threads at once and require every
     * result to be exactly its own command's output — no tearing, no cross-talk, no
     * {@code StringIndexOutOfBoundsException}.
     */
    @Test
    public void concurrentExecutionsDoNotTearEachOthersOutput() throws Exception {
        final int threads = 8;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger ok = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int id = i;
            new Thread(() -> {
                try {
                    go.await();
                    // ~40 KiB of output per command, so the readers really are still
                    // running when the process exits.
                    RootHelper.RootResult r = sh(
                            "i=0; while [ $i -lt 1000 ]; do echo 'line-" + id
                                    + "-padding-padding'; i=$((i+1)); done", TIMEOUT);
                    if (!r.success()) {
                        failures.add("thread " + id + " exit " + r.exitCode());
                        return;
                    }
                    String[] lines = r.stdout().split("\n");
                    if (lines.length != 1000) {
                        failures.add("thread " + id + " got " + lines.length + " lines");
                        return;
                    }
                    for (String line : lines) {
                        if (!line.equals("line-" + id + "-padding-padding")) {
                            failures.add("thread " + id + " saw torn line '" + line + "'");
                            return;
                        }
                    }
                    ok.incrementAndGet();
                } catch (Throwable t) {
                    failures.add("thread " + id + " threw " + t);
                } finally {
                    done.countDown();
                }
            }, "exec-" + i).start();
        }

        go.countDown();
        assertTrue("threads did not finish", done.await(60, TimeUnit.SECONDS));
        assertEquals("failures: " + failures, 0, failures.size());
        assertEquals(threads, ok.get());
    }

    // ------------------------------------------------------------------
    // Convenience wrappers keep the contract
    // ------------------------------------------------------------------

    @Test
    public void execRootReturnsNullWhenTheCommandFails() {
        // No `su` on the JVM, so this exercises the could-not-start path end to end.
        assertNull(RootHelper.execRoot("echo hello", 2000));
    }

    @Test
    public void resultRejectsNullOutputs() {
        RootHelper.RootResult r = new RootHelper.RootResult("cmd", 0, null, null);
        assertNotNull(r.stdout());
        assertNotNull(r.stderr());
        assertEquals("", r.stdout());
        assertEquals("", r.stderr());
        assertTrue(r.success());
    }

    @Test
    public void everyFailureCodeIsNegativeSoItCanNeverReadAsSuccess() {
        int[] codes = {
            RootHelper.EXIT_NOT_STARTED,
            RootHelper.EXIT_TIMED_OUT,
            RootHelper.EXIT_NO_OUTPUT,
            RootHelper.EXIT_INTERRUPTED,
        };
        for (int code : codes) {
            assertTrue("EXIT_* sentinel " + code + " must be negative", code < 0);
            assertFalse(new RootHelper.RootResult("c", code, "out", "err").success());
            assertNull(new RootHelper.RootResult("c", code, "out", "err").outputOrNull());
        }
    }
}
