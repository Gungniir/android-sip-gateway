package org.onetwoone.gateway.sip;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.onetwoone.gateway.GatewayAccount;
import org.onetwoone.gateway.config.GatewayConfig;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

/**
 * GW-14 - the account-reference guard, AUDIT F4.
 *
 * <p>What this can and cannot cover. {@code createAccount()} is pjsua2 all the way down
 * ({@code new AccountCallbackWrapper(...)}, {@code account.create(accConfig)}) and there is no
 * PJSIP native library on the JVM, so the account is planted by reflection and stood in for by
 * a Mockito mock - which works precisely because the guard under test is a reference identity
 * check and never calls into the account. The half of F4 that actually closes it - that
 * {@code sendSipMessage} now runs on the control thread, so no other thread can delete the
 * account between the read and {@code buddy.create(...)} - is not reachable from here at all:
 * it lives in {@code PjsipSipService}, behind pjsua2. That half is stated in the code and
 * verified on hardware, not here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SipAccountManagerTest {

    private SipAccountManager manager;

    @Before
    public void setUp() throws Exception {
        Application app = RuntimeEnvironment.getApplication();

        Field instance = GatewayConfig.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
        GatewayConfig.init(app);

        GatewayConfig config = GatewayConfig.getInstance();
        manager = new SipAccountManager(config, new SipEndpointManager(config));
    }

    /** Stand in for {@code createAccount()}, which needs a PJSIP endpoint we do not have. */
    private void plantAccount(GatewayAccount account) throws Exception {
        Field field = SipAccountManager.class.getDeclaredField("account");
        field.setAccessible(true);
        field.set(manager, account);
    }

    private void plantRegistered(boolean registered) throws Exception {
        Field field = SipAccountManager.class.getDeclaredField("registered");
        field.setAccessible(true);
        field.set(manager, registered);
    }

    @Test
    public void noAccountIsEverCurrent() {
        assertNull(manager.getAccount());
        assertFalse(manager.isCurrentAccount(null));
        assertFalse("nothing is current while there is no account",
                manager.isCurrentAccount(mock(GatewayAccount.class)));
    }

    @Test
    public void theLiveAccountIsCurrentAndNothingElseIs() throws Exception {
        GatewayAccount live = mock(GatewayAccount.class);
        plantAccount(live);

        assertSame(live, manager.getAccount());
        assertTrue(manager.isCurrentAccount(live));
        assertFalse("a different account object must not pass the guard",
                manager.isCurrentAccount(mock(GatewayAccount.class)));
        assertFalse("null must not pass the guard", manager.isCurrentAccount(null));
    }

    /**
     * The F4 case. A caller that captured the reference before a reload deleted it must be
     * told the reference is stale, rather than handing pjsua2 a freed native peer - which is
     * an abort, not an exception, so there is nothing to catch afterwards.
     */
    @Test
    public void aDeletedAccountIsNoLongerCurrent() throws Exception {
        GatewayAccount doomed = mock(GatewayAccount.class);
        plantAccount(doomed);
        plantRegistered(true);

        GatewayAccount captured = manager.getAccount();
        assertTrue("precondition: the caller captured the live account",
                manager.isCurrentAccount(captured));

        manager.deleteAccount();

        assertFalse("the captured reference must not survive deleteAccount() - F4",
                manager.isCurrentAccount(captured));
        assertNull(manager.getAccount());
        assertFalse("deleting the account also drops the registration",
                manager.isRegistered());
    }

    /**
     * {@code deleteAccount()} must be finished when it returns - no queued work, no deferred
     * native teardown - because {@code doReloadConfig} creates the replacement account on the
     * very next statement and no longer sleeps 500 ms in between.
     */
    @Test
    public void deleteAccountUnregistersAndDeletesBeforeItReturns() throws Exception {
        GatewayAccount doomed = mock(GatewayAccount.class);
        plantAccount(doomed);

        manager.deleteAccount();

        verify(doomed).setRegistration(false);
        verify(doomed).delete();
    }

    /** Called on a null account during teardown paths that run twice; must not throw. */
    @Test
    public void deleteAccountIsANoOpWhenThereIsNoAccount() {
        manager.deleteAccount();
        assertNull(manager.getAccount());
    }

    /**
     * A replacement account created after a reload is current; the one it replaced is not.
     * This is the state {@code sendSipMessage}'s re-check exists to detect.
     */
    @Test
    public void replacingTheAccountStalesTheOldReference() throws Exception {
        GatewayAccount old = mock(GatewayAccount.class);
        plantAccount(old);
        GatewayAccount captured = manager.getAccount();

        manager.deleteAccount();
        GatewayAccount replacement = mock(GatewayAccount.class);
        plantAccount(replacement);

        assertTrue(manager.isCurrentAccount(replacement));
        assertFalse("the pre-reload reference must still be refused",
                manager.isCurrentAccount(captured));
    }
}
