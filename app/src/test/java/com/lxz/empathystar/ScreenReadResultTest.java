package com.lxz.empathystar;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class ScreenReadResultTest {
    private final ChatSnapshot origin = new ChatSnapshot(ChatSnapshot.QQ, 12, 7);
    private final ScreenReadResult.Geometry geometry = new ScreenReadResult.Geometry(1080, 2400, 2200, 2220, false);
    private ScreenReadResult.Message message() {
        return new ScreenReadResult.Message("synthetic-message", new ChatSurfacePolicy.Bounds(100, 200, 500, 300),
            ChatSurfacePolicy.MESSAGE_SIDE_INCOMING);
    }
    private ScreenReadResult ready(List<ScreenReadResult.Message> messages) {
        return new ScreenReadResult(ScreenReadResult.State.READY, origin, geometry,
            ScreenReadResult.Metrics.empty(), messages);
    }
    @Test public void readyCarriesPureOriginGeometryDirectionAndBounds() {
        ScreenReadResult result = ready(Collections.singletonList(message()));
        assertSame(origin, result.snapshot);
        assertEquals(2200, result.geometry.composerTop);
        assertEquals(500, result.messages.get(0).bounds.right);
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_INCOMING, result.messages.get(0).side);
        assertTrue(result.snapshot.matches(ChatSnapshot.QQ, 12, 7));
        assertFalse(result.snapshot.matches(ChatSnapshot.WECHAT, 12, 7));
        assertFalse(result.snapshot.matches(ChatSnapshot.QQ, 13, 7));
        assertFalse(result.snapshot.matches(ChatSnapshot.QQ, 12, 8));
    }
    @Test public void messagesAreDefensivelyCopiedAndUnmodifiable() {
        List<ScreenReadResult.Message> list = new ArrayList<>();
        list.add(message());
        ScreenReadResult result = ready(list);
        list.clear();
        assertEquals(1, result.messages.size());
        assertThrows(UnsupportedOperationException.class, () -> result.messages.clear());
    }
    @Test public void countersAreCopiedInBothDirections() {
        int[] senders = {2, 3, 4};
        ScreenReadResult.Metrics metrics = new ScreenReadResult.Metrics(10, 1, 3, 1, 1, 2, 2, true,
            new int[5], senders, new int[3]);
        senders[0] = 99;
        assertEquals(2, metrics.senders()[0]);
        metrics.senders()[1] = 99;
        assertEquals(3, metrics.senders()[1]);
    }
    @Test public void allFailureStatesRejectMessages() {
        for (ScreenReadResult.State state : ScreenReadResult.State.values()) {
            if (state == ScreenReadResult.State.READY) continue;
            assertThrows(IllegalArgumentException.class, () -> new ScreenReadResult(state, origin, geometry,
                ScreenReadResult.Metrics.empty(), Collections.singletonList(message())));
        }
    }
    @Test public void readyRequiresMessagesOriginAndGeometry() {
        assertThrows(IllegalArgumentException.class, () -> ready(Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> new ScreenReadResult(ScreenReadResult.State.READY,
            null, geometry, ScreenReadResult.Metrics.empty(), Collections.singletonList(message())));
        assertThrows(IllegalArgumentException.class, () -> new ScreenReadResult(ScreenReadResult.State.READY,
            origin, null, ScreenReadResult.Metrics.empty(), Collections.singletonList(message())));
    }
    @Test public void validChatWithoutTextIsNotAnEmptyTree() {
        ScreenReadResult noText = new ScreenReadResult(ScreenReadResult.State.NO_TEXT, origin, geometry,
            ScreenReadResult.Metrics.empty(), Collections.emptyList());
        ScreenReadResult empty = ScreenReadResult.unavailable(ScreenReadResult.State.EMPTY_TREE, origin);
        assertNotEquals(noText.state, empty.state);
        assertNotNull(noText.geometry);
        assertNull(empty.geometry);
        assertTrue(noText.messages.isEmpty());
    }
    @Test public void structuredStatesRequireAValidWindow() {
        for (ScreenReadResult.State state : new ScreenReadResult.State[]{ScreenReadResult.State.EMPTY_TREE,
            ScreenReadResult.State.NOT_CHAT, ScreenReadResult.State.NO_COMPOSER}) {
            assertThrows(IllegalArgumentException.class, () -> ScreenReadResult.unavailable(state, null));
            assertThrows(IllegalArgumentException.class, () -> ScreenReadResult.unavailable(state,
                new ChatSnapshot(ChatSnapshot.QQ, -1, 7)));
        }
    }
    @Test public void failuresRemainDistinctAndContainNoMessages() {
        for (ScreenReadResult.State state : new ScreenReadResult.State[]{ScreenReadResult.State.PAUSED,
            ScreenReadResult.State.NOT_AUTHORIZED, ScreenReadResult.State.NO_WINDOW,
            ScreenReadResult.State.WINDOW_UNRESOLVED, ScreenReadResult.State.UNSUPPORTED,
            ScreenReadResult.State.READ_ERROR}) {
            ScreenReadResult result = ScreenReadResult.unavailable(state, null);
            assertSame(state, result.state);
            assertTrue(result.messages.isEmpty());
        }
    }
    @Test public void diagnosticHasOnlyStructureNotMessageContent() {
        ScreenReadResult result = ready(Collections.singletonList(message()));
        assertFalse(result.metrics.composerDiagnostic().contains(result.messages.get(0).text));
        assertFalse(result.toString().contains(result.messages.get(0).text));
    }
    @Test public void malformedMetricArraysAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenReadResult.Metrics(0, 0, 0, 0, 0, 0, 0,
            false, new int[4], new int[3], new int[3]));
    }
    @Test public void aFakeReaderCanReplaceAndroidWithoutNodesOrNetwork() {
        final ScreenReadResult expected = ready(Collections.singletonList(message()));
        ScreenReader fake = new ScreenReader() {
            @Override public void observeWindow(int windowId, String pkg) {}
            @Override public ScreenReadResult read(long revision) { return expected; }
            @Override public boolean isCurrent(ChatSnapshot snapshot, long revision) {
                return snapshot != null && snapshot.matches(origin.packageName, origin.windowId, revision);
            }
            @Override public FillStatus fillEmptyDraft(ChatSnapshot snapshot, long revision, String reply) {
                if (reply == null || reply.isEmpty()) return FillStatus.NO_REPLY;
                return isCurrent(snapshot, revision) ? FillStatus.INSERTED : FillStatus.STALE_SNAPSHOT;
            }
        };
        assertSame(expected, fake.read(7));
        assertEquals(ScreenReader.FillStatus.STALE_SNAPSHOT, fake.fillEmptyDraft(origin, 8, "synthetic-reply"));
        assertEquals(ScreenReader.FillStatus.NO_REPLY, fake.fillEmptyDraft(origin, 7, ""));
        assertEquals(ScreenReader.FillStatus.INSERTED, fake.fillEmptyDraft(origin, 7, "synthetic-reply"));
    }
}
