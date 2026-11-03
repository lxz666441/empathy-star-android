package com.lxz.empathystar;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ChatSurfacePolicyTest {
    @Test public void dockedKeyboardUsesTheVisibleChatViewportForComposerDetection() {
        assertEquals(1500, ChatSurfacePolicy.visibleWindowBottom(2400, 2400, 1500));
        assertEquals(1450, ChatSurfacePolicy.visibleWindowBottom(2400, 1450, 1500));
        assertEquals(2400, ChatSurfacePolicy.visibleWindowBottom(2400, 2400, 0));
        assertEquals(2400, ChatSurfacePolicy.visibleWindowBottom(2400, 0, 0));
    }
    @Test public void markerDiagnosticShowsDetectedDisplayedAndSkippedCounts() {
        assertEquals("已识别 5 条可见文字消息，显示 3 个分析星标，跳过 2 条",
            ChatSurfacePolicy.markerSummaryDiagnostic(5, 3, 2));
        assertEquals("已识别 5 条可见文字消息，显示 5 个分析星标，跳过 0 条",
            ChatSurfacePolicy.markerSummaryDiagnostic(5, 5, 0));
    }

    @Test public void composerFailureDiagnosticContainsOnlyStructureCounts() {
        assertEquals("聊天页诊断：节点=120，非空文本叶=18，消息文本ID=6，消息行祖先=6，"
                + "消息列表祖先=6，完整消息结构=6，底部编辑框=0，按钮=0，导航标签=0，"
                + "窗口总数=3，微信窗口数=1，聚焦微信窗口数=1，活动根微信=1，活动根子节点=12，"
                + "微信根最大子节点=12；未显示星标",
            ChatSurfacePolicy.composerRecognitionDiagnostic(
                120, 18, 6, 6, 6, 6, 0, 0, 0, 3, 1, 1, 1, 12, 12));
    }

    @Test public void emptyTreeDiagnosticSaysPreviousMarkersAreTemporarilyRetained() {
        String structural = ChatSurfacePolicy.composerRecognitionDiagnostic(
            1, 0, 0, 0, 0, 0, 0, 0, 0, 4, 1, 1, 1, 0, 0);
        assertEquals("聊天页诊断：节点=1，非空文本叶=0，消息文本ID=0，消息行祖先=0，"
                + "消息列表祖先=0，完整消息结构=0，底部编辑框=0，按钮=0，导航标签=0，"
                + "窗口总数=4，微信窗口数=1，聚焦微信窗口数=1，活动根微信=1，活动根子节点=0，"
                + "微信根最大子节点=0；无障碍树暂空，上一帧星标短暂保留并等待重扫",
            ChatSurfacePolicy.withTemporaryOverlayRetention(structural));
        assertEquals("", ChatSurfacePolicy.withTemporaryOverlayRetention(null));
        assertEquals("聊天页诊断：节点=1，非空文本叶=0，消息文本ID=0，消息行祖先=0，"
                + "消息列表祖先=0，完整消息结构=0，底部编辑框=0，按钮=0，导航标签=0，"
                + "窗口总数=4，微信窗口数=1，聚焦微信窗口数=1，活动根微信=1，活动根子节点=0，"
                + "微信根最大子节点=0；无障碍树恢复超时，已清除上一帧星标",
            ChatSurfacePolicy.withExpiredOverlayRetention(structural));
    }

    @Test public void everyVisibleMessageKeepsItsOwnAnalysisTargetIncludingSmallTalk() {
        assertTrue(ChatSurfacePolicy.allTargets(Collections.emptyList()).isEmpty());
        List<String> one = ChatSurfacePolicy.allTargets(Collections.singletonList("哈哈，早上好"));
        assertEquals(Collections.singletonList("哈哈，早上好"), one);
        List<String> candidates = Arrays.asList("普通寒暄", "哈哈哈哈", "今天挺好", "你觉得呢", "普通寒暄");
        List<String> many = ChatSurfacePolicy.allTargets(candidates);
        assertEquals(Arrays.asList("普通寒暄", "哈哈哈哈", "今天挺好", "你觉得呢", "普通寒暄"), many);
        assertNotSame(candidates, many);
    }

    @Test public void analysisContextContainsOnlyUpToEightEarlierMessages() {
        List<String> conversation = Arrays.asList(
            "m0", "m1", "m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9", "selected", "after");
        assertEquals(Arrays.asList("m2", "m3", "m4", "m5", "m6", "m7", "m8", "m9"),
            ChatSurfacePolicy.precedingMessages(conversation, 10, 8));
        assertEquals(Arrays.asList("m0", "m1"), ChatSurfacePolicy.precedingMessages(conversation, 2, 8));
        assertTrue(ChatSurfacePolicy.precedingMessages(conversation, 0, 8).isEmpty());
        assertTrue(ChatSurfacePolicy.precedingMessages(conversation, 2, 0).isEmpty());
    }

    @Test public void modelPromptLabelsPrecedingContextAndSeparatesSelectedTarget() {
        String prompt = ChatSurfacePolicy.analysisUserPrompt(
            "selected-target", Arrays.asList("earlier-one", "earlier-two"));
        assertTrue(prompt.startsWith("所选消息之前的可见文字"));
        assertTrue(prompt.contains("earlier-one\nearlier-two"));
        assertTrue(prompt.endsWith("请只分析这条所选消息：selected-target"));
        assertFalse(prompt.contains("later-message"));
        assertTrue(ChatSurfacePolicy.analysisUserPrompt("target", Collections.emptyList()).contains("（无）"));
    }

    @Test public void composerMustHaveEditorAndComposerButtons() {
        assertFalse(ChatSurfacePolicy.hasChatComposer(0, 3, 0));
        assertFalse(ChatSurfacePolicy.hasChatComposer(1, 1, 0));
        assertTrue(ChatSurfacePolicy.hasChatComposer(1, 2, 0));
    }

    @Test public void anyBottomNavigationLabelRejectsChatSurface() {
        assertTrue(ChatSurfacePolicy.hasChatComposer(1, 3, 0));
        assertFalse(ChatSurfacePolicy.hasChatComposer(1, 3, 1));
        assertFalse(ChatSurfacePolicy.hasChatComposer(1, 3, 2));
        assertFalse(ChatSurfacePolicy.hasChatComposer(1, 3, 4));
    }

    @Test public void onlyWechatMessageTextInsideRowsAndListCanReceiveStars() {
        assertTrue(ChatSurfacePolicy.isWeChatMessageText(
            "android.widget.TextView", "com.tencent.mm:id/bkl", true, true));
        assertFalse(ChatSurfacePolicy.isWeChatMessageText(
            "android.widget.TextView", "com.tencent.mm:id/a48", true, true));
        assertFalse(ChatSurfacePolicy.isWeChatMessageText(
            "android.widget.TextView", "com.tencent.mm:id/bkl", false, true));
        assertFalse(ChatSurfacePolicy.isWeChatMessageText(
            "android.widget.TextView", "com.tencent.mm:id/bkl", true, false));
        assertFalse(ChatSurfacePolicy.isWeChatMessageText(
            "android.widget.EditText", "com.tencent.mm:id/bkl", true, true));
    }

    @Test public void messageSideRequiresOneVerticallyAlignedAvatarOutsideBubble() {
        ChatSurfacePolicy.Bounds bubble = new ChatSurfacePolicy.Bounds(180, 400, 650, 470);
        ChatSurfacePolicy.Bounds leftAvatar = new ChatSurfacePolicy.Bounds(24, 410, 104, 490);
        ChatSurfacePolicy.Bounds rightAvatar = new ChatSurfacePolicy.Bounds(976, 410, 1056, 490);
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_INCOMING,
            ChatSurfacePolicy.classifyMessageSide(bubble, java.util.Collections.singletonList(leftAvatar)));
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_OUTGOING,
            ChatSurfacePolicy.classifyMessageSide(bubble, java.util.Collections.singletonList(rightAvatar)));
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_UNKNOWN,
            ChatSurfacePolicy.classifyMessageSide(bubble, java.util.Collections.emptyList()));
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_UNKNOWN,
            ChatSurfacePolicy.classifyMessageSide(bubble, java.util.Arrays.asList(leftAvatar, rightAvatar)));
        assertEquals(ChatSurfacePolicy.MESSAGE_SIDE_UNKNOWN,
            ChatSurfacePolicy.classifyMessageSide(bubble,
                java.util.Collections.singletonList(new ChatSurfacePolicy.Bounds(190, 410, 270, 490))));
    }

    @Test public void screenPositionIsTranslatedByMeasuredOverlayWindowOrigin() {
        assertEquals(2082, ChatSurfacePolicy.layoutCoordinateForScreen(2163, 81));
        assertEquals(2163, ChatSurfacePolicy.layoutCoordinateForScreen(2163, 0));
    }

    @Test public void overlaysRequireConsentAndWechatInForeground() {
        assertTrue(ChatSurfacePolicy.shouldKeepOverlays(true, true));
        assertFalse(ChatSurfacePolicy.shouldKeepOverlays(false, true));
        assertFalse(ChatSurfacePolicy.shouldKeepOverlays(true, false));
        assertFalse(ChatSurfacePolicy.shouldKeepOverlays(false, false));
    }

    @Test public void missingRootDuringWindowTransitionImmediatelyClearsOldOverlays() {
        assertFalse(ChatSurfacePolicy.shouldKeepOverlaysAfterMissingRoot(true));
        assertTrue(ChatSurfacePolicy.shouldKeepOverlaysAfterMissingRoot(false));
    }

    @Test public void emptyTreeRetainsOnlyAttachedMarkersOutsideWindowTransitions() {
        assertTrue(ChatSurfacePolicy.shouldRetainPreviousMarkersOnEmptyTree(true, false, true));
        assertFalse(ChatSurfacePolicy.shouldRetainPreviousMarkersOnEmptyTree(true, true, true));
        assertFalse(ChatSurfacePolicy.shouldRetainPreviousMarkersOnEmptyTree(true, false, false));
        assertFalse(ChatSurfacePolicy.shouldRetainPreviousMarkersOnEmptyTree(false, false, true));
    }

    @Test public void staleSuggestionIsInvalidatedAfterWechatUiChanges() {
        assertTrue(ChatSurfacePolicy.isSameWeChatUiRevision(17L, 17L));
        assertFalse(ChatSurfacePolicy.isSameWeChatUiRevision(17L, 18L));
    }

    @Test public void staleOverlayGeometryCannotCommitAfterLayoutOrSurfaceChanges() {
        assertTrue(ChatSurfacePolicy.mayCommitOverlayGeometry(4L, 4L, 9L, 9L, true, true));
        assertFalse(ChatSurfacePolicy.mayCommitOverlayGeometry(3L, 4L, 9L, 9L, true, true));
        assertFalse(ChatSurfacePolicy.mayCommitOverlayGeometry(4L, 4L, 9L, 10L, true, true));
        assertFalse(ChatSurfacePolicy.mayCommitOverlayGeometry(4L, 4L, 9L, 9L, false, true));
        assertFalse(ChatSurfacePolicy.mayCommitOverlayGeometry(4L, 4L, 9L, 9L, true, false));
    }

    @Test public void staleStarPositionCorrectionIsRejectedAfterLayoutOrChatChanges() {
        assertTrue(ChatSurfacePolicy.mayApplyOverlayCorrection(5L, 5L, 11L, 11L, true, true));
        assertFalse(ChatSurfacePolicy.mayApplyOverlayCorrection(4L, 5L, 11L, 11L, true, true));
        assertFalse(ChatSurfacePolicy.mayApplyOverlayCorrection(5L, 5L, 11L, 12L, true, true));
        assertFalse(ChatSurfacePolicy.mayApplyOverlayCorrection(5L, 5L, 11L, 11L, false, true));
        assertFalse(ChatSurfacePolicy.mayApplyOverlayCorrection(5L, 5L, 11L, 11L, true, false));
    }

    @Test public void ocrIsManualChatOnlyAndDoesNotFallbackOnSensitiveOrReadableText() {
        assertTrue(ChatSurfacePolicy.shouldOfferOcr(34, true, 0, 0));
        assertFalse(ChatSurfacePolicy.shouldOfferOcr(33, true, 0, 0));
        assertFalse(ChatSurfacePolicy.shouldOfferOcr(34, false, 0, 0));
        assertFalse(ChatSurfacePolicy.shouldOfferOcr(34, true, 1, 0));
        assertFalse(ChatSurfacePolicy.shouldOfferOcr(34, true, 0, 1));
    }

    @Test public void modelUploadRequiresSeparateConsentAndNonemptyKey() {
        assertFalse(ChatSurfacePolicy.maySendModelData(false, "secret"));
        assertFalse(ChatSurfacePolicy.maySendModelData(true, "  "));
        assertFalse(ChatSurfacePolicy.maySendModelData(true, null));
        assertTrue(ChatSurfacePolicy.maySendModelData(true, "secret"));
    }

    @Test public void storedApiKeyIsNotDecryptedBeforeModelDataConsent() {
        assertFalse(ChatSurfacePolicy.shouldDecryptStoredApiKey(false, false));
        assertFalse(ChatSurfacePolicy.shouldDecryptStoredApiKey(false, true));
        assertFalse(ChatSurfacePolicy.shouldDecryptStoredApiKey(true, false));
        assertTrue(ChatSurfacePolicy.shouldDecryptStoredApiKey(true, true));
    }

    @Test public void analysisRequestRequiresConsentKeyAndUnchangedWechatUi() {
        assertTrue(ChatSurfacePolicy.maySendAnalysisRequest(true, "secret", 12L, 12L));
        assertFalse(ChatSurfacePolicy.maySendAnalysisRequest(false, "secret", 12L, 12L));
        assertFalse(ChatSurfacePolicy.maySendAnalysisRequest(true, "  ", 12L, 12L));
        assertFalse(ChatSurfacePolicy.maySendAnalysisRequest(true, "secret", 12L, 13L));
    }

    @Test public void cancelledAnalysisGenerationCannotContinue() {
        assertTrue(ChatSurfacePolicy.mayContinueAnalysisRequest(4, 4, true, "secret", 12L, 12L));
        assertFalse(ChatSurfacePolicy.mayContinueAnalysisRequest(4, 5, true, "secret", 12L, 12L));
        assertFalse(ChatSurfacePolicy.mayContinueAnalysisRequest(4, 4, false, "secret", 12L, 12L));
        assertFalse(ChatSurfacePolicy.mayContinueAnalysisRequest(4, 4, true, "secret", 12L, 13L));
    }

    @Test public void analysisResponseFieldsMustBeNonemptyAndWithinPromptLimits() {
        assertTrue(ChatSurfacePolicy.isValidAnalysisResponse(repeat('i', 80), repeat('r', 300)));
        assertFalse(ChatSurfacePolicy.isValidAnalysisResponse(repeat('i', 81), "简短回复"));
        assertFalse(ChatSurfacePolicy.isValidAnalysisResponse("简短解读", repeat('r', 301)));
        assertFalse(ChatSurfacePolicy.isValidAnalysisResponse("  ", "简短回复"));
        assertFalse(ChatSurfacePolicy.isValidAnalysisResponse("简短解读", null));
    }

    @Test public void modelEndpointMustBeHttpsWithAHostAndNoEmbeddedCredentials() {
        assertTrue(ChatSurfacePolicy.isSecureModelEndpoint("https://api.example.test/v1/chat"));
        assertFalse(ChatSurfacePolicy.isSecureModelEndpoint("http://api.example.test/v1/chat"));
        assertFalse(ChatSurfacePolicy.isSecureModelEndpoint("https:///v1/chat"));
        assertFalse(ChatSurfacePolicy.isSecureModelEndpoint("https://user:password@example.test/api"));
        assertFalse(ChatSurfacePolicy.isSecureModelEndpoint("not a url"));
        assertFalse(ChatSurfacePolicy.isSecureModelEndpoint(null));
    }

    @Test public void starPositionStaysAtExactRightBottomAndAboveComposer() {
        ChatSurfacePolicy.Position position = ChatSurfacePolicy.positionStar(
            900, 1900, 1080, 2400, 2050, 36, 12);
        assertNotNull(position);
        assertEquals(912, position.x);
        assertEquals(1864, position.y);
    }

    @Test public void unsafePositionIsRejectedInsteadOfClampedAwayFromMessage() {
        ChatSurfacePolicy.Position upper = ChatSurfacePolicy.positionStar(
            300, 55, 1080, 2400, 2200, 96, 12);
        ChatSurfacePolicy.Position lower = ChatSurfacePolicy.positionStar(
            300, 2200, 1080, 2400, 2050, 96, 12);
        assertNull(upper);
        assertNull(lower);
        assertNull(ChatSurfacePolicy.positionStar(1040, 800, 1080, 2400, 2050, 36, 12));
    }

    @Test public void noStarWhenComposerLeavesNoSafeSpace() {
        assertNull(ChatSurfacePolicy.positionStar(100, 100, 300, 600, 50, 96, 12));
    }

    @Test public void multipleStarsAvoidMessageTextOtherStarsAndComposer() {
        List<ChatSurfacePolicy.Bounds> messages = Arrays.asList(
            new ChatSurfacePolicy.Bounds(100, 500, 300, 560),
            new ChatSurfacePolicy.Bounds(100, 552, 300, 612),
            new ChatSurfacePolicy.Bounds(580, 650, 980, 710),
            new ChatSurfacePolicy.Bounds(100, 740, 400, 800),
            new ChatSurfacePolicy.Bounds(580, 830, 1040, 890));
        int size = 36;
        int margin = 12;
        int composerTop = 2200;

        List<ChatSurfacePolicy.Position> positions = ChatSurfacePolicy.positionStars(
            messages, 1080, 2400, composerTop, size, margin);

        assertEquals(messages.size(), positions.size());
        assertNotNull(positions.get(0));
        assertNull(positions.get(1)); // Collision: do not move to the avatar/another row.
        assertNull(positions.get(4)); // Right edge: do not switch to the left side.
        for (int index = 0; index < positions.size(); index++) {
            ChatSurfacePolicy.Position position = positions.get(index);
            if (position == null) continue;
            assertEquals(messages.get(index).right + margin, position.x);
            assertEquals(messages.get(index).bottom - size, position.y);
            ChatSurfacePolicy.Bounds marker = new ChatSurfacePolicy.Bounds(
                position.x, position.y, position.x + size, position.y + size);
            assertTrue(position.x >= margin);
            assertTrue(position.x + size <= 1080 - margin);
            assertTrue(position.y >= margin);
            assertTrue(position.y + size + margin <= composerTop);
            for (ChatSurfacePolicy.Bounds message : messages) assertFalse(marker.intersects(message));
            for (int previous = 0; previous < index; previous++) {
                ChatSurfacePolicy.Position previousPosition = positions.get(previous);
                if (previousPosition == null) continue;
                ChatSurfacePolicy.Bounds previousMarker = new ChatSurfacePolicy.Bounds(
                    previousPosition.x, previousPosition.y,
                    previousPosition.x + size, previousPosition.y + size);
                assertFalse(marker.expand(margin).intersects(previousMarker.expand(margin)));
            }
        }
    }

    @Test public void multipleStarPositionsFailClosedWhenComposerHasNoSafeSpace() {
        List<ChatSurfacePolicy.Position> positions = ChatSurfacePolicy.positionStars(
            Collections.singletonList(new ChatSurfacePolicy.Bounds(40, 100, 160, 140)),
            300, 600, 50, 36, 12);
        assertEquals(1, positions.size());
        assertNull(positions.get(0));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
