package com.lxz.empathystar;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** QQ 9.3.70 group profile observed on V2049A. Names only from the verified row's nickname slot. */
final class QqGroupAdapter implements ChatAppAdapter {
    private static final String PREFIX = "com.tencent.mobileqq:id/";
    private final QqAdapter messages = new QqAdapter();
    @Override public String packageName() { return ChatSnapshot.QQ; }
    @Override public boolean isMessageText(AccessibilityNodeInfo node, int[] stats) { return messages.isMessageText(node, stats); }
    @Override public AccessibilityNodeInfo messageRow(AccessibilityNodeInfo node) { return messages.messageRow(node); }
    @Override public boolean isEditor(AccessibilityNodeInfo node) { return messages.isEditor(node); }

    static boolean groupHeader(AccessibilityNodeInfo root) {
        // Reject private chat before searching the entire tree for absent group-only IDs.
        if (ChatAppAdapter.hasVisibleId(root, PREFIX + "mam", 0)
            || ChatAppAdapter.hasVisibleId(root, PREFIX + "j64", 0)) return false;
        return ChatAppAdapter.hasVisibleId(root, PREFIX + "3md", 0)
            && ChatAppAdapter.hasVisibleId(root, PREFIX + "3kc", 0)
            && ChatAppAdapter.hasVisibleId(root, PREFIX + "3ka", 0);
    }

    @Override public boolean isComposer(AccessibilityNodeInfo root, int width, int height,
                                        int[] editors, int[] buttons, int[] navigation) {
        if (!groupHeader(root) || !ChatAppAdapter.hasVisibleId(root, PREFIX + "dmj", 0)) return false;
        countEditors(root, width, height, editors, 0, new int[1]);
        return editors[0] == 1;
    }
    private void countEditors(AccessibilityNodeInfo node, int width, int height, int[] count, int depth, int[] visited) {
        if (node == null || depth > 40 || visited[0]++ >= 5000) return;
        if (isEditor(node)) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (bounds.width() >= width * .35f && bounds.top >= height * .55f) count[0]++;
        }
        for (int i = 0; i < node.getChildCount(); i++) countEditors(node.getChild(i), width, height, count, depth + 1, visited);
    }

    @Override public String speakerName(AccessibilityNodeInfo message) {
        AccessibilityNodeInfo row = messageRow(message);
        if (row == null) return "";
        Rect bubble = new Rect(); message.getBoundsInScreen(bubble);
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findNicknameSlot(row, bubble, candidates, 0);
        if (candidates.size() != 1) return "";
        CharSequence text = candidates.get(0).getText();
        if (text == null) return "";
        String name = text.toString().trim();
        return name.isEmpty() || name.length() > 80 || ChatContentPolicy.sensitive(name) ? "" : name;
    }
    int nicknameSlotCount(AccessibilityNodeInfo message) {
        AccessibilityNodeInfo row = messageRow(message);
        if (row == null) return 0;
        Rect bubble = new Rect(); message.getBoundsInScreen(bubble);
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        findNicknameSlot(row, bubble, candidates, 0);
        return candidates.size();
    }
    private void findNicknameSlot(AccessibilityNodeInfo node, Rect bubble,
                                  List<AccessibilityNodeInfo> candidates, int depth) {
        if (node == null || depth > 12 || candidates.size() > 1) return;
        if (node.isVisibleToUser() && ChatAppAdapter.id(node, PREFIX + "mjq")) {
            AccessibilityNodeInfo parent = node.getParent();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (ChatNodeProfile.groupNickname(node.getClassName(), node.getViewIdResourceName(),
                parent == null ? null : parent.getClassName(), node.isVisibleToUser(), node.getChildCount(),
                new ChatSurfacePolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                new ChatSurfacePolicy.Bounds(bubble.left, bubble.top, bubble.right, bubble.bottom))) candidates.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) findNicknameSlot(node.getChild(i), bubble, candidates, depth + 1);
    }
}
