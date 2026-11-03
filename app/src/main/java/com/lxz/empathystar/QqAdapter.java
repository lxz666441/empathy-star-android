package com.lxz.empathystar;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

/** QQ 9.3.70 node profile, measured on V2049A. Unknown structures fail closed. */
final class QqAdapter implements ChatAppAdapter {
    private static final String PREFIX = "com.tencent.mobileqq:id/";
    public String packageName() { return ChatSnapshot.QQ; }

    public AccessibilityNodeInfo messageRow(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo bubble = ChatAppAdapter.ancestor(node, PREFIX + "p2q");
        if (bubble == null) return null;
        AccessibilityNodeInfo row = bubble.getParent();
        if (!ChatAppAdapter.id(row, PREFIX + "root")) return null;
        AccessibilityNodeInfo list = row.getParent();
        return list != null && ChatNodeProfile.classIs(list.getClassName(), "androidx.recyclerview.widget.RecyclerView")
            ? row : null;
    }

    public boolean isMessageText(AccessibilityNodeInfo node, int[] stats) {
        boolean text = node != null && ChatAppAdapter.id(node, PREFIX + "mjn")
            && ChatNodeProfile.classIs(node.getClassName(), "android.widget.TextView");
        if (!text) return false; // no parent-chain IPC for buttons, timestamps and other leaves
        boolean row = messageRow(node) != null;
        if (text) stats[1]++;
        if (row) { stats[2]++; stats[3]++; }
        if (text && row) stats[4]++;
        return node != null && ChatNodeProfile.messageText(packageName(), node.getClassName(),
            node.getViewIdResourceName(), row, row);
    }

    public boolean isEditor(AccessibilityNodeInfo node) {
        return node != null && node.isVisibleToUser() && node.isEditable()
            && ChatNodeProfile.classIs(node.getClassName(), "android.widget.EditText")
            && ChatAppAdapter.id(node, PREFIX + "input")
            && ChatAppAdapter.ancestor(node, PREFIX + "dmj") != null;
    }

    public boolean isComposer(AccessibilityNodeInfo root, int width, int height,
                              int[] editors, int[] buttons, int[] navigation) {
        int[] header = new int[4], visited = new int[1];
        countEditors(root, width, height, editors, header, visited, 0);
        // Private-chat header includes the contact presence flipper; conversation
        // list/search and unverified group headers must not pass merely for having text.
        return visited[0] < 5000 && header[0] > 0 && header[1] > 0 && header[2] > 0
            && header[3] > 0 && editors[0] == 1;
    }

    private void countEditors(AccessibilityNodeInfo node, int width, int height, int[] count,
                              int[] header, int[] visited, int depth) {
        if (node == null || depth > 40 || visited[0]++ >= 5000) return;
        if (node.isVisibleToUser()) {
            String id = node.getViewIdResourceName();
            if ((PREFIX + "3md").equals(id)) header[0]++;
            if ((PREFIX + "mam").equals(id)) header[1]++;
            if ((PREFIX + "j64").equals(id)) header[2]++;
            if ((PREFIX + "dmj").equals(id)) header[3]++;
        }
        if (isEditor(node)) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (bounds.width() >= width * .35f && bounds.top >= height * .55f) count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++)
            countEditors(node.getChild(i), width, height, count, header, visited, depth + 1);
    }
}
