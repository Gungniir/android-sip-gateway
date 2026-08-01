package org.onetwoone.gateway.sip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for SipHeaderReader.
 * Plain JUnit - this class deliberately has no PJSIP dependency so it runs on the JVM.
 */
public class SipHeaderReaderTest {

    private static String invite(String extraHeaders) {
        return "INVITE sip:+79991234567@pbx.example.com SIP/2.0\r\n"
                + "Via: SIP/2.0/TLS 10.0.0.1:5061;branch=z9hG4bK1\r\n"
                + "From: \"2001\" <sip:2001@pbx.example.com>;tag=abc\r\n"
                + "To: <sip:+79991234567@pbx.example.com>\r\n"
                + "Call-ID: 12345@pbx.example.com\r\n"
                + "CSeq: 1 INVITE\r\n"
                + extraHeaders
                + "Content-Type: application/sdp\r\n"
                + "\r\n"
                + "v=0\r\n"
                + "o=- 1 1 IN IP4 10.0.0.1\r\n"
                + "m=audio 4000 RTP/AVP 8\r\n";
    }

    @Test
    public void testReadsSimSlot() {
        assertEquals(2, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: 2\r\n")));
    }

    @Test
    public void testHeaderNameIsCaseInsensitive() {
        assertEquals(1, SipHeaderReader.readSimSlot(invite("x-gsm-sim: 1\r\n")));
    }

    @Test
    public void testToleratesMissingSpaceAndPadding() {
        assertEquals(2, SipHeaderReader.readSimSlot(invite("X-GSM-SIM:2\r\n")));
        assertEquals(2, SipHeaderReader.readSimSlot(invite("X-GSM-SIM:   2  \r\n")));
    }

    @Test
    public void testAbsentHeaderMeansNoPreference() {
        // 0 tells CallManager to fall back to routing by caller extension.
        assertEquals(0, SipHeaderReader.readSimSlot(invite("")));
    }

    @Test
    public void testOutOfRangeAndGarbageSlotsAreIgnored() {
        assertEquals(0, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: 3\r\n")));
        assertEquals(0, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: 0\r\n")));
        assertEquals(0, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: -1\r\n")));
        assertEquals(0, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: two\r\n")));
        assertEquals(0, SipHeaderReader.readSimSlot(invite("X-GSM-SIM: \r\n")));
    }

    @Test
    public void testDoesNotReadIntoTheSdpBody() {
        // An SDP attribute that happens to look like the header must not be picked up.
        String msg = invite("") + "a=X-GSM-SIM: 2\r\n";
        assertEquals(0, SipHeaderReader.readSimSlot(msg));
    }

    @Test
    public void testHandlesBareLineFeeds() {
        assertEquals(2, SipHeaderReader.readSimSlot(
                "INVITE sip:+79991234567@pbx SIP/2.0\nX-GSM-SIM: 2\n\nv=0\n"));
    }

    @Test
    public void testReadsOtherHeaders() {
        assertEquals("+79991234567",
                SipHeaderReader.read(invite("X-GSM-CallerID: +79991234567\r\n"), "X-GSM-CallerID"));
    }

    @Test
    public void testRequestLineIsNotMistakenForAHeader() {
        // "INVITE sip:..." contains a colon; the part before it must not match anything.
        assertNull(SipHeaderReader.read(invite(""), "INVITE sip"));
    }

    @Test
    public void testNullAndEmptyInputs() {
        assertEquals(0, SipHeaderReader.readSimSlot(null));
        assertNull(SipHeaderReader.read(null, "X-GSM-SIM"));
        assertNull(SipHeaderReader.read(invite(""), null));
        assertNull(SipHeaderReader.read(invite(""), ""));
    }
}
