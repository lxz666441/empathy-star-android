package com.lxz.empathystar;

import android.view.accessibility.AccessibilityNodeInfo;

/** Package-specific structure only. Never guesses message direction from prose. */
interface ChatAppAdapter {
    String packageName();
    boolean isMessageText(AccessibilityNodeInfo node, int[] stats);
    AccessibilityNodeInfo messageRow(AccessibilityNodeInfo node);
    boolean isComposer(AccessibilityNodeInfo root, int width, int height, int[] editors,
                       int[] buttons, int[] navigation);
    boolean isEditor(AccessibilityNodeInfo node);
    default String speakerName(AccessibilityNodeInfo message) { return ""; }

    static boolean id(AccessibilityNodeInfo node, String id) {
        return node != null && id.equals(node.getViewIdResourceName());
    }

    static AccessibilityNodeInfo ancestor(AccessibilityNodeInfo node, String id) {
        AccessibilityNodeInfo current = node == null ? null : node.getParent();
        for (int depth = 0; current != null && depth < 16; depth++, current = current.getParent()) {
            if (id(current, id)) return current;
        }
        return null;
    }

    static boolean hasVisibleId(AccessibilityNodeInfo node, String id, int depth) {
        if (node == null || depth > 40) return false;
        if (id(node, id) && node.isVisibleToUser()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasVisibleId(node.getChild(i), id, depth + 1)) return true;
        }
        return false;
    }
}
