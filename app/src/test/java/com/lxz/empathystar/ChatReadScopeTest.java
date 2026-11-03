package com.lxz.empathystar;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatReadScopeTest {
    @Test public void pauseDoesNotGrantConsentAndHasIndependentKeys() {
        assertFalse(ChatReadScope.enabled(false, false));
        assertFalse(ChatReadScope.enabled(true, true));
        assertTrue(ChatReadScope.enabled(true, false));
        assertNotEquals(ChatReadScope.pausedKey(ChatSnapshot.QQ), ChatReadScope.pausedKey(ChatSnapshot.WECHAT));
    }
    @Test public void qqOnlyNeverSelectsAWeChatWindow() {
        ChatReadScope scope = new ChatReadScope();
        scope.observe(10, ChatSnapshot.WECHAT);
        scope.observe(20, ChatSnapshot.QQ);
        assertFalse(scope.mayQuery(10, true, true, true, false, true));
        assertTrue(scope.mayQuery(20, true, true, true, false, true));
    }
    @Test public void weChatOnlyNeverSelectsAQqWindow() {
        ChatReadScope scope = new ChatReadScope();
        scope.observe(20, ChatSnapshot.QQ);
        assertFalse(scope.mayQuery(20, true, true, true, true, false));
    }
    @Test public void unknownBackgroundAndNonApplicationWindowsAreRejected() {
        ChatReadScope scope = new ChatReadScope();
        assertFalse(scope.mayQuery(20, true, true, true, true, true));
        scope.observe(20, ChatSnapshot.QQ);
        assertFalse(scope.mayQuery(20, false, true, true, true, true));
        assertFalse(scope.mayQuery(20, true, false, false, true, true));
        assertFalse(scope.mayQuery(20, true, true, true, false, false));
    }
    @Test public void changedWindowOwnerCannotReuseAnAllowedMapping() {
        ChatReadScope scope = new ChatReadScope();
        scope.observe(20, ChatSnapshot.QQ);
        scope.observe(20, "other.app");
        assertFalse(scope.mayQuery(20, true, true, true, true, true));
    }
    @Test(expected = IllegalArgumentException.class) public void unknownPackageHasNoPauseKey() {
        ChatReadScope.pausedKey("other.app");
    }
}
