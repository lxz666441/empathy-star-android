package com.lxz.empathystar;

/** Immutable origin of a marker, request or reply. Contains no conversation content. */
final class ChatSnapshot {
    static final String WECHAT = "com.tencent.mm";
    static final String QQ = "com.tencent.mobileqq";
    final String packageName;
    final int windowId;
    final long revision;

    ChatSnapshot(String packageName, int windowId, long revision) {
        if (!supported(packageName)) throw new IllegalArgumentException("UNSUPPORTED_APP");
        this.packageName = packageName;
        this.windowId = windowId;
        this.revision = revision;
    }

    static boolean supported(String packageName) {
        return WECHAT.equals(packageName) || QQ.equals(packageName);
    }

    boolean matches(String foregroundPackage, int foregroundWindow, long currentRevision) {
        return windowId >= 0 && packageName.equals(foregroundPackage)
            && windowId == foregroundWindow && revision == currentRevision;
    }

    static String readConsentKey(String packageName) {
        if (!supported(packageName)) throw new IllegalArgumentException("UNSUPPORTED_APP");
        return QQ.equals(packageName) ? "qq_consent" : "consent";
    }

    static String modelConsentKey(String packageName) {
        if (!supported(packageName)) throw new IllegalArgumentException("UNSUPPORTED_APP");
        return QQ.equals(packageName) ? "qq_ai_data_consent" : MainKeys.WECHAT_MODEL_CONSENT;
    }

    // No Android dependency: these contracts run in ordinary JVM tests.
    private static final class MainKeys { static final String WECHAT_MODEL_CONSENT = "ai_data_consent"; }
}
