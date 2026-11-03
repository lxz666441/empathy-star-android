package com.lxz.empathystar;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The only product boundary allowed to acquire application nodes and write empty drafts. */
final class AccessibilityChatReader implements ScreenReader {
    interface Diagnostics {
        void rootQueried(String packageName);
        void recovered(int before, boolean refreshed, int afterRefresh, boolean cacheCleared, int afterQuery);
    }
    private final AccessibilityService service;
    private final AccessPolicy policy;
    private final Diagnostics diagnostics;
    private final ChatReadScope scope = new ChatReadScope();
    private final ChatAppAdapter wechat = new WeChatAdapter(), qq = new QqAdapter();
    private final ChatAppAdapter qqGroup = new QqGroupAdapter();
    private long lastRecoveryRevision = -1;
    private int lastRecoveryWindow = -1;
    private String lastRecoveryPackage = "";

    AccessibilityChatReader(AccessibilityService service, AccessPolicy policy, Diagnostics diagnostics) {
        this.service = service; this.policy = policy; this.diagnostics = diagnostics;
    }
    @Override public void observeWindow(int windowId, String packageName) {
        if (windowId < 0 || packageName == null) return;
        // Never let accessibility-overlay events overwrite an application's owner.
        // This inspects metadata only, not roots of another app.
        for (AccessibilityWindowInfo window : service.getWindows()) {
            if (window.getId() == windowId && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                && (window.isActive() || window.isFocused())) {
                scope.observe(windowId, packageName);
                return;
            }
        }
    }

    private static final class WindowRead {
        AccessibilityNodeInfo root;
        ChatSnapshot snapshot;
        ScreenReadResult.State state;
        int windows, appWindows, focusedWindows, activeChildren = -1, maxChildren = -1;
        boolean activeMatches;
    }

    private AccessibilityNodeInfo allowedRoot(AccessibilityWindowInfo window) {
        String owner = scope.owner(window.getId());
        if (!scope.mayQuery(window.getId(), window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION,
            window.isActive(), window.isFocused(), policy.access(ChatSnapshot.WECHAT) == Access.ALLOWED,
            policy.access(ChatSnapshot.QQ) == Access.ALLOWED) || policy.access(owner) != Access.ALLOWED) return null;
        diagnostics.rootQueried(owner);
        AccessibilityNodeInfo root = window.getRoot();
        return root != null && owner.equals(String.valueOf(root.getPackageName()))
            && root.getWindowId() == window.getId() ? root : null;
    }

    private WindowRead acquire(long revision) {
        WindowRead result = new WindowRead();
        List<AccessibilityWindowInfo> windows = service.getWindows();
        result.windows = windows.size();
        // Window metadata is safe to inspect; roots of unknown/paused/foreign owners are never requested.
        result.state = ScreenReadResult.State.NO_WINDOW;
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                || (!window.isActive() && !window.isFocused())) continue;
            String owner = scope.owner(window.getId());
            if (owner == null) { result.state = ScreenReadResult.State.WINDOW_UNRESOLVED; continue; }
            if (!ChatSnapshot.supported(owner)) { result.state = ScreenReadResult.State.UNSUPPORTED; continue; }
            Access access = policy.access(owner);
            result.snapshot = new ChatSnapshot(owner, window.getId(), revision);
            if (access != Access.ALLOWED) {
                result.state = access == Access.PAUSED ? ScreenReadResult.State.PAUSED : ScreenReadResult.State.NOT_AUTHORIZED;
                return result;
            }
            result.root = allowedRoot(window);
            result.appWindows = 1; result.focusedWindows = 1;
            result.activeMatches = result.root != null;
            result.activeChildren = result.root == null ? -1 : result.root.getChildCount();
            result.maxChildren = result.activeChildren;
            result.state = result.root == null ? ScreenReadResult.State.NO_WINDOW
                : result.activeChildren == 0 ? ScreenReadResult.State.EMPTY_TREE : ScreenReadResult.State.READY;
            return result;
        }
        return result;
    }

    private WindowRead recover(WindowRead current, long revision) {
        if (current.root == null || current.root.getChildCount() > 0) return current;
        ChatSnapshot origin = current.snapshot;
        if (lastRecoveryRevision == revision && lastRecoveryWindow == origin.windowId
            && lastRecoveryPackage.equals(origin.packageName)) return current;
        lastRecoveryRevision = revision; lastRecoveryWindow = origin.windowId; lastRecoveryPackage = origin.packageName;
        int before = current.root.getChildCount();
        boolean refreshed = current.root.refresh();
        int after = current.root.getChildCount();
        boolean cleared = after <= 0 && Build.VERSION.SDK_INT >= 33 && service.clearCache();
        WindowRead next = acquire(revision);
        diagnostics.recovered(before, refreshed, after, cleared, next.root == null ? -1 : next.root.getChildCount());
        // A recovery must not silently move a read to a different application or window.
        if (next.snapshot == null || !origin.matches(next.snapshot.packageName, next.snapshot.windowId, revision)) {
            return unavailableWindow(ScreenReadResult.State.NO_WINDOW);
        }
        return next;
    }

    private WindowRead unavailableWindow(ScreenReadResult.State state) {
        WindowRead result = new WindowRead(); result.state = state; return result;
    }

    private ChatAppAdapter adapter(String packageName) { return ChatSnapshot.QQ.equals(packageName) ? qq : wechat; }

    @Override public ScreenReadResult read(long revision) {
        try {
            WindowRead window = recover(acquire(revision), revision);
            if (window.root == null) return ScreenReadResult.unavailable(window.state, window.snapshot);
            AccessibilityNodeInfo root = window.root;
            boolean group = ChatSnapshot.QQ.equals(window.snapshot.packageName) && QqGroupAdapter.groupHeader(root);
            if (group && !policy.groupAllowed(window.snapshot.packageName))
                return ScreenReadResult.unavailable(ScreenReadResult.State.NOT_AUTHORIZED, window.snapshot);
            ChatAppAdapter adapter = group ? qqGroup : adapter(window.snapshot.packageName);
            DisplayMetrics display = service.getResources().getDisplayMetrics();
            int width = display.widthPixels, height = display.heightPixels;
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            int keyboardTop = 0;
            for (AccessibilityWindowInfo item : service.getWindows()) {
                if (item.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                Rect keyboard = new Rect(); item.getBoundsInScreen(keyboard);
                if (keyboard.left <= 0 && keyboard.right >= width && keyboard.bottom >= height && keyboard.top > 0)
                    keyboardTop = keyboardTop == 0 ? keyboard.top : Math.min(keyboardTop, keyboard.top);
            }
            height = ChatSurfacePolicy.visibleWindowBottom(height, bounds.bottom, keyboardTop);
            NodeMessageScanner scanner = new NodeMessageScanner(adapter, display.density);
            int[] nodes = {0}, editable = {0}, messageStats = new int[5], senderStats = new int[3];
            int[] editors = {0}, buttons = {0}, navigation = {0};
            List<ScreenReadResult.Message> messages = new ArrayList<>();
            boolean composer = adapter.isComposer(root, width, height, editors, buttons, navigation);
            int[] editorTop = {height}, composerTop = {height};
            boolean fallback = false;
            ScreenReadResult.State state;
            if (!composer) {
                scanner.countStructure(root, nodes, editable, messageStats, 0);
                state = root.getChildCount() == 0 ? ScreenReadResult.State.EMPTY_TREE : ScreenReadResult.State.NOT_CHAT;
            } else {
                scanner.collect(root, messages, editorTop, composerTop, nodes, editable,
                    messageStats, senderStats, width, height, 0);
                if (composerTop[0] == height) {
                    composerTop[0] = scanner.findBottomComposerTop(root, width, height, 0);
                    fallback = composerTop[0] != height;
                }
                if (composerTop[0] == height) {
                    state = ScreenReadResult.State.NO_COMPOSER;
                    messages.clear();
                } else {
                    final int safeBottom = composerTop[0] - Math.round(display.density * 2);
                    messages.removeIf(item -> item.bounds.bottom >= safeBottom);
                    messages.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
                    state = messages.isEmpty() ? ScreenReadResult.State.NO_TEXT : ScreenReadResult.State.READY;
                }
            }
            ScreenReadResult.Metrics metrics = new ScreenReadResult.Metrics(nodes[0], editable[0], window.windows,
                window.appWindows, window.focusedWindows, window.activeChildren, window.maxChildren,
                window.activeMatches, messageStats, senderStats, new int[]{editors[0], buttons[0], navigation[0]});
            List<ChatSurfacePolicy.Bounds> exclusions = new ArrayList<>();
            if (composer) scanner.collectExclusions(root, exclusions, 0, new int[1]);
            ScreenReadResult.Geometry geometry = composer
                ? new ScreenReadResult.Geometry(width, height, composerTop[0], editorTop[0], fallback, exclusions) : null;
            return new ScreenReadResult(state, window.snapshot, geometry, metrics, messages,
                group ? ScreenReadResult.ConversationType.GROUP : ScreenReadResult.ConversationType.PRIVATE);
        } catch (RuntimeException failure) {
            // Platform failures are explicit; neither exception text nor chat content leaves this module.
            return ScreenReadResult.unavailable(ScreenReadResult.State.READ_ERROR, null);
        }
    }

    @Override public boolean isCurrent(ChatSnapshot snapshot, long revision) {
        if (snapshot == null || snapshot.revision != revision || policy.access(snapshot.packageName) != Access.ALLOWED) return false;
        try {
            WindowRead current = acquire(revision);
            if (current.root != null && ChatSnapshot.QQ.equals(snapshot.packageName)
                && QqGroupAdapter.groupHeader(current.root) && !policy.groupAllowed(snapshot.packageName)) return false;
            return current.root != null && snapshot.matches(String.valueOf(current.root.getPackageName()),
                current.root.getWindowId(), revision);
        } catch (RuntimeException failure) { return false; }
    }

    @Override public FillStatus fillEmptyDraft(ChatSnapshot snapshot, long revision, String reply) {
        if (reply == null || reply.isEmpty()) return FillStatus.NO_REPLY;
        if (snapshot == null || snapshot.revision != revision || policy.access(snapshot.packageName) != Access.ALLOWED)
            return FillStatus.STALE_SNAPSHOT;
        try {
            WindowRead current = acquire(revision);
            if (current.root == null || !snapshot.matches(String.valueOf(current.root.getPackageName()),
                current.root.getWindowId(), revision)) return FillStatus.STALE_SNAPSHOT;
            AccessibilityNodeInfo root = current.root;
            if (ChatSnapshot.QQ.equals(snapshot.packageName) && QqGroupAdapter.groupHeader(root)) return FillStatus.NOT_CHAT;
            ChatAppAdapter adapter = adapter(snapshot.packageName);
            Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
            DisplayMetrics display = service.getResources().getDisplayMetrics();
            if (!adapter.isComposer(root, display.widthPixels, bounds.bottom, new int[1], new int[1], new int[1]))
                return FillStatus.NOT_CHAT;
            AccessibilityNodeInfo editor = new NodeMessageScanner(adapter, display.density).findEditor(root);
            if (editor == null) return FillStatus.NO_EDITOR;
            CharSequence existing = editor.getText();
            if (existing != null && existing.length() > 0) return FillStatus.NONEMPTY_DRAFT;
            if (!isCurrent(snapshot, revision) || !editor.refresh() || !editor.isVisibleToUser() || !editor.isEnabled())
                return FillStatus.STALE_SNAPSHOT;
            existing = editor.getText();
            if (existing != null && existing.length() > 0) return FillStatus.NONEMPTY_DRAFT;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply);
            return editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ? FillStatus.INSERTED : FillStatus.UNSUPPORTED_ACTION;
        } catch (RuntimeException failure) { return FillStatus.READ_ERROR; }
    }
}
