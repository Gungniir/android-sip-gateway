package org.onetwoone.gateway.sip;

import org.pjsip.pjsua2.AccountInfo;
import org.pjsip.pjsua2.AudioMediaVector2;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallOpParam;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.ConfPortInfo;
import org.pjsip.pjsua2.IntVector;
import org.pjsip.pjsua2.SipHeader;
import org.pjsip.pjsua2.SipHeaderVector;
import org.pjsip.pjsua2.StreamInfo;
import org.pjsip.pjsua2.StreamStat;

/**
 * The one place that says which pjsua2 objects the Java side owns, and the only way this app
 * releases them. AUDIT H7 / GW-22.
 *
 * <h3>The rule</h3>
 * Every SWIG proxy in {@code org.pjsip.pjsua2} carries a {@code swigCMemOwn} flag, set by the
 * factory that produced it. <b>Only {@code true} means the Java side owns native memory and
 * must call {@code delete()}.</b> Read the generated factory to find out; do not guess from the
 * type name. Two objects of the same class can differ - {@code AudioMedia} is owned when you
 * {@code new} one, not owned when {@code typecastFromMedia} hands you a view of pjsua's.
 *
 * <p><b>Owned because the Java side made it</b> - anything you {@code new} in this package is
 * {@code (ptr, true)}. On the call path that is {@link CallOpParam}, {@link SipHeaderVector}
 * and {@link SipHeader}, all of which pjsua2 marshals synchronously and never retains, so they
 * are released as soon as the {@code answer}/{@code hangup}/{@code makeCall} that consumed them
 * has returned.
 *
 * <p><b>Owned because pjsua2 handed it back by value - delete after use</b> (an overload exists
 * here for each):
 * {@link CallInfo} ({@code Call.getInfo()}), {@link AccountInfo} ({@code Account.getInfo()}),
 * {@link AudioMediaVector2} ({@code Endpoint.mediaEnumPorts2()}), {@link ConfPortInfo}
 * ({@code AudioMedia.getPortInfo()} / {@code getPortInfoFromId()}), {@link StreamInfo} and
 * {@link StreamStat} ({@code Call.getStreamInfo()} / {@code getStreamStat()}),
 * {@link CodecInfoVector2} ({@code codecEnum2()} / {@code videoCodecEnum2()}),
 * {@link IntVector} <em>from {@code Endpoint.transportEnum()}</em>.
 *
 * <p><b>Not owned - deleting is a no-op, so no overload is offered</b>:
 * {@code CallMediaInfoVector} ({@code CallInfo.getMedia()}), {@code CallMediaInfo},
 * {@code Media} ({@code Call.getMedia(i)}), {@code AudioMedia} from
 * {@code AudioMedia.typecastFromMedia} or {@code AudioMediaVector2.get(i)}, {@code CodecInfo}
 * from {@code CodecInfoVector2.get(i)}, {@code IntVector} from
 * {@code ConfPortInfo.getListeners()}, {@code SipTxOption} from {@code CallOpParam.getTxOption()},
 * {@code RtcpStat} / {@code RtcpStreamStat}, {@code ByteVector} from
 * {@code MediaFrame.getBuf()}. The absence of an overload is the signal: if the compiler
 * refuses your argument, the object is pjsua's, not ours.
 *
 * <h3>Children die with their parent</h3>
 * The "not owned" list above is mostly <em>pointers into</em> an owned parent -
 * {@code CallInfo.getMedia()} returns {@code &info->media}. Deleting the parent dangles every
 * such view, so the {@code finally} that deletes an owned object must enclose <b>all</b> uses
 * of what was derived from it. That is why the try/finally blocks in this codebase wrap whole
 * loops rather than single statements.
 *
 * <h3>What this is not</h3>
 * These are <em>value</em> objects. {@code Call} and {@code Account} are SWIG <em>directors</em>
 * whose native half calls back into Java; their deletion is an ordering problem, not a
 * try/finally, and it is handled where each one's lifecycle lives ({@code SipAccountManager}
 * for {@code Account}). {@code PjsipLogWriter}'s singleton is a director held by native code
 * for the life of the process and must never be deleted at all.
 *
 * <h3>Why bother, when every proxy has a finalizer</h3>
 * Every proxy's {@code finalize()} calls {@code delete()}, so skipping these is a
 * finalizer-deferred release, not a permanent leak. What it costs is determinism: native
 * memory the GC heuristic cannot see, and a finalizer queue that grows at roughly
 * 30 objects per completed gateway call.
 */
public final class Pjsua2Lifetime {

    private Pjsua2Lifetime() {
    }

    /**
     * Java-created. {@code Call.answer/hangup/makeCall} take it by const reference and marshal
     * it into pjsua's own structures before returning, so it may be deleted the moment that
     * call has returned - and must not be deleted before.
     */
    public static void delete(CallOpParam o) {
        if (o != null) {
            o.delete();
        }
    }

    /**
     * Java-created. {@code SipTxOption.setHeaders} copy-assigns the underlying
     * {@code std::vector}, so this may be deleted once the setter has returned.
     */
    public static void delete(SipHeaderVector o) {
        if (o != null) {
            o.delete();
        }
    }

    /**
     * Java-created. {@code SipHeaderVector.add} copies the element, so this may be deleted once
     * it has been added.
     */
    public static void delete(SipHeader o) {
        if (o != null) {
            o.delete();
        }
    }

    /** From {@code Call.getInfo()}. Invalidates any {@code CallMediaInfoVector} taken from it. */
    public static void delete(CallInfo o) {
        if (o != null) {
            o.delete();
        }
    }

    /** From {@code Account.getInfo()}. */
    public static void delete(AccountInfo o) {
        if (o != null) {
            o.delete();
        }
    }

    /**
     * From {@code Endpoint.mediaEnumPorts2()}. The {@code AudioMedia} elements it hands out are
     * <em>not</em> owned and must not be deleted, but they do not outlive this vector.
     */
    public static void delete(AudioMediaVector2 o) {
        if (o != null) {
            o.delete();
        }
    }

    /**
     * From {@code AudioMedia.getPortInfo()} or {@code AudioMedia.getPortInfoFromId()}.
     * Invalidates any {@code IntVector} taken from {@code getListeners()}.
     */
    public static void delete(ConfPortInfo o) {
        if (o != null) {
            o.delete();
        }
    }

    /** From {@code Call.getStreamInfo()}. */
    public static void delete(StreamInfo o) {
        if (o != null) {
            o.delete();
        }
    }

    /** From {@code Call.getStreamStat()}. Invalidates the {@code RtcpStat} taken from it. */
    public static void delete(StreamStat o) {
        if (o != null) {
            o.delete();
        }
    }

    /** From {@code Endpoint.codecEnum2()} or {@code videoCodecEnum2()}. */
    public static void delete(CodecInfoVector2 o) {
        if (o != null) {
            o.delete();
        }
    }

    /**
     * From {@code Endpoint.transportEnum()} <b>only</b>. An {@code IntVector} from
     * {@code ConfPortInfo.getListeners()} is a view into its parent and must not be deleted -
     * the two are indistinguishable at this signature, so check the factory before calling.
     */
    public static void delete(IntVector o) {
        if (o != null) {
            o.delete();
        }
    }
}
