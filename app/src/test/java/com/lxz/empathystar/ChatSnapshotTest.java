package com.lxz.empathystar;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatSnapshotTest {
    @Test public void packageSwitchRejectsSameRevisionAndWindow() {
        ChatSnapshot source = new ChatSnapshot(ChatSnapshot.QQ, 15, 7);
        assertTrue(source.matches(ChatSnapshot.QQ, 15, 7));
        assertFalse(source.matches(ChatSnapshot.WECHAT, 15, 7));
        assertFalse(source.matches("com.other.app", 15, 7));
    }
    @Test public void windowAndRevisionMustBothRemainCurrent() {
        ChatSnapshot source = new ChatSnapshot(ChatSnapshot.WECHAT, 15, 7);
        assertFalse(source.matches(ChatSnapshot.WECHAT, 16, 7));
        assertFalse(source.matches(ChatSnapshot.WECHAT, 15, 8));
        assertFalse(new ChatSnapshot(ChatSnapshot.WECHAT, -1, 7).matches(ChatSnapshot.WECHAT, -1, 7));
    }
    @Test public void qqDoesNotInheritWeChatReadingOrUploadConsent() {
        assertNotEquals(ChatSnapshot.readConsentKey(ChatSnapshot.QQ), ChatSnapshot.readConsentKey(ChatSnapshot.WECHAT));
        assertNotEquals(ChatSnapshot.modelConsentKey(ChatSnapshot.QQ), ChatSnapshot.modelConsentKey(ChatSnapshot.WECHAT));
        assertEquals("consent", ChatSnapshot.readConsentKey(ChatSnapshot.WECHAT));
        assertEquals("ai_data_consent", ChatSnapshot.modelConsentKey(ChatSnapshot.WECHAT));
    }
    @Test(expected = IllegalArgumentException.class) public void arbitraryPackagesCannotCreateSnapshots() {
        new ChatSnapshot("other", 1, 1);
    }
    @Test(expected = IllegalArgumentException.class) public void arbitraryPackagesCannotUseExistingConsent() {
        ChatSnapshot.readConsentKey("other");
    }
}
