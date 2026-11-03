package com.lxz.empathystar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable in-memory result. No Android nodes, credentials, network or content logging. */
final class ScreenReadResult {
    enum ConversationType { PRIVATE, GROUP }
    enum State {
        READY, PAUSED, NOT_AUTHORIZED, EMPTY_TREE, NOT_CHAT, NO_TEXT, NO_COMPOSER,
        NO_WINDOW, WINDOW_UNRESOLVED, UNSUPPORTED, READ_ERROR
    }

    static final class Message {
        final String text;
        final ChatSurfacePolicy.Bounds bounds;
        final int side;
        final String speakerName;
        Message(String text, ChatSurfacePolicy.Bounds bounds, int side) {
            this(text, bounds, side, "");
        }
        Message(String text, ChatSurfacePolicy.Bounds bounds, int side, String speakerName) {
            this.text = Objects.requireNonNull(text);
            this.bounds = Objects.requireNonNull(bounds);
            this.side = side;
            this.speakerName = speakerName == null || speakerName.length() > 80
                || ChatContentPolicy.sensitive(speakerName) ? "" : speakerName;
        }
    }

    static final class Geometry {
        final int width, height, composerTop, editorTop;
        final boolean usedBottomBarFallback;
        final List<ChatSurfacePolicy.Bounds> exclusions;
        Geometry(int width, int height, int composerTop, int editorTop, boolean fallback) {
            this(width, height, composerTop, editorTop, fallback, Collections.emptyList());
        }
        Geometry(int width, int height, int composerTop, int editorTop, boolean fallback,
                 List<ChatSurfacePolicy.Bounds> exclusions) {
            this.width = width; this.height = height;
            this.composerTop = composerTop; this.editorTop = editorTop;
            this.usedBottomBarFallback = fallback;
            this.exclusions = Collections.unmodifiableList(new ArrayList<>(exclusions));
        }
    }

    /** Fixed structural counts only; no text, node objects or window titles. */
    static final class Metrics {
        final int nodes, editable, windows, appWindows, focusedAppWindows, activeChildren, maxChildren;
        final boolean activeRootMatches;
        private final int[] messageStats, senderStats, composerStats;
        Metrics(int nodes, int editable, int windows, int appWindows, int focusedAppWindows,
                int activeChildren, int maxChildren, boolean activeRootMatches,
                int[] messageStats, int[] senderStats, int[] composerStats) {
            this.nodes = nodes; this.editable = editable; this.windows = windows;
            this.appWindows = appWindows; this.focusedAppWindows = focusedAppWindows;
            this.activeChildren = activeChildren; this.maxChildren = maxChildren;
            this.activeRootMatches = activeRootMatches;
            this.messageStats = copy(messageStats, 5);
            this.senderStats = copy(senderStats, 3);
            this.composerStats = copy(composerStats, 3);
        }
        private static int[] copy(int[] value, int size) {
            if (value == null || value.length != size) throw new IllegalArgumentException("INVALID_METRICS");
            return value.clone();
        }
        int[] senders() { return senderStats.clone(); }
        String composerDiagnostic() {
            return ChatSurfacePolicy.composerRecognitionDiagnostic(nodes, messageStats[0], messageStats[1],
                messageStats[2], messageStats[3], messageStats[4], composerStats[0], composerStats[1],
                composerStats[2], windows, appWindows, focusedAppWindows, activeRootMatches ? 1 : 0,
                activeChildren, maxChildren);
        }
        static Metrics empty() {
            return new Metrics(0, 0, 0, 0, 0, -1, -1, false, new int[5], new int[3], new int[3]);
        }
    }

    final State state;
    final ConversationType conversationType;
    final ChatSnapshot snapshot;
    final Geometry geometry;
    final Metrics metrics;
    final List<Message> messages;

    ScreenReadResult(State state, ChatSnapshot snapshot, Geometry geometry, Metrics metrics, List<Message> messages) {
        this(state, snapshot, geometry, metrics, messages, ConversationType.PRIVATE);
    }

    ScreenReadResult(State state, ChatSnapshot snapshot, Geometry geometry, Metrics metrics, List<Message> messages,
                     ConversationType conversationType) {
        this.conversationType = Objects.requireNonNull(conversationType);
        this.state = Objects.requireNonNull(state);
        this.metrics = Objects.requireNonNull(metrics);
        Objects.requireNonNull(messages);
        boolean surface = state == State.READY || state == State.NO_TEXT || state == State.NO_COMPOSER
            || state == State.NOT_CHAT || state == State.EMPTY_TREE;
        if (surface && (snapshot == null || snapshot.windowId < 0))
            throw new IllegalArgumentException("MISSING_ORIGIN");
        if ((state == State.READY || state == State.NO_TEXT) && geometry == null)
            throw new IllegalArgumentException("MISSING_GEOMETRY");
        if (state == State.READY ? messages.isEmpty() : !messages.isEmpty())
            throw new IllegalArgumentException("INVALID_MESSAGES_FOR_STATE");
        for (Message message : messages) Objects.requireNonNull(message);
        this.snapshot = snapshot; this.geometry = geometry;
        this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
    }

    static ScreenReadResult unavailable(State state, ChatSnapshot snapshot) {
        return new ScreenReadResult(state, snapshot, null, Metrics.empty(), Collections.emptyList());
    }
}
