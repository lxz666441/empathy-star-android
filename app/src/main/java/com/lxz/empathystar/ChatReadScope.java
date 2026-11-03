package com.lxz.empathystar;

import java.util.HashMap;
import java.util.Map;

/** Gate root queries using event metadata, before asking Android for any node. */
final class ChatReadScope {
    private final Map<Integer, String> windowOwners = new HashMap<>();

    static String pausedKey(String packageName) {
        if (!ChatSnapshot.supported(packageName)) throw new IllegalArgumentException("UNSUPPORTED_APP");
        return ChatSnapshot.QQ.equals(packageName) ? "qq_read_paused" : "wechat_read_paused";
    }

    static boolean enabled(boolean consented, boolean paused) { return consented && !paused; }

    synchronized void observe(int windowId, String packageName) {
        if (windowId < 0 || packageName == null) return;
        if (windowOwners.size() >= 128 && !windowOwners.containsKey(windowId)) windowOwners.clear();
        windowOwners.put(windowId, packageName);
    }

    synchronized String owner(int windowId) { return windowOwners.get(windowId); }

    boolean mayQuery(int windowId, boolean application, boolean active, boolean focused,
                     boolean weChatEnabled, boolean qqEnabled) {
        if (!application || (!active && !focused)) return false;
        String owner = owner(windowId);
        return (ChatSnapshot.WECHAT.equals(owner) && weChatEnabled)
            || (ChatSnapshot.QQ.equals(owner) && qqEnabled);
    }
}
