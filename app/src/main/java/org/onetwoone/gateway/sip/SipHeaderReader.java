package org.onetwoone.gateway.sip;

/**
 * Reads single-value headers out of a raw SIP message.
 *
 * pjsua2 exposes no per-header accessor on the receive path - it only hands us the whole
 * message as text via {@code SipRxData.getWholeMsg()} - so custom headers have to be
 * picked out by hand. Deliberately free of PJSIP and Android dependencies so it runs on
 * the JVM.
 */
public final class SipHeaderReader {

    /** Header the PBX sets to pick the SIM slot for a SIP&rarr;GSM call or SMS. */
    public static final String SIM_HEADER = "X-GSM-SIM";

    private SipHeaderReader() {
    }

    /**
     * Read a header value from the header section of a raw SIP message.
     *
     * @return the trimmed value, or null when the header is absent or has an empty value
     */
    public static String read(String wholeMsg, String headerName) {
        if (wholeMsg == null || headerName == null || headerName.isEmpty()) {
            return null;
        }

        String[] lines = wholeMsg.split("\r\n|\n|\r", -1);

        // Start at 1: line 0 is the request/status line, not a header. It carries a colon of
        // its own ("INVITE sip:...") and would otherwise be matched like one.
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];

            // Headers end at the first empty line - never read into the SDP body.
            if (line.isEmpty()) {
                return null;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (!line.substring(0, colon).trim().equalsIgnoreCase(headerName)) {
                continue;
            }

            String value = line.substring(colon + 1).trim();
            return value.isEmpty() ? null : value;
        }

        return null;
    }

    /**
     * Read the SIM slot the PBX asked for.
     *
     * @return 1 or 2, or 0 when the header is absent or does not name a valid slot - the
     *         caller then falls back to deriving the slot from the caller extension
     */
    public static int readSimSlot(String wholeMsg) {
        String value = read(wholeMsg, SIM_HEADER);
        if (value == null) {
            return 0;
        }

        try {
            int slot = Integer.parseInt(value);
            return (slot == 1 || slot == 2) ? slot : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
