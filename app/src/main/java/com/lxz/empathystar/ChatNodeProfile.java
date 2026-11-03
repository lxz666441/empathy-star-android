package com.lxz.empathystar;

/** Null-safe structural predicates; no Android dependency or message content. */
final class ChatNodeProfile {
    static boolean groupNickname(CharSequence className, String resourceId, CharSequence parentClass,
                                 boolean visible, int children, ChatSurfacePolicy.Bounds label,
                                 ChatSurfacePolicy.Bounds message) {
        return visible && children == 0 && "com.tencent.mobileqq:id/mjq".equals(resourceId)
            && classIs(className, "android.widget.TextView") && classIs(parentClass, "android.widget.LinearLayout")
            && label != null && message != null && label.width() > 0 && label.height() > 0
            && label.bottom <= message.top && message.top - label.bottom <= label.height()
            && label.left >= message.left && label.left < message.right;
    }
    static boolean classIs(CharSequence actual, String expected) {
        return actual != null && expected.equals(actual.toString());
    }

    static boolean messageText(String packageName, CharSequence className, String resourceId,
                               boolean insideRow, boolean insideList) {
        if (!classIs(className, "android.widget.TextView") || !insideRow || !insideList) return false;
        if (ChatSnapshot.WECHAT.equals(packageName)) return "com.tencent.mm:id/bkl".equals(resourceId);
        if (ChatSnapshot.QQ.equals(packageName)) return "com.tencent.mobileqq:id/mjn".equals(resourceId);
        return false;
    }
}
