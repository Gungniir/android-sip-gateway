package org.pjsip.pjsua2;

/**
 * Hand-written accessor that exposes the raw C++ address behind a pjsua2 {@link ByteVector}.
 *
 * <p><b>This file is NOT SWIG-generated.</b> Every other class in this package is, and
 * {@code CLAUDE.md} forbids editing them. Adding a new file here is permitted
 * (PHASE-2-PLAN §3 rule 2) and is the only way to do what this class does, because
 * {@code ByteVector.getCPtr} and {@code MediaFrame.getCPtr} are {@code protected static} —
 * reachable from inside {@code org.pjsip.pjsua2} and nowhere else.
 *
 * <h3>Why it exists</h3>
 * {@code GsmAudioPort}'s two pjmedia real-time callbacks used to move audio through
 * {@code ByteVector.add(Short)} / {@code get(int)}, one element per byte. At 8&nbsp;kHz /
 * 20&nbsp;ms that is a 320-iteration loop, 50&nbsp;times a second, in each direction:
 * ~32&nbsp;500 JNI transitions per second, roughly 4&nbsp;000 {@code Short} boxes per second
 * per direction ({@code Short.valueOf} caches only −128…127, and the values are 0…255), and
 * ~10 {@code std::vector} reallocations per frame from repeated {@code push_back}. All of it
 * on the thread where a GC pause is an audible dropout (AUDIT H2).
 *
 * <h3>The ABI dependency this creates — READ BEFORE CHANGING pjsua2</h3>
 * The addresses handed out here are consumed by {@code gsm_audio_jni.c}, which reads the
 * first two pointer-sized words of a {@code std::vector<unsigned char>} as
 * {@code __begin_} / {@code __end_}. That welds this code to:
 * <ol>
 *   <li>{@code pj::ByteVector} being {@code std::vector<unsigned char>}
 *       (pjsua2 {@code types.hpp}), and</li>
 *   <li>libc++'s {@code std::vector} layout — three pointers, data first.</li>
 * </ol>
 * Both are verified against the vendored {@code libpjsua2.so} itself, not assumed:
 * {@code Java_..._ByteVector_1doSize} disassembles to
 * {@code ldp x8, x9, [x2]; sub x8, x9, x8} — literally "load the words at +0 and +8,
 * subtract" — and the library links {@code libc++_shared.so}, the same STL this project's
 * CMake build uses ({@code ANDROID_STL=c++_shared}).
 *
 * <p>It is <em>also</em> checked at runtime. {@code GsmAudioPort}'s constructor
 * cross-validates both copy directions against pjsua2's own generated per-element
 * accessors and silently falls back to the old loops if they disagree, so a PJSIP rebuild
 * that changes the layout degrades in performance rather than corrupting the heap. If you
 * rebuild PJSIP, watch for {@code "bulk frame copy unavailable"} in logcat.
 *
 * <h3>What this class deliberately does not do</h3>
 * It never resizes or reallocates a vector, and the native side never writes a vector's
 * control block — only the bytes between {@code __begin_} and {@code __end_}. Sizing stays
 * with pjsua2 ({@link #allocate}, {@code MediaFrame.setBuf}), so no assumption is made about
 * which allocator owns the storage.
 */
public final class PjByteVectorAccess {

    private PjByteVectorAccess() {
    }

    /**
     * A {@code ByteVector} of {@code size} zero bytes, sized in a single native call.
     *
     * <p>Deliberately not {@code new ByteVector()} + {@code add()} in a loop: the whole
     * point is that the vector is pre-sized once and its storage then reused for the life
     * of the port. The returned object owns its native memory ({@code swigCMemOwn=true}),
     * so hold it in a field — letting it become unreachable frees the storage.
     */
    public static ByteVector allocate(int size) {
        return new ByteVector(size, (short) 0);
    }

    /**
     * Address of the {@code std::vector<unsigned char>} backing {@code frame.buf}, or 0 if
     * it cannot be determined.
     *
     * <p>Uses the generated {@code MediaFrame_buf_get} accessor rather than an offset into
     * {@code MediaFrame}, so no assumption is made about that struct's layout — and,
     * unlike {@link MediaFrame#getBuf()}, it allocates nothing. {@code getBuf()} returns
     * {@code new ByteVector(cPtr, false)}: a fresh <em>finalizable</em> wrapper, created
     * twice per frame on the RT thread, i.e. 100 objects/s onto the finalizer queue.
     */
    public static long bufAddress(MediaFrame frame) {
        if (frame == null) {
            return 0;
        }
        long framePtr = MediaFrame.getCPtr(frame);
        if (framePtr == 0) {
            return 0;
        }
        return pjsua2JNI.MediaFrame_buf_get(framePtr, frame);
    }

    /** Address of a {@code ByteVector} the caller owns, or 0. */
    public static long address(ByteVector vector) {
        return ByteVector.getCPtr(vector);
    }
}
