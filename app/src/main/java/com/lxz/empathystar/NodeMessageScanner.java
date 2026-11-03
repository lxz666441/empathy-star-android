package com.lxz.empathystar;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;

/** Package-adapted structure parsing, independent of windows, overlays and models. */
final class NodeMessageScanner {
    private final ChatAppAdapter adapter;
    private final String sourcePackage;
    private final float density;
    NodeMessageScanner(ChatAppAdapter adapter, float density) {
        this.adapter = adapter; this.sourcePackage = adapter.packageName(); this.density = density;
    }
    private int dp(int value) { return Math.round(density * value); }

    void collect(AccessibilityNodeInfo node, List<ScreenReadResult.Message> out, int[] editorTop, int[] composerTop,
                         int[] nodeCount, int[] editableCount, int[] messageNodeStats, int[] senderStats,
                         int width, int height, int depth) {
        if (node == null || depth > 40 || out.size() > 100 || nodeCount[0] >= 5000) return;
        nodeCount[0]++;
        if (node.isEditable() || android.text.TextUtils.equals("android.widget.EditText", node.getClassName())) {
            editableCount[0]++;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 8 && bounds.top > height * 0.30f && bounds.top < editorTop[0]) {
                editorTop[0] = bounds.top;
                composerTop[0] = findComposerTop(node, bounds, width, height);
            }
        }
        boolean messageNode = node.isVisibleToUser() && !node.isEditable() && node.getChildCount() == 0
            && adapter.isMessageText(node, messageNodeStats);
        // Do not read names, list summaries, buttons, timestamps or card contents.
        CharSequence value = messageNode ? node.getText() : null;
        if (value != null && !node.isEditable() && node.getChildCount() == 0) {
            String text = value.toString().trim();
            if (!text.isEmpty()) {
                messageNodeStats[0]++;
                if (messageNode) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    AccessibilityNodeInfo row = adapter.messageRow(node);
                    boolean inViewport = bounds.top > height * 0.11f && bounds.bottom < height * 0.99f;
                    if (ChatSnapshot.QQ.equals(sourcePackage)) {
                        AccessibilityNodeInfo list = row == null ? null : row.getParent();
                        Rect viewport = new Rect();
                        if (list != null) list.getBoundsInScreen(viewport);
                        inViewport = list != null && list.isVisibleToUser()
                            && ChatSurfacePolicy.insideMessageViewport(
                                new ChatSurfacePolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                                new ChatSurfacePolicy.Bounds(viewport.left, viewport.top, viewport.right, viewport.bottom),
                                width, height);
                    }
                    if (text.length() <= 500 && bounds.width() > 8 && bounds.height() > 8
                        && inViewport
                        && bounds.left >= 0 && bounds.right <= width
                        && !looksLikeChrome(text) && !looksLikeTimestamp(text)) {
                        int side = classifyMessageSide(row, bounds, width, height);
                        if (ChatSnapshot.QQ.equals(sourcePackage)) {
                            AccessibilityNodeInfo bubble = ChatAppAdapter.ancestor(node, "com.tencent.mobileqq:id/p2q");
                            Rect outer = new Rect();
                            if (bubble != null && bubble.isVisibleToUser()) {
                                bubble.getBoundsInScreen(outer);
                                if (outer.contains(bounds) && outer.left >= 0 && outer.right <= width
                                    && outer.bottom < composerTop[0]) bounds = outer;
                            }
                        }
                        if (side == ChatSurfacePolicy.MESSAGE_SIDE_INCOMING) senderStats[0]++;
                        else if (side == ChatSurfacePolicy.MESSAGE_SIDE_OUTGOING) senderStats[1]++;
                        else senderStats[2]++;
                        out.add(new ScreenReadResult.Message(text, new ChatSurfacePolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom), side,
                            adapter.speakerName(node)));
                    }
                }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++)
            collect(node.getChild(i), out, editorTop, composerTop, nodeCount, editableCount,
                messageNodeStats, senderStats, width, height, depth + 1);
    }

    void countStructure(AccessibilityNodeInfo node, int[] nodes, int[] editors, int[] stats, int depth) {
        if (node == null || depth > 40 || nodes[0] >= 5000) return;
        nodes[0]++;
        if (node.isEditable()) editors[0]++;
        // Structural diagnostics deliberately do not read text on a rejected page.
        adapter.isMessageText(node, stats);
        for (int i = 0; i < node.getChildCount(); i++) countStructure(node.getChild(i), nodes, editors, stats, depth + 1);
    }

    void collectExclusions(AccessibilityNodeInfo node, List<ChatSurfacePolicy.Bounds> out, int depth, int[] count) {
        if (node == null || depth > 40 || count[0]++ >= 5000 || out.size() >= 500) return;
        String id = node.getViewIdResourceName();
        boolean occupied = ChatSnapshot.QQ.equals(sourcePackage)
            ? (("com.tencent.mobileqq:id/p2q".equals(id) || "com.tencent.mobileqq:id/wgh".equals(id))
                && ChatAppAdapter.ancestor(node, "com.tencent.mobileqq:id/root") != null)
                || ("com.tencent.mobileqq:id/55h".equals(id)
                    && ChatAppAdapter.ancestor(node, "com.tencent.mobileqq:id/mko") != null)
            : ChatAppAdapter.ancestor(node, "com.tencent.mm:id/bkj") != null
                && (node.getChildCount() == 0 || "com.tencent.mm:id/bkl".equals(id));
        if (occupied && node.isVisibleToUser()) {
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (bounds.width() > 0 && bounds.height() > 0)
                out.add(new ChatSurfacePolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom));
        }
        for (int i = 0; i < node.getChildCount(); i++) collectExclusions(node.getChild(i), out, depth + 1, count);
    }

    private int classifyMessageSide(AccessibilityNodeInfo row, Rect messageBounds,
                                    int width, int height) {
        if (row == null) return ChatSurfacePolicy.MESSAGE_SIDE_UNKNOWN;
        List<ChatSurfacePolicy.Bounds> avatarCandidates = new ArrayList<>();
        collectAvatarBounds(row, avatarCandidates, messageBounds, width, height, 0);
        return ChatSurfacePolicy.classifyMessageSide(new ChatSurfacePolicy.Bounds(
            messageBounds.left, messageBounds.top, messageBounds.right, messageBounds.bottom), avatarCandidates);
    }

    private void collectAvatarBounds(AccessibilityNodeInfo node, List<ChatSurfacePolicy.Bounds> candidates,
                                    Rect messageBounds, int width, int height, int depth) {
        if (node == null || depth > 12 || candidates.size() > 8) return;
        CharSequence className = node.getClassName();
        if (className != null && ("android.widget.ImageView".contentEquals(className)
            || "android.widget.ImageButton".contentEquals(className))) {
            if (ChatSnapshot.QQ.equals(sourcePackage)
                && ChatAppAdapter.ancestor(node, "com.tencent.mobileqq:id/wgh") == null) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            int minAvatar = dp(28);
            int maxAvatar = dp(72);
            boolean avatarSized = bounds.width() >= minAvatar && bounds.width() <= maxAvatar
                && bounds.height() >= minAvatar && bounds.height() <= maxAvatar
                && bounds.width() <= bounds.height() * 1.25f
                && bounds.height() <= bounds.width() * 1.25f;
            boolean sameMessageBand = bounds.bottom > messageBounds.top - maxAvatar
                && bounds.top < messageBounds.bottom + maxAvatar;
            boolean edgeAligned = bounds.right <= width * 0.25f || bounds.left >= width * 0.75f;
            if (avatarSized && sameMessageBand && edgeAligned) {
                candidates.add(new ChatSurfacePolicy.Bounds(bounds.left, bounds.top,
                    bounds.right, bounds.bottom));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++)
            collectAvatarBounds(node.getChild(i), candidates, messageBounds, width, height, depth + 1);
    }

    private int findComposerTop(AccessibilityNodeInfo editor, Rect editorBounds, int width, int height) {
        int candidateTop = height;
        AccessibilityNodeInfo ancestor = editor.getParent();
        for (int depth = 0; ancestor != null && depth < 8; depth++) {
            Rect bounds = new Rect();
            ancestor.getBoundsInScreen(bounds);
            int nodeHeight = bounds.height();
            if (bounds.width() >= width * 0.70f && nodeHeight >= dp(36) && nodeHeight <= dp(220)
                && bounds.top > height * 0.50f && bounds.bottom >= editorBounds.bottom - dp(2)
                && bounds.bottom >= height * 0.82f) {
                candidateTop = Math.min(candidateTop, bounds.top);
            }
            ancestor = ancestor.getParent();
        }
        return candidateTop;
    }

    int findBottomComposerTop(AccessibilityNodeInfo node, int width, int height, int depth) {
        if (node == null || depth > 40) return height;
        int candidateTop = height;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int nodeHeight = bounds.height();
        if (bounds.width() >= width * 0.85f && nodeHeight >= dp(36) && nodeHeight <= dp(220)
            && bounds.top > height * 0.60f && bounds.bottom >= height * 0.82f
            // Android app metrics exclude the gesture-navigation inset, while
            // AccessibilityNodeInfo bounds may still use full physical-screen coordinates.
            && bounds.bottom <= height + dp(80)) {
            candidateTop = bounds.top;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            candidateTop = Math.min(candidateTop,
                findBottomComposerTop(node.getChild(i), width, height, depth + 1));
        }
        return candidateTop;
    }

    private boolean looksLikeChrome(String text) {
        return text.equals("发送") || text.equals("按住说话") || text.equals("表情") || text.equals("更多功能");
    }

    private boolean looksLikeTimestamp(String text) {
        return text.matches("^\\d{1,2}:\\d{2}$")
            || text.matches("^(?:\\d{1,2}月\\d{1,2}日|今天|昨天|周[一二三四五六日天]|星期[一二三四五六日天]).*\\d{1,2}:\\d{2}$");
    }


    AccessibilityNodeInfo findEditor(AccessibilityNodeInfo node) {
        return findEditor(node, 0, new int[1]);
    }
    private AccessibilityNodeInfo findEditor(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null || depth > 40 || visited[0]++ >= 5000) return null;
        if (adapter.isEditor(node) && node.isVisibleToUser() && node.isEnabled()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditor(node.getChild(i), depth + 1, visited);
            if (found != null) return found;
        }
        return null;
    }
}
