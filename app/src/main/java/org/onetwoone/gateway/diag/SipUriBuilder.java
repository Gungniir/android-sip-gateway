package org.onetwoone.gateway.diag;

/**
 * Builds the SIP request URIs the gateway dials.
 *
 * Kept free of PJSIP types so it can be unit tested on the JVM (libpjsua2.so cannot be
 * loaded there) and so the gateway call path and the diagnostic test call can never
 * drift apart.
 */
public final class SipUriBuilder {

    private SipUriBuilder() {
    }

    /**
     * @param destination extension or number, e.g. "101" or "*43"
     * @param server      SIP server host
     * @param useTls      append ";transport=tls" when true
     * @return "sip:&lt;destination&gt;@&lt;server&gt;[;transport=tls]"
     */
    public static String build(String destination, String server, boolean useTls) {
        String dest = destination == null ? "" : destination.trim();
        String host = server == null ? "" : server.trim();
        return "sip:" + dest + "@" + host + (useTls ? ";transport=tls" : "");
    }
}
