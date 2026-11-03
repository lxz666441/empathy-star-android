package com.lxz.empathystar;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatNodeProfileTest {
    @Test public void emptyWeChatRootCanHaveNoClassNameWithoutCrashing() {
        assertFalse(ChatNodeProfile.classIs(null, "android.widget.TextView"));
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.WECHAT, null, null, false, false));
    }
    @Test public void classComparisonAcceptsCharSequenceNotOnlyString() {
        assertTrue(ChatNodeProfile.classIs(new StringBuilder("android.widget.TextView"), "android.widget.TextView"));
    }
    @Test public void messageProfilesRequireTheirOwnPackageAndFullId() {
        assertTrue(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.TextView", "com.tencent.mobileqq:id/mjn", true, true));
        assertTrue(ChatNodeProfile.messageText(ChatSnapshot.WECHAT, "android.widget.TextView", "com.tencent.mm:id/bkl", true, true));
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.TextView", "com.tencent.mm:id/bkl", true, true));
        assertFalse(ChatNodeProfile.messageText("other", "android.widget.TextView", "other:id/mjn", true, true));
    }
    @Test public void listSummariesAndDetachedTextCannotBecomeMessages() {
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.TextView", "com.tencent.mobileqq:id/mjn", false, true));
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.TextView", "com.tencent.mobileqq:id/mjn", true, false));
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.TextView", "com.tencent.mobileqq:id/summary", true, true));
    }
    @Test public void imageCardsAndUnknownWeChatIdsAreRejected() {
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.QQ, "android.widget.ImageView", "com.tencent.mobileqq:id/mjn", true, true));
        assertFalse(ChatNodeProfile.messageText(ChatSnapshot.WECHAT, "android.widget.TextView", null, true, true));
    }
}
