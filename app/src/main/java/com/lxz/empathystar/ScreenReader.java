package com.lxz.empathystar;

/** Internal, Android-free boundary. Implementations never send messages or call models. */
interface ScreenReader {
    enum Access { ALLOWED, PAUSED, NOT_AUTHORIZED }
    interface AccessPolicy {
        Access access(String packageName);
        default boolean groupAllowed(String packageName) { return false; }
    }
    enum FillStatus { INSERTED, NO_REPLY, STALE_SNAPSHOT, NOT_CHAT, NO_EDITOR, NONEMPTY_DRAFT, UNSUPPORTED_ACTION, READ_ERROR }
    void observeWindow(int windowId, String packageName);
    ScreenReadResult read(long revision);
    boolean isCurrent(ChatSnapshot snapshot, long revision);
    FillStatus fillEmptyDraft(ChatSnapshot snapshot, long revision, String reply);
}
