package org.onetwoone.gateway.diag;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for SipUriBuilder.
 * Plain JUnit - this class deliberately has no PJSIP dependency so it runs on the JVM.
 */
public class SipUriBuilderTest {

    @Test
    public void testPlainUri() {
        assertEquals("sip:101@pbx.example.com",
                SipUriBuilder.build("101", "pbx.example.com", false));
    }

    @Test
    public void testTlsUri() {
        assertEquals("sip:101@pbx.example.com;transport=tls",
                SipUriBuilder.build("101", "pbx.example.com", true));
    }

    @Test
    public void testFeatureCodeDestination() {
        // FreePBX feature codes such as the *43 echo test must survive untouched.
        assertEquals("sip:*43@pbx.example.com",
                SipUriBuilder.build("*43", "pbx.example.com", false));
    }

    @Test
    public void testPhoneNumberDestination() {
        assertEquals("sip:+79810293335@pbx.example.com",
                SipUriBuilder.build("+79810293335", "pbx.example.com", false));
    }

    @Test
    public void testTrimsWhitespace() {
        assertEquals("sip:101@pbx.example.com",
                SipUriBuilder.build("  101 ", " pbx.example.com  ", false));
    }

    @Test
    public void testNullsBecomeEmpty() {
        assertEquals("sip:@", SipUriBuilder.build(null, null, false));
    }
}
