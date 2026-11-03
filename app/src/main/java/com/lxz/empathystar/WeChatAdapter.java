package com.lxz.empathystar;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

final class WeChatAdapter implements ChatAppAdapter {
    public String packageName() { return ChatSnapshot.WECHAT; }

    public AccessibilityNodeInfo messageRow(AccessibilityNodeInfo node) {
        return ChatAppAdapter.ancestor(node, "com.tencent.mm:id/bkj");
    }

    public boolean isMessageText(AccessibilityNodeInfo node, int[] stats) {
        boolean text = node != null && ChatNodeProfile.classIs(node.getClassName(), "android.widget.TextView")
            && ChatAppAdapter.id(node, "com.tencent.mm:id/bkl");
        boolean row = messageRow(node) != null;
        boolean list = ChatAppAdapter.ancestor(node, "com.tencent.mm:id/bp0") != null;
        if (text) stats[1]++;
        if (row) stats[2]++;
        if (list) stats[3]++;
        if (text && row && list) stats[4]++;
        return node != null && ChatNodeProfile.messageText(packageName(), node.getClassName(),
            node.getViewIdResourceName(), row, list);
    }

    public boolean isEditor(AccessibilityNodeInfo node) {
        return node != null && node.isVisibleToUser() && node.isEditable()
            && ChatNodeProfile.classIs(node.getClassName(), "android.widget.EditText");
    }

    public boolean isComposer(AccessibilityNodeInfo root, int width, int height,
                              int[] editors, int[] buttons, int[] navigation) {
        count(root, width, height, editors, buttons, navigation, 0);
        return ChatSurfacePolicy.hasChatComposer(editors[0], buttons[0], navigation[0]);
    }

    private void count(AccessibilityNodeInfo node, int width, int height, int[] editors,
                       int[] buttons, int[] navigation, int depth) {
        if (node == null || depth > 40) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (node.isVisibleToUser() && bounds.top >= height * .70f && bounds.bottom >= height * .82f) {
            if (isEditor(node) && bounds.width() >= width * .40f) editors[0]++;
            if (ChatNodeProfile.classIs(node.getClassName(), "android.widget.ImageButton")) buttons[0]++;
            if (ChatAppAdapter.id(node, "com.tencent.mm:id/icon_tv")) navigation[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) count(node.getChild(i), width, height,
            editors, buttons, navigation, depth + 1);
    }
}
