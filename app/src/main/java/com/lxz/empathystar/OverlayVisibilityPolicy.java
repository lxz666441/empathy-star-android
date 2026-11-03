package com.lxz.empathystar;

/** Pure fail-closed display rules, also used before requesting any app nodes. */
final class OverlayVisibilityPolicy {
    private OverlayVisibilityPolicy() {}

    static boolean screenAvailable(boolean interactive, boolean keyguardLocked, boolean deviceLocked) {
        return interactive && !keyguardLocked && !deviceLocked;
    }

    static long scrollDelay(long now, long lastScroll) {
        if (lastScroll < 0) return 0;
        return Math.max(0, 120 - Math.max(0, now - lastScroll));
    }

    static boolean differentKnownWindow(int expected, int eventWindow) {
        return expected >= 0 && eventWindow >= 0 && expected != eventWindow;
    }

    static ChatSurfacePolicy.Position fixedSummaryPosition(int width, int composerTop,
            int buttonWidth, int buttonHeight, int left, int top, int chatTop) {
        if (buttonWidth <= 0 || buttonHeight <= 0 || left < 0 || top < 0
            || left + buttonWidth > width || top + buttonHeight > Math.min(composerTop, chatTop)) return null;
        return new ChatSurfacePolicy.Position(left, top);
    }

    static ChatSurfacePolicy.Position summaryPosition(java.util.List<ChatSurfacePolicy.Bounds> excluded,
            int width, int height, int composerTop, int buttonWidth, int buttonHeight, int margin, int chatTop) {
        return summaryPosition(excluded, width, height, composerTop, buttonWidth, buttonHeight, margin, chatTop, false);
    }

    static ChatSurfacePolicy.Position summaryPosition(java.util.List<ChatSurfacePolicy.Bounds> excluded,
            int width, int height, int composerTop, int buttonWidth, int buttonHeight, int margin, int chatTop,
            boolean compact) {
        int bottom = Math.min(height, composerTop);
        if (buttonWidth <= 0 || buttonHeight <= 0 || width < buttonWidth + margin * 2) return null;
        // Compact entry uses the avatar-side whitespace first, separate from message stars.
        int[] xs = compact ? new int[]{margin, width - buttonWidth - margin, (width - buttonWidth) / 2}
            : new int[]{width - buttonWidth - margin, (width - buttonWidth) / 2, margin};
        // Search whitespace in the verified chat area, never move into header/input controls.
        java.util.List<Integer> ys = new java.util.ArrayList<>();
        ys.add(bottom - buttonHeight - margin);
        for (ChatSurfacePolicy.Bounds area : excluded) ys.add(area.top - buttonHeight - margin);
        for (int y : ys) {
            if (y < chatTop || y + buttonHeight + margin > bottom) continue;
            for (int x : xs) {
                ChatSurfacePolicy.Bounds candidate = new ChatSurfacePolicy.Bounds(x, y,
                    x + buttonWidth, y + buttonHeight).expand(margin);
                boolean blocked = false;
                for (ChatSurfacePolicy.Bounds area : excluded) {
                    if (candidate.intersects(area)) { blocked = true; break; }
                }
                if (!blocked) return new ChatSurfacePolicy.Position(x, y);
            }
        }
        return null;
    }
}
