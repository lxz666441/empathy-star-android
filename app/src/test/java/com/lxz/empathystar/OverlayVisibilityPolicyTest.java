package com.lxz.empathystar;

import org.junit.Test;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class OverlayVisibilityPolicyTest {
    @Test public void summaryStaysInSameUpperLeftHeaderSlotWhenMessagesScrollOrKeyboardOpens() {
        for (int chatTop : new int[]{235, 244, 500, 900}) {
            for (int composerTop : new int[]{1230, 2136}) {
                ChatSurfacePolicy.Position position = OverlayVisibilityPolicy.fixedSummaryPosition(
                    1080, composerTop, 144, 78, 312, 105, chatTop);
                assertNotNull(position);
                assertEquals(312, position.x);
                assertEquals(105, position.y);
                assertTrue(position.y + 78 <= chatTop);
            }
        }
        assertNull(OverlayVisibilityPolicy.fixedSummaryPosition(400, 2136, 144, 78, 312, 105, 235));
        assertNull(OverlayVisibilityPolicy.fixedSummaryPosition(1080, 2136, 144, 78, 312, 105, 160));
    }
    @Test public void firstMessageUsesMeasuredListTopInsteadOfScreenPercentage() {
        ChatSurfacePolicy.Bounds viewport = new ChatSurfacePolicy.Bounds(0, 235, 1080, 2136);
        ChatSurfacePolicy.Bounds first = new ChatSurfacePolicy.Bounds(140, 235, 900, 510);
        assertTrue(first.top < 2319 * .11);
        assertTrue(ChatSurfacePolicy.insideMessageViewport(first, viewport, 1080, 2319));
        assertFalse(ChatSurfacePolicy.insideMessageViewport(
            new ChatSurfacePolicy.Bounds(140, 200, 900, 510), viewport, 1080, 2319));
        assertFalse(ChatSurfacePolicy.insideMessageViewport(
            new ChatSurfacePolicy.Bounds(140, 2050, 900, 2200), viewport, 1080, 2319));
    }

    @Test public void compactSummaryRemainsAvailableInDenseMessageAndAvatarGutters() {
        List<ChatSurfacePolicy.Bounds> excluded = new ArrayList<>();
        for (int top = 250; top < 2136; top += 234) {
            excluded.add(new ChatSurfacePolicy.Bounds(140, top, 928, Math.min(2136, top + 234)));
            excluded.add(new ChatSurfacePolicy.Bounds(32, top + 21, 140, top + 129));
            excluded.add(new ChatSurfacePolicy.Bounds(940, top + 198, 976, top + 234));
        }
        assertNull(OverlayVisibilityPolicy.summaryPosition(excluded, 1080, 2319, 2136, 144, 78, 12, 250));
        ChatSurfacePolicy.Position compact = OverlayVisibilityPolicy.summaryPosition(
            excluded, 1080, 2319, 2136, 72, 72, 12, 250, true);
        assertNotNull(compact);
        ChatSurfacePolicy.Bounds button = new ChatSurfacePolicy.Bounds(compact.x, compact.y, compact.x + 72, compact.y + 72);
        for (ChatSurfacePolicy.Bounds area : excluded) assertFalse(button.expand(12).intersects(area));
        assertTrue(button.bottom + 12 <= 2136);
    }

    @Test public void compactSummaryAvoidsJumpToLatestControlInRightGutter() {
        List<ChatSurfacePolicy.Bounds> excluded = Arrays.asList(
            new ChatSurfacePolicy.Bounds(140, 235, 928, 2136),
            new ChatSurfacePolicy.Bounds(32, 1950, 140, 2058),
            new ChatSurfacePolicy.Bounds(980, 2028, 1053, 2109));
        ChatSurfacePolicy.Position position = OverlayVisibilityPolicy.summaryPosition(
            excluded, 1080, 2319, 2136, 72, 72, 12, 235, true);
        assertNotNull(position);
        ChatSurfacePolicy.Bounds button = new ChatSurfacePolicy.Bounds(position.x, position.y, position.x + 72, position.y + 72);
        for (ChatSurfacePolicy.Bounds area : excluded) assertFalse(button.expand(12).intersects(area));
    }

    @Test public void overlayWindowListEventsWithUnknownIdDoNotPretendToSwitchChats() {
        assertFalse(OverlayVisibilityPolicy.differentKnownWindow(37, -1));
        assertFalse(OverlayVisibilityPolicy.differentKnownWindow(-1, 37));
        assertFalse(OverlayVisibilityPolicy.differentKnownWindow(37, 37));
        assertTrue(OverlayVisibilityPolicy.differentKnownWindow(37, 38));
    }
    @Test public void screenMustBeInteractiveAndBothLockStatesClear() {
        for (int mask = 0; mask < 8; mask++) {
            boolean interactive = (mask & 1) != 0, keyguard = (mask & 2) != 0, device = (mask & 4) != 0;
            assertEquals(mask == 1, OverlayVisibilityPolicy.screenAvailable(interactive, keyguard, device));
        }
    }

    @Test public void scrollWaitsUntilStableAndDoesNotUseWallClock() {
        assertEquals(0, OverlayVisibilityPolicy.scrollDelay(1000, -1));
        assertEquals(120, OverlayVisibilityPolicy.scrollDelay(1000, 1000));
        assertEquals(40, OverlayVisibilityPolicy.scrollDelay(1080, 1000));
        assertEquals(0, OverlayVisibilityPolicy.scrollDelay(1120, 1000));
        assertEquals(0, OverlayVisibilityPolicy.scrollDelay(1400, 1000));
    }

    @Test public void adjacentWiderRowDoesNotHideShortMessageStarThroughVirtualPadding() {
        List<ChatSurfacePolicy.Bounds> rows = Arrays.asList(
            new ChatSurfacePolicy.Bounds(140, 321, 889, 489),
            new ChatSurfacePolicy.Bounds(140, 489, 928, 723));
        List<ChatSurfacePolicy.Position> positions = ChatSurfacePolicy.positionStars(rows, rows,
            1080, 2319, 2136, 36, 12);
        assertNotNull(positions.get(0));
        assertNotNull(positions.get(1));
        for (ChatSurfacePolicy.Position position : positions) {
            ChatSurfacePolicy.Bounds marker = new ChatSurfacePolicy.Bounds(position.x, position.y,
                position.x + 36, position.y + 36);
            for (ChatSurfacePolicy.Bounds row : rows) assertFalse(marker.intersects(row));
        }
    }

    @Test public void avatarOrOtherBubbleBlocksMarkerWithoutMovingIt() {
        ChatSurfacePolicy.Bounds message = new ChatSurfacePolicy.Bounds(100, 300, 500, 400);
        List<ChatSurfacePolicy.Position> positions = ChatSurfacePolicy.positionStars(
            Collections.singletonList(message),
            Arrays.asList(message, new ChatSurfacePolicy.Bounds(510, 360, 590, 440)),
            1080, 2400, 2200, 36, 12);
        assertNull(positions.get(0));
    }

    @Test public void markerOutsideViewportIsNotShiftedUpToComposer() {
        assertNull(ChatSurfacePolicy.positionStars(Collections.singletonList(
            new ChatSurfacePolicy.Bounds(100, 2000, 600, 2190)), 1080, 2400, 2180, 36, 12).get(0));
    }

    @Test public void summaryFitsOnlyInActualWhitespaceAboveComposer() {
        List<ChatSurfacePolicy.Bounds> excluded = Arrays.asList(
            new ChatSurfacePolicy.Bounds(100, 400, 900, 800),
            new ChatSurfacePolicy.Bounds(900, 1600, 1080, 2200));
        ChatSurfacePolicy.Position position = OverlayVisibilityPolicy.summaryPosition(
            excluded, 1080, 2400, 2200, 144, 78, 12, 400);
        assertNotNull(position);
        ChatSurfacePolicy.Bounds button = new ChatSurfacePolicy.Bounds(position.x, position.y,
            position.x + 144, position.y + 78);
        assertTrue(button.top >= 400);
        assertTrue(button.bottom + 12 <= 2200);
        for (ChatSurfacePolicy.Bounds area : excluded) assertFalse(button.expand(12).intersects(area));
    }

    @Test public void summaryHidesWhenNoWhitespaceInsteadOfCoveringMessages() {
        assertNull(OverlayVisibilityPolicy.summaryPosition(Collections.singletonList(
            new ChatSurfacePolicy.Bounds(0, 200, 1080, 2200)),
            1080, 2400, 2200, 144, 78, 12, 200));
    }

    @Test public void summaryDoesNotFallBackIntoHeaderOrKeyboard() {
        assertNull(OverlayVisibilityPolicy.summaryPosition(Collections.emptyList(),
            1080, 2400, 350, 144, 78, 12, 300));
    }

    @Test public void geometryDefensivelyCopiesExclusionList() {
        List<ChatSurfacePolicy.Bounds> list = new ArrayList<>();
        list.add(new ChatSurfacePolicy.Bounds(0, 0, 100, 100));
        ScreenReadResult.Geometry geometry = new ScreenReadResult.Geometry(1080, 2400, 2200, 2200, false, list);
        list.clear();
        assertEquals(1, geometry.exclusions.size());
        try { geometry.exclusions.clear(); fail("immutable"); }
        catch (UnsupportedOperationException expected) { }
    }
}
