package com.lxz.empathystar;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure decision and geometry rules shared by the accessibility service and tests. */
final class ChatSurfacePolicy {
    static final int MESSAGE_SIDE_UNKNOWN = 0;
    static final int MESSAGE_SIDE_INCOMING = 1;
    static final int MESSAGE_SIDE_OUTGOING = 2;

    static int visibleWindowBottom(int screenBottom, int rootBottom, int keyboardTop) {
        int bottom = screenBottom;
        if (rootBottom > 0) bottom = Math.min(bottom, rootBottom);
        if (keyboardTop > 0) bottom = Math.min(bottom, keyboardTop);
        return bottom;
    }

    static boolean insideMessageViewport(Bounds message, Bounds viewport, int width, int height) {
        return message != null && viewport != null && viewport.width() > 0 && viewport.height() > 0
            && message.width() > 8 && message.height() > 8
            && message.left >= Math.max(0, viewport.left) && message.right <= Math.min(width, viewport.right)
            && message.top >= Math.max(0, viewport.top) && message.bottom <= Math.min(height, viewport.bottom);
    }

    static int classifyMessageSide(Bounds message, List<Bounds> avatarCandidates) {
        if (message == null || avatarCandidates == null) return MESSAGE_SIDE_UNKNOWN;
        int side = MESSAGE_SIDE_UNKNOWN;
        for (Bounds avatar : avatarCandidates) {
            if (avatar == null || avatar.width() < 1 || avatar.height() < 1) continue;
            boolean verticallyAligned = avatar.bottom > message.top && avatar.top < message.bottom;
            if (!verticallyAligned) continue;
            int candidateSide = avatar.right <= message.left ? MESSAGE_SIDE_INCOMING
                : avatar.left >= message.right ? MESSAGE_SIDE_OUTGOING : MESSAGE_SIDE_UNKNOWN;
            if (candidateSide == MESSAGE_SIDE_UNKNOWN || (side != MESSAGE_SIDE_UNKNOWN && side != candidateSide)) {
                return MESSAGE_SIDE_UNKNOWN;
            }
            side = candidateSide;
        }
        return side;
    }

    static String counterpartyMarkerDiagnostic(int incoming, int outgoing, int unknown,
                                                int displayed, int skipped) {
        return "方向识别：对方=" + incoming + "，自己=" + outgoing + "，不确定=" + unknown
            + "；对方消息星标=" + displayed + "，因位置跳过=" + skipped;
    }

    static <T> List<T> allTargets(List<T> candidates) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(candidates);
    }

    static String markerSummaryDiagnostic(int recognizedMessages, int displayedMarkers, int skippedMessages) {
        return "已识别 " + recognizedMessages + " 条可见文字消息，显示 " + displayedMarkers
            + " 个分析星标，跳过 " + skippedMessages + " 条";
    }

    static String composerRecognitionDiagnostic(int nodes, int textLeaves, int messageTextIds,
                                                 int messageRowAncestors, int messageListAncestors,
                                                 int completeMessageShapes, int editors,
                                                 int bottomButtons, int bottomNavigationLabels,
                                                 int totalWindows, int weChatWindows,
                                                 int focusedWeChatWindows, int activeRootIsWeChat,
                                                 int activeRootChildren, int maxWeChatRootChildren) {
        return "聊天页诊断：节点=" + nodes + "，非空文本叶=" + textLeaves
            + "，消息文本ID=" + messageTextIds + "，消息行祖先=" + messageRowAncestors
            + "，消息列表祖先=" + messageListAncestors + "，完整消息结构=" + completeMessageShapes
            + "，底部编辑框=" + editors + "，按钮=" + bottomButtons
            + "，导航标签=" + bottomNavigationLabels
            + "，窗口总数=" + totalWindows + "，微信窗口数=" + weChatWindows
            + "，聚焦微信窗口数=" + focusedWeChatWindows + "，活动根微信=" + activeRootIsWeChat
            + "，活动根子节点=" + activeRootChildren + "，微信根最大子节点=" + maxWeChatRootChildren
            + "；未显示星标";
    }

    static String withTemporaryOverlayRetention(String diagnostic) {
        if (diagnostic == null) return "";
        return diagnostic.replace("；未显示星标", "；无障碍树暂空，上一帧星标短暂保留并等待重扫");
    }

    static String withExpiredOverlayRetention(String diagnostic) {
        if (diagnostic == null) return "";
        return diagnostic.replace("；未显示星标", "；无障碍树恢复超时，已清除上一帧星标");
    }

    static <T> List<T> precedingMessages(List<T> messages, int selectedIndex, int maximum) {
        if (messages == null || messages.isEmpty() || selectedIndex <= 0 || maximum <= 0) {
            return Collections.emptyList();
        }
        int end = Math.min(selectedIndex, messages.size());
        int start = Math.max(0, end - maximum);
        return new ArrayList<>(messages.subList(start, end));
    }

    static String analysisUserPrompt(String target, List<String> precedingMessages) {
        String context = precedingMessages == null || precedingMessages.isEmpty()
            ? "（无）" : String.join("\n", precedingMessages);
        return "所选消息之前的可见文字（可能包含双方消息，顺序从早到晚）：\n"
            + context + "\n\n请只分析这条所选消息：" + target;
    }

    static final class Position {
        final int x;
        final int y;

        Position(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return right - left; }
        int height() { return bottom - top; }

        Bounds expand(int amount) {
            return new Bounds(left - amount, top - amount, right + amount, bottom + amount);
        }

        boolean intersects(Bounds other) {
            return left < other.right && right > other.left
                && top < other.bottom && bottom > other.top;
        }
    }

    private ChatSurfacePolicy() {}

    static boolean hasChatComposer(int editors, int bottomButtons, int bottomNavigationLabels) {
        return editors > 0 && bottomButtons >= 2 && bottomNavigationLabels == 0;
    }

    static boolean isWeChatMessageText(String className, String resourceId,
                                       boolean insideMessageRow, boolean insideMessageList) {
        return "android.widget.TextView".equals(className)
            && resourceId != null && resourceId.endsWith(":id/bkl")
            && insideMessageRow && insideMessageList;
    }

    static int layoutCoordinateForScreen(int screenCoordinate, int measuredWindowOffset) {
        return screenCoordinate - measuredWindowOffset;
    }

    static boolean shouldKeepOverlays(boolean consent, boolean weChatForeground) {
        return consent && weChatForeground;
    }

    static boolean shouldKeepOverlaysAfterMissingRoot(boolean windowStateChanged) {
        return !windowStateChanged;
    }

    static boolean shouldRetainPreviousMarkersOnEmptyTree(boolean emptyWeChatTree,
                                                           boolean windowStateChanged,
                                                           boolean hasAttachedMarkers) {
        return emptyWeChatTree && !windowStateChanged && hasAttachedMarkers;
    }

    static boolean isSameWeChatUiRevision(long cardRevision, long currentRevision) {
        return cardRevision == currentRevision;
    }

    static boolean mayCommitOverlayGeometry(long callbackGeneration, long currentGeneration,
                                             long callbackUiRevision, long currentUiRevision,
                                             boolean markerAttached, boolean weChatForeground) {
        return callbackGeneration == currentGeneration
            && isSameWeChatUiRevision(callbackUiRevision, currentUiRevision)
            && markerAttached && weChatForeground;
    }

    static boolean mayApplyOverlayCorrection(long callbackGeneration, long currentGeneration,
                                              long callbackUiRevision, long currentUiRevision,
                                              boolean markerIsCurrent, boolean weChatForeground) {
        return mayCommitOverlayGeometry(callbackGeneration, currentGeneration,
            callbackUiRevision, currentUiRevision, markerIsCurrent, weChatForeground);
    }

    static boolean shouldOfferOcr(int apiLevel, boolean chatSurface, int readableTexts, int secretTexts) {
        return apiLevel >= 34 && chatSurface && readableTexts == 0 && secretTexts == 0;
    }

    static boolean maySendModelData(boolean aiDataConsent, String apiKey) {
        return aiDataConsent && apiKey != null && !apiKey.trim().isEmpty();
    }

    static boolean shouldDecryptStoredApiKey(boolean aiDataConsent, boolean keyConfigured) {
        return aiDataConsent && keyConfigured;
    }

    static boolean maySendAnalysisRequest(boolean aiDataConsent, String apiKey,
                                          long requestUiRevision, long currentUiRevision) {
        return maySendModelData(aiDataConsent, apiKey)
            && isSameWeChatUiRevision(requestUiRevision, currentUiRevision);
    }

    static boolean mayContinueAnalysisRequest(int requestGeneration, int currentGeneration,
                                               boolean aiDataConsent, String apiKey,
                                               long requestUiRevision, long currentUiRevision) {
        return requestGeneration == currentGeneration
            && maySendAnalysisRequest(aiDataConsent, apiKey, requestUiRevision, currentUiRevision);
    }

    static boolean isValidAnalysisResponse(String insight, String reply) {
        if (insight == null || reply == null) return false;
        String cleanInsight = insight.trim();
        String cleanReply = reply.trim();
        return !cleanInsight.isEmpty() && cleanInsight.length() <= 80
            && !cleanReply.isEmpty() && cleanReply.length() <= 300;
    }

    static boolean isSecureModelEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return false;
        try {
            URL url = new URL(endpoint.trim());
            return "https".equalsIgnoreCase(url.getProtocol())
                && url.getHost() != null && !url.getHost().isEmpty()
                && url.getUserInfo() == null;
        } catch (MalformedURLException error) {
            return false;
        }
    }

    static Position positionStar(int right, int messageBottom, int screenWidth, int screenHeight,
                                 int composerTop, int starSize, int margin) {
        int x = right + margin;
        int y = messageBottom - starSize;
        if (starSize <= 0 || x < margin || y < margin
            || x + starSize + margin > screenWidth
            || y + starSize + margin > Math.min(screenHeight, composerTop)) return null;
        return new Position(x, y);
    }

    static List<Position> positionStars(List<Bounds> messages, int screenWidth, int screenHeight,
                                        int composerTop, int starSize, int margin) {
        return positionStars(messages, messages, screenWidth, screenHeight, composerTop, starSize, margin);
    }

    static List<Position> positionStars(List<Bounds> messages, List<Bounds> exclusions,
                                        int screenWidth, int screenHeight,
                                        int composerTop, int starSize, int margin) {
        if (messages == null || messages.isEmpty()) return Collections.emptyList();
        int safeBottom = Math.min(screenHeight, composerTop);
        int maxX = screenWidth - starSize - margin;
        int maxY = safeBottom - starSize - margin;
        if (maxX < margin || maxY < margin) {
            return new ArrayList<>(Collections.nCopies(messages.size(), null));
        }

        List<Position> result = new ArrayList<>(Collections.nCopies(messages.size(), null));
        List<Bounds> occupiedMarkers = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            Bounds message = messages.get(index);
            Position chosen = positionStar(message.right, message.bottom, screenWidth, screenHeight,
                composerTop, starSize, margin);
            if (chosen == null || chosen.y < message.top) continue;
            Bounds candidate = new Bounds(chosen.x, chosen.y, chosen.x + starSize, chosen.y + starSize);
            if (overlapsMessageOrMarker(candidate, messages, occupiedMarkers, index, margin)) continue;
            boolean blocked = false;
            for (Bounds excluded : exclusions) {
                if (candidate.intersects(excluded)) { blocked = true; break; }
            }
            if (blocked) continue;
            occupiedMarkers.add(candidate);
            result.set(index, chosen);
        }
        return result;
    }

    private static boolean overlapsMessageOrMarker(Bounds candidate, List<Bounds> messages,
                                                    List<Bounds> occupiedMarkers, int targetIndex,
                                                    int margin) {
        Bounds expandedCandidate = candidate.expand(margin);
        for (int index = 0; index < messages.size(); index++) {
            Bounds message = messages.get(index);
            // QQ containers already include row spacing. Extending below the target
            // invents a collision with an adjacent wider row that starts at its bottom.
            if (candidate.intersects(message)) return true;
        }
        for (Bounds marker : occupiedMarkers) {
            if (expandedCandidate.intersects(marker.expand(margin))) return true;
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
