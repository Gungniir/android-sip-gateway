package org.onetwoone.gateway.diag;

import android.util.Log;

import org.pjsip.pjsua2.AudioMedia;
import org.pjsip.pjsua2.AudioMediaVector2;
import org.pjsip.pjsua2.Call;
import org.pjsip.pjsua2.CallInfo;
import org.pjsip.pjsua2.CallMediaInfo;
import org.pjsip.pjsua2.CallMediaInfoVector;
import org.pjsip.pjsua2.CodecInfo;
import org.pjsip.pjsua2.CodecInfoVector2;
import org.pjsip.pjsua2.ConfPortInfo;
import org.pjsip.pjsua2.Endpoint;
import org.pjsip.pjsua2.IntVector;
import org.pjsip.pjsua2.RtcpStat;
import org.pjsip.pjsua2.StreamInfo;
import org.pjsip.pjsua2.StreamStat;
import org.pjsip.pjsua2.pjmedia_dir;
import org.pjsip.pjsua2.pjmedia_type;
import org.pjsip.pjsua2.pjsua_call_media_status;

/**
 * Read-only snapshot of everything that decides whether audio actually flows on a SIP
 * call: the negotiated stream direction and codec, RTP counters, and — the part nothing
 * else logs — the conference-bridge wiring.
 *
 * The decisive question this answers: does the local source port's listener list contain
 * the call media's conference slot? pjmedia's conference bridge skips get_frame (and so
 * never calls {@code onFrameRequested}) for any port whose listener count is zero, so a
 * missing listener fully explains a silent transmit leg even when startTransmit() threw
 * nothing.
 *
 * Every method swallows its own exceptions - diagnostics must never break a call.
 */
public final class SipDiagnostics {
    private static final String TAG = "SipDiag";

    private SipDiagnostics() {
    }

    /**
     * Dump call media, stream and conference-bridge state.
     *
     * @param call        the active call
     * @param localSource the media we transmit into the call (GsmAudioPort, tone
     *                    generator, ...), or null if not applicable
     * @param label       short context tag, e.g. "startBridge"
     */
    public static String dump(Call call, AudioMedia localSource, String label) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SIP diag [").append(label).append("] ===\n");

        int localPortId = -1;
        if (localSource != null) {
            try {
                localPortId = localSource.getPortId();
                sb.append("local source: portId=").append(localPortId)
                        .append(" listeners=").append(listenersOf(localSource)).append('\n');
            } catch (Exception e) {
                sb.append("local source: <error ").append(e.getMessage()).append(">\n");
            }
        }

        try {
            CallInfo info = call.getInfo();
            sb.append("call: state=").append(info.getStateText())
                    .append(" remote=").append(info.getRemoteUri()).append('\n');

            CallMediaInfoVector mediaVec = info.getMedia();
            sb.append("media count=").append(mediaVec.size()).append('\n');

            for (int i = 0; i < mediaVec.size(); i++) {
                CallMediaInfo mi = mediaVec.get(i);
                boolean isAudio = mi.getType() == pjmedia_type.PJMEDIA_TYPE_AUDIO;

                sb.append("  [").append(i).append("] type=").append(mediaTypeName(mi.getType()))
                        .append(" status=").append(mediaStatusName(mi.getStatus()))
                        .append(" dir=").append(dirName(mi.getDir()))
                        .append(" confSlot=").append(mi.getAudioConfSlot()).append('\n');

                if (!isAudio) {
                    continue;
                }

                appendConfWiring(sb, mi.getAudioConfSlot(), localPortId);
                appendStreamInfo(sb, call, i);
                appendStreamStat(sb, call, i);
            }
        } catch (Exception e) {
            sb.append("call info error: ").append(e.getMessage()).append('\n');
        }

        try {
            Endpoint ep = Endpoint.instance();
            sb.append("conf bridge: active=").append(ep.mediaActivePorts())
                    .append("/").append(ep.mediaMaxPorts()).append(" ports=[");
            AudioMediaVector2 ports = ep.mediaEnumPorts2();
            for (int i = 0; i < ports.size(); i++) {
                AudioMedia m = ports.get(i);
                if (i > 0) sb.append(", ");
                try {
                    ConfPortInfo pi = m.getPortInfo();
                    sb.append(pi.getPortId()).append(':').append(pi.getName());
                } catch (Exception e) {
                    sb.append('?');
                }
            }
            sb.append("]\n");
        } catch (Exception e) {
            sb.append("conf bridge error: ").append(e.getMessage()).append('\n');
        }

        return sb.toString();
    }

    /**
     * Codec inventory of this PJSIP build with negotiation priorities. Worth logging
     * once at startup: it says whether the codecs the PBX allows are even compiled in.
     */
    public static String dumpCodecs() {
        StringBuilder sb = new StringBuilder("=== audio codecs (id/priority) ===\n");
        try {
            CodecInfoVector2 codecs = Endpoint.instance().codecEnum2();
            for (int i = 0; i < codecs.size(); i++) {
                CodecInfo ci = codecs.get(i);
                sb.append("  ").append(ci.getCodecId())
                        .append(" prio=").append(ci.getPriority()).append('\n');
            }
        } catch (Exception e) {
            sb.append("  error: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Is {@code source} still connected to conference port {@code sinkPortId}?
     *
     * Conference links do not survive PJSIP re-creating a call's media stream (which it
     * does on any re-INVITE/UPDATE - including the codec-locking UPDATE it sends itself
     * right after the 200 OK). The ports live on with the same slot numbers, so slot
     * comparison cannot detect it; only the listener list can.
     */
    public static boolean isTransmitting(AudioMedia source, int sinkPortId) {
        if (source == null || sinkPortId < 0) {
            return false;
        }
        try {
            return contains(source.getPortInfo().getListeners(), sinkPortId);
        } catch (Exception e) {
            return false;
        }
    }

    /** Dump to logcat and return the same text. */
    public static String dumpAndLog(Call call, AudioMedia localSource, String label) {
        String text = dump(call, localSource, label);
        for (String line : text.split("\n")) {
            Log.i(TAG, line);
        }
        return text;
    }

    // ========== helpers ==========

    private static void appendConfWiring(StringBuilder sb, int callSlot, int localPortId) {
        if (callSlot < 0) {
            sb.append("      conf: call media has no conference slot\n");
            return;
        }
        try {
            ConfPortInfo callPort = AudioMedia.getPortInfoFromId(callSlot);
            String callListeners = listenersOf(callPort);
            sb.append("      conf: callPort=").append(callSlot)
                    .append(" name=").append(callPort.getName())
                    .append(" listeners=").append(callListeners).append('\n');

            if (localPortId >= 0) {
                boolean localToCall = contains(AudioMedia.getPortInfoFromId(localPortId).getListeners(), callSlot);
                boolean callToLocal = contains(callPort.getListeners(), localPortId);
                sb.append("      WIRED local->call(TX to SIP)=").append(localToCall)
                        .append("  call->local(RX from SIP)=").append(callToLocal).append('\n');
                if (!localToCall) {
                    sb.append("      !! local source has no listener on the call port -"
                            + " conference bridge will never call onFrameRequested\n");
                }
            }
        } catch (Exception e) {
            sb.append("      conf error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void appendStreamInfo(StringBuilder sb, Call call, int idx) {
        try {
            StreamInfo si = call.getStreamInfo(idx);
            sb.append("      stream: codec=").append(si.getCodecName())
                    .append('/').append(si.getCodecClockRate())
                    .append(" dir=").append(dirName(si.getDir()))
                    .append(" txPt=").append(si.getTxPt())
                    .append(" rxPt=").append(si.getRxPt())
                    .append(" remoteRtp=").append(si.getRemoteRtpAddress()).append('\n');
            if (si.getDir() == pjmedia_dir.PJMEDIA_DIR_DECODING) {
                sb.append("      !! stream is receive-only - SDP negotiated no send direction\n");
            }
        } catch (Exception e) {
            sb.append("      stream info error: ").append(e.getMessage()).append('\n');
        }
    }

    private static void appendStreamStat(StringBuilder sb, Call call, int idx) {
        try {
            StreamStat ss = call.getStreamStat(idx);
            RtcpStat rtcp = ss.getRtcp();
            sb.append("      rtp tx: pkt=").append(rtcp.getTxStat().getPkt())
                    .append(" bytes=").append(rtcp.getTxStat().getBytes())
                    .append(" | rx: pkt=").append(rtcp.getRxStat().getPkt())
                    .append(" bytes=").append(rtcp.getRxStat().getBytes())
                    .append(" loss=").append(rtcp.getRxStat().getLoss())
                    .append(" discard=").append(rtcp.getRxStat().getDiscard()).append('\n');
            if (rtcp.getTxStat().getPkt() == 0) {
                sb.append("      !! zero RTP packets transmitted\n");
            }
        } catch (Exception e) {
            sb.append("      stream stat error: ").append(e.getMessage()).append('\n');
        }
    }

    private static String listenersOf(AudioMedia media) {
        try {
            return listenersOf(media.getPortInfo());
        } catch (Exception e) {
            return "<error>";
        }
    }

    private static String listenersOf(ConfPortInfo info) {
        try {
            IntVector listeners = info.getListeners();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < listeners.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(listeners.get(i));
            }
            return sb.append(']').toString();
        } catch (Exception e) {
            return "<error>";
        }
    }

    private static boolean contains(IntVector vec, int value) {
        for (int i = 0; i < vec.size(); i++) {
            if (vec.get(i) == value) {
                return true;
            }
        }
        return false;
    }

    private static String dirName(int dir) {
        switch (dir) {
            case pjmedia_dir.PJMEDIA_DIR_NONE:
                return "none";
            case pjmedia_dir.PJMEDIA_DIR_ENCODING:
                return "sendonly";
            case pjmedia_dir.PJMEDIA_DIR_DECODING:
                return "recvonly";
            case pjmedia_dir.PJMEDIA_DIR_ENCODING_DECODING:
                return "sendrecv";
            default:
                return "dir" + dir;
        }
    }

    private static String mediaTypeName(int type) {
        if (type == pjmedia_type.PJMEDIA_TYPE_AUDIO) return "audio";
        if (type == pjmedia_type.PJMEDIA_TYPE_VIDEO) return "video";
        return "type" + type;
    }

    private static String mediaStatusName(int status) {
        if (status == pjsua_call_media_status.PJSUA_CALL_MEDIA_NONE) return "none";
        if (status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) return "ACTIVE";
        if (status == pjsua_call_media_status.PJSUA_CALL_MEDIA_LOCAL_HOLD) return "local_hold";
        if (status == pjsua_call_media_status.PJSUA_CALL_MEDIA_REMOTE_HOLD) return "remote_hold";
        if (status == pjsua_call_media_status.PJSUA_CALL_MEDIA_ERROR) return "error";
        return "status" + status;
    }
}
