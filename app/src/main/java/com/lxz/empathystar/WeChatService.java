package com.lxz.empathystar;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WeChatService extends AccessibilityService {
    // Keep the installed service component stable across upgrades; adapters own app differences.
    private ScreenReader screenReader;
    private ScreenReadResult visibleFrame;
    private View summaryAction;
    private ConversationSummaryCard summaryCard;
    private volatile String sourcePackage = ChatSnapshot.WECHAT;
    private volatile int sourceWindowId = -1;
    private ChatSnapshot cardSnapshot;
    private boolean screenGuardReady;
    private boolean screenOff;
    private boolean scanPending;
    private long lastScrollAt = -1;
    private long starCreations, starVisibilityChanges, fullClears, scanCount, windowListEvents;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            screenOff = Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
            suspendOverlaySession();
            if (screenAvailable()) scheduleScan();
        }
    };

    private boolean screenAvailable() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return screenGuardReady && !screenOff && power != null && keyguard != null
            && OverlayVisibilityPolicy.screenAvailable(power.isInteractive(),
                keyguard.isKeyguardLocked(), keyguard.isDeviceLocked());
    }

    private void suspendOverlaySession() {
        handler.removeCallbacks(scanTask);
        scanPending = false;
        lastScrollAt = -1;
        wechatUiRevision++;
        overlayOffsetKnown = false;
        hideAll();
    }
    private final SharedPreferences.OnSharedPreferenceChangeListener scopeListener = (prefs, key) -> {
        if ("consent".equals(key) || "qq_consent".equals(key) || "wechat_read_paused".equals(key)
            || "qq_read_paused".equals(key) || "qq_group_consent".equals(key)
            || "ai_data_consent".equals(key) || "qq_ai_data_consent".equals(key)) {
            hideAll();
            scheduleScan();
        }
    };

    private boolean readingAllowed(String packageName) {
        if (!screenAvailable() || !ChatSnapshot.supported(packageName)) return false;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        return ChatReadScope.enabled(prefs.getBoolean(ChatSnapshot.readConsentKey(packageName), false),
            prefs.getBoolean(ChatReadScope.pausedKey(packageName), false));
    }

    private boolean modelAllowed(String packageName) {
        return readingAllowed(packageName) && getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
            .getBoolean(ChatSnapshot.modelConsentKey(packageName), false);
    }

    private ScreenReader.Access readAccess(String packageName) {
        if (!screenAvailable()) return ScreenReader.Access.PAUSED;
        if (!ChatSnapshot.supported(packageName)) return ScreenReader.Access.NOT_AUTHORIZED;
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(ChatReadScope.pausedKey(packageName), false)) return ScreenReader.Access.PAUSED;
        return prefs.getBoolean(ChatSnapshot.readConsentKey(packageName), false)
            ? ScreenReader.Access.ALLOWED : ScreenReader.Access.NOT_AUTHORIZED;
    }

    private boolean isCurrentSnapshot(ChatSnapshot snapshot) {
        return screenAvailable() && screenReader != null && screenReader.isCurrent(snapshot, wechatUiRevision);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private final Map<String, View> stars = new LinkedHashMap<>();
    private int overlayWindowOffsetX;
    private int overlayWindowOffsetY;
    private int overlayCalibrationWidth;
    private int overlayCalibrationHeight;
    private boolean overlayOffsetKnown;
    private long overlayGeometryGeneration = 0;
    private final Map<View, OverlayPlacement> pendingPlacements = new LinkedHashMap<>();
    private boolean placementQueued;
    private Runnable geometryReporter;
    private long scanStartedAt, readDurationMs, measuredScrollAt = -1;
    private View card;
    private AnalysisCardStack analysisCardStack;
    private View ocrAction;
    private TextView cardBody;
    private Button insertButton;
    private String selectedText = "";
    private String proposedReply = "";
    private LinearLayout cardResultContent;
    private final ChatModelClient modelClient = new ChatModelClient.Https();
    private List<String> visibleContext = new ArrayList<>();
    private volatile int generation = 0;
    private final Object analysisConnectionLock = new Object();
    private HttpURLConnection activeAnalysisConnection;
    private String lastDiagnostic = "";
    private long lastDiagnosticAt = 0;
    private volatile long wechatUiRevision = 0;
    private long cardUiRevision = -1;
    private boolean cardTracksWeChatSurface = false;
    private TextRecognizer textRecognizer;
    private Bitmap ocrBitmapInFlight;
    private long ocrGeneration = 0;
    private boolean ocrInProgress = false;
    private int lastComposerTop = 0;
    private final Runnable scanTask = this::scan;
    private final Runnable foregroundRefresh = () -> {
        scheduleScan();
    };
    private final Runnable foregroundCheck = new Runnable() {
        @Override public void run() {
            scan();
            if (BuildConfig.DEBUG) getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
                .putString("overlay_counters", "created=" + starCreations + ";visibility=" + starVisibilityChanges
                    + ";clears=" + fullClears + ";scans=" + scanCount + ";windowEvents=" + windowListEvents).apply();
            handler.postDelayed(this, 2000);
        }
    };

    @Override public void onServiceConnected() {
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (!screenGuardReady) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenReceiver, filter);
            screenGuardReady = true;
        }
        screenReader = new AccessibilityChatReader(this, new ScreenReader.AccessPolicy() {
            @Override public ScreenReader.Access access(String packageName) { return readAccess(packageName); }
            @Override public boolean groupAllowed(String packageName) {
                return ChatSnapshot.QQ.equals(packageName) && readingAllowed(packageName)
                    && getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean("qq_group_consent", false);
            }
        }, new AccessibilityChatReader.Diagnostics() {
            @Override public void rootQueried(String packageName) {
                SharedPreferences counts = getSharedPreferences("read_scope_diagnostics", MODE_PRIVATE);
                String key = ChatSnapshot.QQ.equals(packageName) ? "qq_root_queries" : "wechat_root_queries";
                counts.edit().putLong(key, counts.getLong(key, 0) + 1).apply();
            }
            @Override public void recovered(int before, boolean refreshed, int after, boolean cleared, int queried) {
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                    .putString("root_recovery_result", "before=" + before + ";refreshed=" + (refreshed ? 1 : 0)
                        + ";afterRefresh=" + after + ";cacheCleared=" + (cleared ? 1 : 0) + ";afterQuery=" + queried)
                    .putLong("root_recovery_at", System.currentTimeMillis()).apply();
            }
        });
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(scopeListener);
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
            .putLong("service_connected_at", System.currentTimeMillis()).apply();
        scheduleScan();
        handler.postDelayed(foregroundCheck, 2000);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!screenAvailable()) { suspendOverlaySession(); return; }
        if (event == null || event.getPackageName() == null) return;
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (screenReader != null) screenReader.observeWindow(event.getWindowId(), event.getPackageName().toString());
        }
        if (getPackageName().contentEquals(event.getPackageName())) {
            // Opening the app's own settings must clear chat overlays too. Ignore
            // WINDOWS_CHANGED here because adding an accessibility overlay emits it.
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Our own overlay also emits WINDOW_STATE_CHANGED. Only a verified
                // departure from the chat app may clear the surface here.
                if (!isWeChatForeground()) { wechatUiRevision++; hideAll(); }
                handler.removeCallbacks(foregroundRefresh);
                handler.postDelayed(foregroundRefresh, 180);
            }
            return;
        }
        if (!ChatSnapshot.supported(event.getPackageName().toString())) {
            if ((eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) && !isWeChatForeground()) {
                wechatUiRevision++;
                hideAll();
            }
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
                handler.removeCallbacks(foregroundRefresh);
                handler.postDelayed(foregroundRefresh, 180);
            }
            return;
        }
        if (!readingAllowed(event.getPackageName().toString())) {
            if (sourcePackage.contentEquals(event.getPackageName())
                || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) hideAll();
            return;
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            windowListEvents++;
            // Adding/removing our own accessibility windows also notifies QQ.
            // A window-list change (often windowId=-1) is not a new chat revision.
            if (!isWeChatForeground()) { wechatUiRevision++; hideAll(); }
            scheduleScan();
            return;
        }
        wechatUiRevision++;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            || !sourcePackage.contentEquals(event.getPackageName())
            || OverlayVisibilityPolicy.differentKnownWindow(sourceWindowId, event.getWindowId())) {
            hideAll();
        } else if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
            || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Old interactions expire immediately; retain only same-page View objects.
            visibleFrame = null;
            overlayGeometryGeneration++;
            for (View marker : stars.values()) marker.setEnabled(false);
            if (summaryAction != null) summaryAction.setEnabled(false);
            if (card != null) hideCard();
            ocrGeneration++;
            hideOcrAction();
            selectedText = "";
            proposedReply = "";
            visibleContext.clear();
            if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                lastScrollAt = SystemClock.uptimeMillis();
                for (View marker : stars.values()) setStarVisibility(marker, View.INVISIBLE);
                if (summaryAction != null) summaryAction.setVisibility(View.INVISIBLE);
            }
        }
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
            .putLong(ChatSnapshot.QQ.contentEquals(event.getPackageName()) ? "qq_event_at" : "wechat_event_at",
                System.currentTimeMillis()).apply();
        scheduleScan();
    }

    @Override public void onInterrupt() { hideAll(); }
    @Override public void onDestroy() {
        if (screenGuardReady) { unregisterReceiver(screenReceiver); screenGuardReady = false; }
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(scopeListener);
        handler.removeCallbacks(scanTask);
        handler.removeCallbacks(foregroundCheck);
        handler.removeCallbacks(foregroundRefresh);
        hideAll();
        if (textRecognizer != null) textRecognizer.close();
        super.onDestroy();
    }

    private void scheduleScan() {
        if (!screenAvailable()) { suspendOverlaySession(); return; }
        if (scanPending) return;
        scanPending = true;
        handler.postDelayed(scanTask, 16);
    }

    private String treeState = "unknown";

    private void scan() {
        scanCount++;
        scanPending = false;
        if (!screenAvailable()) { suspendOverlaySession(); return; }
        long scrollDelay = OverlayVisibilityPolicy.scrollDelay(SystemClock.uptimeMillis(), lastScrollAt);
        if (scrollDelay > 0) {
            handler.removeCallbacks(scanTask);
            scanPending = true;
            handler.postDelayed(scanTask, scrollDelay);
            return;
        }
        if (screenReader == null) { hideAll(); return; }
        scanStartedAt = SystemClock.uptimeMillis();
        ScreenReadResult result = screenReader.read(wechatUiRevision);
        if (result.snapshot != null && (!result.snapshot.packageName.equals(sourcePackage)
            || result.snapshot.windowId != sourceWindowId)) {
            hideAll();
            wechatUiRevision++;
            sourcePackage = result.snapshot.packageName;
            sourceWindowId = result.snapshot.windowId;
            result = screenReader.read(wechatUiRevision);
            if (result.snapshot == null || !sourcePackage.equals(result.snapshot.packageName)
                || sourceWindowId != result.snapshot.windowId) { hideAll(); return; }
        }
        readDurationMs = SystemClock.uptimeMillis() - scanStartedAt;
        treeState = result.state == ScreenReadResult.State.EMPTY_TREE ? "empty_tree"
            : result.state == ScreenReadResult.State.READY || result.state == ScreenReadResult.State.NO_TEXT
                || result.state == ScreenReadResult.State.NOT_CHAT || result.state == ScreenReadResult.State.NO_COMPOSER
                    ? "readable_tree" : "no_window";
        if (result.state == ScreenReadResult.State.EMPTY_TREE || result.state == ScreenReadResult.State.NOT_CHAT) {
            String structuralDiagnostic = result.metrics.composerDiagnostic();
            diagnostic(structuralDiagnostic);
            hideAll();
            return;
        }
        if (result.state != ScreenReadResult.State.READY && result.state != ScreenReadResult.State.NO_TEXT) {
            diagnostic("读取状态=" + result.state.name() + "；未读取消息、未截屏、未调用模型");
            hideAll();
            return;
        }
        if (card != null && cardTracksWeChatSurface) {
            if (!ChatSurfacePolicy.isSameWeChatUiRevision(cardUiRevision, wechatUiRevision)) {
                hideCard();
                selectedText = "";
                proposedReply = "";
                visibleContext.clear();
            } else return;
        }
        if (card != null) return;
        visibleFrame = result;
        if (result.conversationType == ScreenReadResult.ConversationType.GROUP) {
            hideStars(); hideOcrAction();
            if (result.state == ScreenReadResult.State.READY) showSummaryAction(result);
            else hideSummaryAction();
            diagnostic("群聊已识别：可见文字=" + result.messages.size() + "；仅提供总结入口，未上传");
            return;
        }
        ScreenReadResult.Geometry geometry = result.geometry;
        int width = geometry.width, height = geometry.height;
        int[] composerTop = {geometry.composerTop}, editorTop = {geometry.editorTop};
        boolean usedBottomBarFallback = geometry.usedBottomBarFallback;
        int[] senderStats = result.metrics.senders();
        lastComposerTop = geometry.composerTop;
        List<MessageText> texts = new ArrayList<>();
        for (ScreenReadResult.Message item : result.messages) {
            ChatSurfacePolicy.Bounds bounds = item.bounds;
            texts.add(new MessageText(item.text, new Rect(bounds.left, bounds.top, bounds.right, bounds.bottom), item.side));
        }
        List<String> allText = new ArrayList<>(texts.size());
        for (MessageText item : texts) allText.add(item.text);
        Map<String, Integer> occurrences = new HashMap<>();
        List<MessageText> markedMessages = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            MessageText item = texts.get(index);
            if (!looksLikeSecret(item.text) && item.side == ChatSurfacePolicy.MESSAGE_SIDE_INCOMING) {
                int occurrence = occurrences.getOrDefault(item.text, 0);
                occurrences.put(item.text, occurrence + 1);
                item.key = item.text + "\u0000" + occurrence;
                item.context = ChatSurfacePolicy.precedingMessages(allText, index, 8);
                markedMessages.add(item);
            }
        }
        if (texts.isEmpty()) {
            hideStars();
            hideSummaryAction();
            if (ChatSnapshot.WECHAT.equals(sourcePackage)
                && ChatSurfacePolicy.shouldOfferOcr(Build.VERSION.SDK_INT, true, 0, 0)) {
                showOcrAction(width, height, composerTop[0]);
                diagnostic("聊天输入栏已识别，但未读到文字节点；点击“识别”可在本机 OCR");
            } else {
                hideOcrAction();
                diagnostic("已找到聊天输入栏，但没有受支持的文字消息；未截屏、未调用模型");
            }
            return;
        }
        hideOcrAction();
        if (markedMessages.isEmpty()) {
            hideStars();
            showSummaryAction(result);
            diagnostic(ChatSurfacePolicy.counterpartyMarkerDiagnostic(senderStats[0], senderStats[1],
                senderStats[2], 0, 0));
            return;
        }
        for (MessageText item : markedMessages) item.context = sanitizeContext(item.context);
        MessageText latestMessage = markedMessages.get(markedMessages.size() - 1);
        visibleContext = new ArrayList<>(latestMessage.context);
        selectedText = latestMessage.text;
        if (card == null) {
            int safeComposerTop = Math.min(composerTop[0], editorTop[0]);
            List<MessageText> targets = ChatSurfacePolicy.allTargets(markedMessages);
            showStars(targets, width, height, safeComposerTop, editorTop[0], usedBottomBarFallback,
                markedMessages.size(), senderStats);
            showSummaryAction(result);
        }
    }

    private List<String> sanitizeContext(List<String> context) {
        List<String> safe = new ArrayList<>(context);
        for (int i = 0; i < safe.size(); i++) if (looksLikeSecret(safe.get(i))) safe.set(i, "[敏感内容已隐藏]");
        return safe;
    }

    private boolean isWeChatForeground() {
        return isCurrentSnapshot(new ChatSnapshot(sourcePackage, sourceWindowId, wechatUiRevision));
    }

    private void diagnostic(String message) {
        long now = System.currentTimeMillis();
        if (message.equals(lastDiagnostic) && now - lastDiagnosticAt < 1000) return;
        lastDiagnostic = message;
        lastDiagnosticAt = now;
        String prefix = ChatSnapshot.QQ.equals(sourcePackage) ? "qq" : "wechat";
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
            .putString(prefix + "_scan_result", ChatSnapshot.QQ.equals(sourcePackage) ? message.replace("微信", "QQ") : message)
            .putString(prefix + "_tree_state", treeState)
            .putLong(prefix + "_scan_at", now).apply();
    }

    private boolean looksLikeSecret(String text) {
        return ChatContentPolicy.sensitive(text);
    }

    private void showStars(List<MessageText> messages, int width, int height, int composerTop,
                          int editorTop, boolean usedBottomBarFallback, int recognizedMessageCount,
                          int[] senderStats) {
        if (windows == null || !screenAvailable()) { hideAll(); return; }
        clearLatestOverlayGeometry();
        if (overlayCalibrationWidth != width || overlayCalibrationHeight != height) {
            overlayCalibrationWidth = width;
            overlayCalibrationHeight = height;
            overlayOffsetKnown = false;
        }
        int size = dp(12);
        Set<String> desiredKeys = new HashSet<>();
        List<ChatSurfacePolicy.Bounds> messageBounds = new ArrayList<>(messages.size());
        for (MessageText message : messages) {
            messageBounds.add(new ChatSurfacePolicy.Bounds(message.bounds.left, message.bounds.top,
                message.bounds.right, message.bounds.bottom));
        }
        List<ChatSurfacePolicy.Bounds> excluded = visibleFrame == null ? new ArrayList<>()
            : new ArrayList<>(visibleFrame.geometry.exclusions);
        if (visibleFrame != null) for (ScreenReadResult.Message item : visibleFrame.messages) excluded.add(item.bounds);
        List<ChatSurfacePolicy.Position> positions = ChatSurfacePolicy.positionStars(
            messageBounds, excluded, width, height, composerTop, size, dp(4));
        Map<String, ChatSurfacePolicy.Position> intendedPositions = new HashMap<>();
        for (int index = 0; index < messages.size(); index++) {
            MessageText message = messages.get(index);
            ChatSurfacePolicy.Position position = positions.get(index);
            if (position == null) {
                removeStarMarker(message.key, stars.get(message.key));
                continue;
            }
            desiredKeys.add(message.key);
            intendedPositions.put(message.key, position);
            View marker = stars.get(message.key);
            if (marker == null) {
                marker = createStarMarker();
                starCreations++;
                setStarVisibility(marker, View.INVISIBLE);
                stars.put(message.key, marker);
            }
            final long correctionGeneration = overlayGeometryGeneration;
            final long markerUiRevision = wechatUiRevision;
            final ChatSnapshot markerSnapshot = new ChatSnapshot(sourcePackage, sourceWindowId, markerUiRevision);
            final String target = message.text;
            final List<String> targetContext = new ArrayList<>(message.context);
            marker.setOnClickListener(v -> {
                if (!ChatSurfacePolicy.isSameWeChatUiRevision(markerUiRevision, wechatUiRevision)
                    || !isCurrentSnapshot(markerSnapshot)) {
                    scheduleScan();
                    return;
                }
                selectedText = target;
                visibleContext = new ArrayList<>(targetContext);
                openCard();
            });
            WindowManager.LayoutParams params = overlayParams(size, size, position.x, position.y);
            applyMeasuredOverlayOffset(params, position.x, position.y, width, size);
            try {
                if (marker.getParent() == null) windows.addView(marker, params);
                else {
                    WindowManager.LayoutParams old = (WindowManager.LayoutParams) marker.getLayoutParams();
                    if (old.x != params.x || old.y != params.y) {
                        setStarVisibility(marker, View.INVISIBLE);
                        windows.updateViewLayout(marker, params);
                    }
                }
                marker.setEnabled(true);
                correctOverlayScreenPosition(marker, message.key, position.x, position.y,
                    width, size, correctionGeneration, markerUiRevision);
            } catch (RuntimeException ignored) {
                diagnostic("已找到消息，但系统拒绝显示星标");
                removeStarMarker(message.key, marker);
            }
        }
        for (String key : new ArrayList<>(stars.keySet())) {
            if (!desiredKeys.contains(key)) removeStarMarker(key, stars.get(key));
        }
        if (!messages.isEmpty()) {
            View first = null;
            View last = null;
            for (MessageText message : messages) {
                if (!intendedPositions.containsKey(message.key)) continue;
                View current = stars.get(message.key);
                if (first == null) first = current;
                last = current;
            }
            final View firstMarker = first;
            final View lastMarker = last;
            if (firstMarker != null && lastMarker != null) {
                final long geometryGeneration = overlayGeometryGeneration;
                final long geometryUiRevision = wechatUiRevision;
                geometryReporter = () -> {
                    if (geometryGeneration != overlayGeometryGeneration) return;
                    boolean markersAttached = true;
                    for (String key : intendedPositions.keySet()) {
                        View targetMarker = stars.get(key);
                        if (targetMarker == null || targetMarker.getParent() == null) {
                            markersAttached = false;
                            break;
                        }
                    }
                    boolean weChatForeground = screenAvailable(); // batch already verified the source for this UI turn
                    if (!ChatSurfacePolicy.mayCommitOverlayGeometry(
                        geometryGeneration, overlayGeometryGeneration,
                        geometryUiRevision, wechatUiRevision, markersAttached, weChatForeground)) {
                        clearLatestOverlayGeometry();
                        return;
                    }
                    int[] firstLocation = new int[2];
                    int[] lastLocation = new int[2];
                    firstMarker.getLocationOnScreen(firstLocation);
                    lastMarker.getLocationOnScreen(lastLocation);
                    StringBuilder actualAnchors = new StringBuilder();
                    StringBuilder targetAnchors = new StringBuilder();
                    for (int i = 0; i < messages.size(); i++) {
                        MessageText message = messages.get(i);
                        ChatSurfacePolicy.Position intended = intendedPositions.get(message.key);
                        if (intended == null) continue;
                        View targetMarker = stars.get(message.key);
                        int[] actual = new int[2];
                        targetMarker.getLocationOnScreen(actual);
                        if (actualAnchors.length() > 0) {
                            actualAnchors.append('|');
                            targetAnchors.append('|');
                        }
                        actualAnchors.append(actual[0]).append(',').append(actual[1]);
                        targetAnchors.append(intended.x).append(',').append(intended.y);
                    }
                    String geometry = "count=" + stars.size()
                        + ";metrics=" + width + "x" + height
                        + ";composerTop=" + composerTop
                        + ";editorTop=" + editorTop
                        + ";first=" + firstLocation[0] + "," + firstLocation[1]
                        + ";last=" + lastLocation[0] + "," + lastLocation[1]
                        + ";anchors=" + actualAnchors
                        + ";targets=" + targetAnchors
                        + ";windowOffset=" + overlayWindowOffsetX + "," + overlayWindowOffsetY;
                    getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
                        .putString("latest_overlay_geometry", geometry).apply();
                };
            }
        }
        if (stars.isEmpty()) {
            diagnostic(ChatSurfacePolicy.counterpartyMarkerDiagnostic(senderStats[0], senderStats[1],
                senderStats[2], 0, recognizedMessageCount));
        } else {
            int displayedMarkers = stars.size();
            diagnostic(ChatSurfacePolicy.counterpartyMarkerDiagnostic(senderStats[0], senderStats[1],
                senderStats[2], displayedMarkers, Math.max(0, recognizedMessageCount - displayedMarkers))
                + (usedBottomBarFallback ? "（按微信底部控件栏定位输入区域）" : ""));
        }
    }

    private void applyMeasuredOverlayOffset(WindowManager.LayoutParams params, int targetX, int targetY,
                                            int screenWidth, int size) {
        if (!overlayOffsetKnown) return;
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        params.x = rtl ? screenWidth - targetX - size + overlayWindowOffsetX
            : targetX - overlayWindowOffsetX;
        params.y = ChatSurfacePolicy.layoutCoordinateForScreen(targetY, overlayWindowOffsetY);
    }

    private void correctOverlayScreenPosition(View marker, String markerKey,
                                              int targetX, int targetY, int screenWidth, int size,
                                              long callbackGeneration, long callbackUiRevision) {
        enqueuePlacement(new OverlayPlacement(marker, markerKey, targetX, targetY, screenWidth, size,
            callbackGeneration, callbackUiRevision));
    }

    private final class OverlayPlacement {
        final View view;
        final String markerKey;
        final int x, y, screenWidth, width;
        final long geometryGeneration, revision;
        final ChatSnapshot snapshot;
        OverlayPlacement(View view, String markerKey, int x, int y, int screenWidth, int width,
                         long geometryGeneration, long revision) {
            this.view = view; this.markerKey = markerKey; this.x = x; this.y = y;
            this.screenWidth = screenWidth; this.width = width;
            this.geometryGeneration = geometryGeneration; this.revision = revision;
            this.snapshot = new ChatSnapshot(sourcePackage, sourceWindowId, revision);
        }
        boolean current(boolean available) {
            return ChatSurfacePolicy.mayApplyOverlayCorrection(geometryGeneration, overlayGeometryGeneration,
                revision, wechatUiRevision, view.getParent() != null
                    && (markerKey == null ? view == summaryAction : stars.get(markerKey) == view), available)
                && snapshot.packageName.equals(sourcePackage) && snapshot.windowId == sourceWindowId;
        }
        void visible(boolean show) {
            view.setEnabled(show);
            if (markerKey == null) view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            else setStarVisibility(view, show ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void enqueuePlacement(OverlayPlacement placement) {
        pendingPlacements.put(placement.view, placement);
        if (placementQueued) return;
        placementQueued = true;
        handler.post(this::correctPlacementBatch);
    }

    private void correctPlacementBatch() {
        placementQueued = false;
        List<OverlayPlacement> batch = new ArrayList<>(pendingPlacements.values());
        pendingPlacements.clear();
        boolean available = screenAvailable();
        batch.removeIf(item -> !item.current(available));
        if (batch.isEmpty() || windows == null) return;
        if (!isCurrentSnapshot(batch.get(0).snapshot)) {
            for (OverlayPlacement item : batch) item.visible(false);
            scheduleScan(); return;
        }
        final long startedAt = scanStartedAt, readMs = readDurationMs;
        final Runnable report = geometryReporter;
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        for (OverlayPlacement item : batch) {
            int[] actual = new int[2]; item.view.getLocationOnScreen(actual);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) item.view.getLayoutParams();
            if (!overlayOffsetKnown) {
                int paramScreenX = rtl ? item.screenWidth - params.x - item.width : params.x;
                overlayWindowOffsetX = actual[0] - paramScreenX;
                overlayWindowOffsetY = actual[1] - params.y;
                overlayOffsetKnown = true;
            }
            int x = rtl ? item.screenWidth - item.x - item.width + overlayWindowOffsetX
                : item.x - overlayWindowOffsetX;
            int y = ChatSurfacePolicy.layoutCoordinateForScreen(item.y, overlayWindowOffsetY);
            if (params.x != x || params.y != y) {
                params.x = x; params.y = y; item.visible(false);
                try { windows.updateViewLayout(item.view, params); }
                catch (RuntimeException failure) { item.visible(false); scheduleScan(); return; }
            }
        }
        // One frame callback and one fresh source check for the whole batch, never per marker.
        batch.get(0).view.postOnAnimation(() -> presentPlacementBatch(batch, startedAt, readMs, report));
    }

    private void presentPlacementBatch(List<OverlayPlacement> batch, long startedAt, long readMs, Runnable report) {
        boolean available = screenAvailable();
        for (OverlayPlacement item : batch) if (!item.current(available)) return;
        if (!isCurrentSnapshot(batch.get(0).snapshot)) {
            for (OverlayPlacement item : batch) item.visible(false);
            scheduleScan(); return;
        }
        boolean aligned = true;
        for (OverlayPlacement item : batch) {
            int[] actual = new int[2]; item.view.getLocationOnScreen(actual);
            if (Math.abs(actual[0] - item.x) > 2 || Math.abs(actual[1] - item.y) > 2) aligned = false;
        }
        for (OverlayPlacement item : batch) item.visible(aligned);
        if (!aligned) { scheduleScan(); return; }
        if (report != null) report.run();
        if (BuildConfig.DEBUG) {
            long now = SystemClock.uptimeMillis();
            SharedPreferences.Editor timing = getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
                .putString("render_timing", "readMs=" + readMs + ";scanToShownMs=" + Math.max(0, now - startedAt)
                    + ";items=" + batch.size() + ";sourceChecks=2");
            if (lastScrollAt >= 0 && measuredScrollAt != lastScrollAt) {
                measuredScrollAt = lastScrollAt;
                timing.putString("scroll_timing", "eventToShownMs=" + Math.max(0, now - lastScrollAt)
                    + ";readMs=" + readMs + ";items=" + batch.size());
            }
            timing.apply();
        }
    }

    private void showOcrAction(int width, int height, int composerTop) {
        if (windows == null || Build.VERSION.SDK_INT < 34) return;
        if (ocrAction == null) {
            TextView action = new TextView(this);
            action.setText("识别");
            action.setTextSize(14);
            action.setTextColor(Color.rgb(72, 48, 0));
            action.setGravity(Gravity.CENTER);
            action.setPadding(dp(8), 0, dp(8), 0);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(255, 232, 165));
            background.setCornerRadius(dp(10));
            action.setBackground(background);
            action.setElevation(dp(3));
            action.setContentDescription("手动识别当前微信聊天文字");
            action.setOnClickListener(v -> captureCurrentChatForOcr());
            ocrAction = action;
        }
        int buttonWidth = dp(56);
        int buttonHeight = dp(40);
        int x = Math.max(dp(8), width - buttonWidth - dp(12));
        int y = Math.max(dp(64), Math.min(composerTop - buttonHeight - dp(8), height - buttonHeight - dp(80)));
        WindowManager.LayoutParams params = overlayParams(buttonWidth, buttonHeight, x, y);
        try {
            if (ocrAction.getParent() == null) windows.addView(ocrAction, params);
            else windows.updateViewLayout(ocrAction, params);
        } catch (RuntimeException ignored) {
            diagnostic("聊天页已识别，但系统拒绝显示手动 OCR 入口");
            hideOcrAction();
        }
    }

    private void hideOcrAction() {
        if (ocrAction != null && ocrAction.getParent() != null) {
            removeOverlayView(ocrAction);
        }
    }

    private void captureCurrentChatForOcr() {
        if (Build.VERSION.SDK_INT < 34) {
            showNotice("手动 OCR 需要 Android 14 或更新版本。");
            return;
        }
        if (ocrInProgress) return;
        if (!isWeChatForeground()) {
            hideAll();
            return;
        }
        ocrInProgress = true;
        final long request = ++ocrGeneration;
        hideOcrAction();
        if (ocrAction instanceof TextView) ((TextView) ocrAction).setText("识别中");
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap wrapped = null;
                    Bitmap bitmap = null;
                    try {
                        if (request != ocrGeneration || !isWeChatForeground()) return;
                        android.graphics.ColorSpace colorSpace = result.getColorSpace();
                        if (colorSpace == null) colorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        if (wrapped == null) throw new IllegalStateException("系统未能提供可识别的屏幕图像");
                        bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        if (bitmap == null) throw new IllegalStateException("无法准备本机识别图像");
                    } catch (Exception error) {
                        if (request == ocrGeneration) failOcr("无法读取当前屏幕，请确认微信聊天仍在前台后重试");
                        return;
                    } finally {
                        if (wrapped != null && wrapped != bitmap) wrapped.recycle();
                        if (buffer != null) buffer.close();
                    }
                    recognizeBitmap(bitmap, request);
                }

                @Override public void onFailure(int errorCode) {
                    if (request != ocrGeneration) return;
                    failOcr("系统拒绝截取当前窗口（错误 " + errorCode + "）；请检查安全窗口限制后重试");
                }
            });
        } catch (RuntimeException error) {
            failOcr("无法启动系统屏幕识别，请重开无障碍服务后重试");
        }
    }

    private void recognizeBitmap(Bitmap bitmap, long request) {
        if (request != ocrGeneration || !isWeChatForeground()) {
            bitmap.recycle();
            return;
        }
        ocrBitmapInFlight = bitmap;
        if (textRecognizer == null) {
            textRecognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        }
        try {
            textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(getMainExecutor(), result -> {
                    if (request != ocrGeneration || !isWeChatForeground()) return;
                    showOcrCandidates(result, request);
                })
                .addOnFailureListener(getMainExecutor(), error -> {
                    if (request == ocrGeneration) failOcr("本机中文 OCR 失败，请稍后重试");
                })
                .addOnCompleteListener(getMainExecutor(), task -> {
                    if (ocrBitmapInFlight == bitmap) ocrBitmapInFlight = null;
                    if (!bitmap.isRecycled()) bitmap.recycle();
                    if (request == ocrGeneration) ocrInProgress = false;
                });
        } catch (RuntimeException error) {
            if (ocrBitmapInFlight == bitmap) ocrBitmapInFlight = null;
            if (!bitmap.isRecycled()) bitmap.recycle();
            if (request == ocrGeneration) failOcr("无法启动本机中文 OCR，请稍后重试");
        }
    }

    private void showOcrCandidates(Text result, long request) {
        int height = getResources().getDisplayMetrics().heightPixels;
        int chatTop = Math.round(height * 0.11f);
        int chatBottom = lastComposerTop > 0 ? Math.min(height, lastComposerTop) : height - dp(72);
        List<String> candidates = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            String value = block.getText() == null ? "" : block.getText().trim();
            Rect bounds = block.getBoundingBox();
            if (value.isEmpty() || value.length() > 500 || bounds == null
                || bounds.top < chatTop || bounds.bottom >= chatBottom || looksLikeSecret(value)) continue;
            candidates.add(value);
        }
        if (request != ocrGeneration) return;
        hideOcrAction();
        hideStars();
        if (candidates.isEmpty()) {
            showNotice("本机 OCR 没有找到可选文字。没有发送模型请求。");
            diagnostic("用户主动 OCR 完成，未找到安全候选文字");
            return;
        }
        hideCard();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        panel.setBackgroundColor(Color.rgb(255, 250, 239));
        panel.addView(text("本机识别候选", 20));
        panel.addView(text("请核对文字和发送方。点某一条的“分析”后，才会把该段文字发送到你配置的模型。", 14));
        ScrollView scroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        final long candidatesUiRevision = wechatUiRevision;
        for (String candidate : candidates) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.addView(text(candidate, 15));
            Button analyze = new Button(this);
            analyze.setText("分析这段");
            analyze.setOnClickListener(v -> {
                if (request != ocrGeneration || !isWeChatForeground()) { hideAll(); return; }
                if (!ChatSurfacePolicy.isSameWeChatUiRevision(candidatesUiRevision, wechatUiRevision)) {
                    hideCard();
                    selectedText = "";
                    proposedReply = "";
                    visibleContext.clear();
                    scheduleScan();
                    return;
                }
                selectedText = candidate;
                visibleContext = new ArrayList<>();
                visibleContext.add(candidate);
                hideCard();
                openCard();
            });
            row.addView(analyze);
            choices.addView(row);
        }
        scroll.addView(choices);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            Math.min(dp(480), Math.round(height * 0.58f))));
        Button close = new Button(this);
        close.setText("取消");
        close.setOnClickListener(v -> { hideCard(); scheduleScan(); });
        panel.addView(close);
        card = panel;
        cardTracksWeChatSurface = true;
        cardUiRevision = wechatUiRevision;
        cardSnapshot = new ChatSnapshot(sourcePackage, sourceWindowId, cardUiRevision);
        try {
            windows.addView(card, overlayParams(getResources().getDisplayMetrics().widthPixels - dp(24),
                WindowManager.LayoutParams.WRAP_CONTENT, dp(12), dp(80)));
            diagnostic("用户主动 OCR 完成，找到 " + candidates.size() + " 个候选；等待用户选择");
        } catch (RuntimeException error) {
            card = null;
            cardTracksWeChatSurface = false;
            cardUiRevision = -1;
            diagnostic("OCR 已完成，但系统拒绝显示候选列表");
        }
    }

    private void failOcr(String message) {
        ocrInProgress = false;
        if (ocrAction instanceof TextView) ((TextView) ocrAction).setText("识别");
        showNotice(message + "。没有发送模型请求。");
        diagnostic("用户主动 OCR 未完成；" + message);
    }

    private View createStarMarker() {
        TextView marker = new TextView(this);
        marker.setText("✦");
        marker.setTextSize(9);
        marker.setTextColor(Color.rgb(72, 48, 0));
        marker.setGravity(Gravity.CENTER);
        marker.setIncludeFontPadding(false);
        marker.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(255, 232, 165));
        background.setCornerRadius(dp(7));
        marker.setBackground(background);
        marker.setElevation(dp(3));
        marker.setContentDescription("分析这条消息");
        return marker;
    }

    private void removeStarMarker(String key, View marker) {
        if (marker != null && marker.getParent() != null) {
            removeOverlayView(marker);
        }
        stars.remove(key);
    }

    private void hideStars() {
        for (View marker : stars.values()) {
            setStarVisibility(marker, View.INVISIBLE);
            if (marker.getParent() != null) {
                removeOverlayView(marker);
            }
        }
        clearLatestOverlayGeometry();
    }

    private void clearLatestOverlayGeometry() {
        overlayGeometryGeneration++;
        geometryReporter = null;
        getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
            .remove("latest_overlay_geometry").apply();
    }

    private void showSummaryAction(ScreenReadResult result) {
        if (windows == null || !screenAvailable()) { hideSummaryAction(); return; }
        boolean safe = false;
        for (ScreenReadResult.Message message : result.messages)
            if (!looksLikeSecret(message.text) && !message.text.trim().isEmpty()) { safe = true; break; }
        if (!safe) { hideSummaryAction(); return; }
        int chatTop = result.geometry.height;
        for (ScreenReadResult.Message message : result.messages) {
            chatTop = Math.min(chatTop, message.bounds.top);
        }
        int statusBarId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int statusBarHeight = statusBarId > 0 ? getResources().getDimensionPixelSize(statusBarId) : dp(27);
        int buttonWidth = dp(48), buttonHeight = dp(26);
        // Measured QQ header slot, left of center and beside the title, above all message rows.
        // This remains fixed across scrolling and never searches lower message whitespace.
        ChatSurfacePolicy.Position position = OverlayVisibilityPolicy.fixedSummaryPosition(result.geometry.width,
            Math.min(result.geometry.composerTop, result.geometry.editorTop), buttonWidth, buttonHeight,
            dp(104), statusBarHeight + dp(8), chatTop);
        if (BuildConfig.DEBUG) getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
            .putString("summary_layout", position == null ? "NO_SAFE_SPACE"
                : "FIXED" + ";x=" + position.x + ";y=" + position.y
                    + ";w=" + buttonWidth + ";h=" + buttonHeight).apply();
        if (position == null) { hideSummaryAction(); return; }
        if (summaryAction != null) {
            String label = "总结";
            if (!label.contentEquals(((TextView) summaryAction).getText())) ((TextView) summaryAction).setText(label);
            WindowManager.LayoutParams params = overlayParams(buttonWidth, buttonHeight, position.x, position.y);
            applyMeasuredOverlayOffset(params, position.x, position.y, result.geometry.width, buttonWidth);
            try {
                WindowManager.LayoutParams old = (WindowManager.LayoutParams) summaryAction.getLayoutParams();
                if (old.x != params.x || old.y != params.y || old.width != params.width || old.height != params.height) {
                    summaryAction.setVisibility(View.INVISIBLE);
                    windows.updateViewLayout(summaryAction, params);
                }
                correctSummaryPosition(summaryAction, position, result.geometry.width, wechatUiRevision);
            }
            catch (RuntimeException ignored) { hideSummaryAction(); }
            return;
        }
        TextView action = text("总结", 12);
        action.setGravity(Gravity.CENTER);
        action.setContentDescription("选择可见消息并总结");
        action.setBackground(cardBackground(Color.rgb(235, 242, 255), 12, Color.rgb(205, 222, 248)));
        action.setOnClickListener(v -> openSummary());
        summaryAction = action;
        action.setVisibility(View.INVISIBLE);
        action.setEnabled(false);
        // Keep the one conversation-level action separate from the bottom input region.
        try {
            WindowManager.LayoutParams params = overlayParams(buttonWidth, buttonHeight, position.x, position.y);
            applyMeasuredOverlayOffset(params, position.x, position.y, result.geometry.width, buttonWidth);
            windows.addView(action, params);
            correctSummaryPosition(action, position, result.geometry.width, wechatUiRevision);
        }
        catch (RuntimeException ignored) { summaryAction = null; }
    }

    private void correctSummaryPosition(View action, ChatSurfacePolicy.Position position, int width, long revision) {
        enqueuePlacement(new OverlayPlacement(action, null, position.x, position.y, width, dp(48),
            overlayGeometryGeneration, revision));
    }

    private void hideSummaryAction() {
        if (summaryAction != null && summaryAction.getParent() != null) {
            removeOverlayView(summaryAction);
        }
        summaryAction = null;
    }

    private void openSummary() {
        ScreenReadResult frame = visibleFrame;
        if (frame == null || frame.state != ScreenReadResult.State.READY || !isCurrentSnapshot(frame.snapshot)) return;
        ConversationSelection selection = ConversationSelection.from(frame);
        hideAll();
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        summaryCard = new ConversationSummaryCard(this, selection,
            prefs.getString("endpoint", MainActivity.DEFAULT_ENDPOINT), prefs.getString("model", MainActivity.DEFAULT_MODEL),
            modelAllowed(selection.snapshot.packageName), () -> { hideCard(); scheduleScan(); }, this::startSummary);
        card = summaryCard;
        cardTracksWeChatSurface = true;
        cardUiRevision = selection.snapshot.revision;
        cardSnapshot = selection.snapshot;
        int height = Math.max(dp(120), Math.min(dp(650), getResources().getDisplayMetrics().heightPixels - dp(140)));
        try { windows.addView(card, overlayParams(getResources().getDisplayMetrics().widthPixels - dp(24), height, dp(12), dp(70))); }
        catch (RuntimeException ignored) { hideCard(); scheduleScan(); }
    }

    private void startSummary(ConversationSelection selection) {
        if (summaryCard == null || !selection.belongsTo(cardSnapshot)
            || !modelAllowed(selection.snapshot.packageName) || !isCurrentSnapshot(selection.snapshot)) {
            if (summaryCard != null) summaryCard.showFailure("授权或聊天界面已变化，请重新选择。");
            return;
        }
        if (!SecureApiKeyStore.hasKey(this)) { summaryCard.showFailure("尚未配置模型密钥。"); return; }
        String key;
        try { key = SecureApiKeyStore.read(this); }
        catch (Exception failure) { summaryCard.showFailure("模型密钥无法解密。"); return; }
        if (key.isEmpty()) { summaryCard.showFailure("模型密钥为空。"); return; }
        final int requestGeneration = generation;
        final ConversationSummaryCard targetCard = summaryCard;
        final ChatSnapshot snapshot = selection.snapshot;
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
                ConversationSummary result = modelClient.summarize(
                    prefs.getString("endpoint", MainActivity.DEFAULT_ENDPOINT),
                    prefs.getString("model", MainActivity.DEFAULT_MODEL), key, selection, connection -> {
                        synchronized (analysisConnectionLock) {
                            if (requestGeneration != generation) throw new IllegalStateException("CANCELLED");
                            activeAnalysisConnection = connection;
                        }
                        if (!ChatSurfacePolicy.mayContinueAnalysisRequest(requestGeneration, generation,
                            modelAllowed(snapshot.packageName), key, snapshot.revision, wechatUiRevision)
                            || !isCurrentSnapshot(snapshot)) throw new IllegalStateException("CANCELLED");
                    });
                handler.post(() -> {
                    if (requestGeneration == generation && summaryCard == targetCard
                        && modelAllowed(snapshot.packageName) && isCurrentSnapshot(snapshot)) targetCard.showResult(result);
                });
            } catch (Exception failure) {
                String code = failure instanceof ChatModelClient.Failure ? ((ChatModelClient.Failure) failure).code : "REQUEST_FAILED";
                getSharedPreferences("analysis_diagnostics", MODE_PRIVATE).edit().putString("last_error_code", code).apply();
                handler.post(() -> {
                    if (requestGeneration == generation && summaryCard == targetCard && isCurrentSnapshot(snapshot))
                        targetCard.showFailure("总结未完成（" + code + "），未使用演示结果。");
                });
            } finally {
                synchronized (analysisConnectionLock) { if (requestGeneration == generation) activeAnalysisConnection = null; }
            }
        }, "summary-request").start();
    }

    private void openCard() {
        if (selectedText.isEmpty()) return;
        hideSummaryAction();
        hideStars();
        if (card != null) return;
        generation++;
        String target = selectedText;
        List<String> context = new ArrayList<>(visibleContext);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(12));
        panel.setBackground(cardBackground(Color.WHITE, 24, Color.rgb(225, 230, 238)));
        panel.setElevation(dp(10));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text("✦ 共情星分析", 22);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dismiss = text("×", 28);
        dismiss.setGravity(Gravity.CENTER);
        dismiss.setContentDescription("关闭分析");
        dismiss.setOnClickListener(v -> { hideCard(); scheduleScan(); });
        header.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(header);
        TextView subtitle = text("理解语境 · 自己决定如何回应", 12);
        subtitle.setTextColor(Color.rgb(100, 112, 130));
        subtitle.setPadding(0, 0, 0, dp(12));
        panel.addView(subtitle);
        cardBody = text("识别到的消息：\n“" + target + "”\n\n正在准备建议…", 15);
        cardResultContent = new LinearLayout(this);
        cardResultContent.setOrientation(LinearLayout.VERTICAL);
        cardResultContent.addView(cardBody);
        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setFillViewport(false);
        resultScroll.addView(cardResultContent);
        panel.addView(resultScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(8), 0, 0);
        insertButton = new Button(this);
        insertButton.setText("填入");
        insertButton.setEnabled(false);
        insertButton.setOnClickListener(v -> insertReply());
        insertButton.setTextColor(Color.WHITE);
        insertButton.setBackgroundTintList(new android.content.res.ColorStateList(
            new int[][] { new int[] { android.R.attr.state_enabled }, new int[] {} },
            new int[] { Color.rgb(43, 101, 218), Color.rgb(172, 184, 204) }));
        actions.addView(insertButton, new LinearLayout.LayoutParams(-1, dp(48)));
        panel.addView(actions);
        analysisCardStack = new AnalysisCardStack(this, panel, () -> { hideCard(); scheduleScan(); });
        card = analysisCardStack;
        cardTracksWeChatSurface = true;
        cardUiRevision = wechatUiRevision;
        cardSnapshot = new ChatSnapshot(sourcePackage, sourceWindowId, cardUiRevision);
        int cardHeight = Math.max(dp(120), Math.min(dp(620), getResources().getDisplayMetrics().heightPixels - dp(150)));
        WindowManager.LayoutParams params = overlayParams(getResources().getDisplayMetrics().widthPixels - dp(24), cardHeight, dp(12), dp(80));
        try { windows.addView(card, params); }
        catch (RuntimeException e) { hideCard(); return; }

        boolean aiDataConsent = modelAllowed(sourcePackage);
        boolean keyConfigured = SecureApiKeyStore.hasKey(this);
        if (!keyConfigured) {
            proposedReply = "";
            cardBody.setText("尚未配置模型密钥，无法生成整句理解、知识点或建议回复。请在共情星中保存模型配置后重试。");
        } else if (!ChatSurfacePolicy.shouldDecryptStoredApiKey(aiDataConsent, keyConfigured)) {
            cardBody.setText(ChatSnapshot.QQ.equals(sourcePackage)
                ? "尚未启用独立的 QQ 模型数据授权，没有发送聊天内容。请到共情星阅读并开启 QQ 模型数据授权。"
                : getString(R.string.card_ai_consent_required));
        } else {
            String key;
            try { key = SecureApiKeyStore.read(this); }
            catch (Exception error) {
                cardBody.setText(R.string.card_key_error);
                return;
            }
            if (key.isEmpty()) {
                cardBody.setText(R.string.card_key_error);
                return;
            }
            if (!ChatSurfacePolicy.maySendModelData(aiDataConsent, key)) {
                cardBody.setText(R.string.card_ai_consent_required);
                return;
            }
            cardBody.setText(getString(R.string.card_loading, target));
            int requestGeneration = generation;
            long requestUiRevision = cardUiRevision;
            ChatSnapshot requestSnapshot = cardSnapshot;
            new Thread(() -> requestAnalysis(key, target, context, requestGeneration, requestUiRevision,
                requestSnapshot), "analysis-request").start();
        }
    }

    private void requestAnalysis(String key, String target, List<String> context, int requestGeneration,
                                 long requestUiRevision, ChatSnapshot requestSnapshot) {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
            boolean aiDataConsent = modelAllowed(requestSnapshot.packageName);
            if (requestGeneration != generation) {
                throw new IllegalStateException(getString(R.string.card_request_cancelled));
            }
            if (!ChatSurfacePolicy.mayContinueAnalysisRequest(
                requestGeneration, generation, aiDataConsent, key,
                requestUiRevision, wechatUiRevision)) {
                String reason = requestGeneration == generation && !ChatSurfacePolicy.maySendModelData(aiDataConsent, key)
                    ? getString(R.string.card_ai_consent_required)
                    : getString(R.string.card_request_cancelled);
                throw new IllegalStateException(reason);
            }
            if (!isCurrentSnapshot(requestSnapshot)) throw new IllegalStateException(getString(R.string.card_request_cancelled));
            String endpoint = prefs.getString("endpoint", MainActivity.DEFAULT_ENDPOINT);
            String model = prefs.getString("model", MainActivity.DEFAULT_MODEL);
            if (!ChatSurfacePolicy.isSecureModelEndpoint(endpoint)) {
                throw new IllegalArgumentException(getString(R.string.model_endpoint_https_error));
            }
            ChatAnalysis result = modelClient.analyze(endpoint, model, key, target, context, connection -> {
                synchronized (analysisConnectionLock) {
                    if (requestGeneration != generation) {
                        throw new IllegalStateException(getString(R.string.card_request_cancelled));
                    }
                    activeAnalysisConnection = connection;
                }
                if (!ChatSurfacePolicy.mayContinueAnalysisRequest(requestGeneration, generation,
                    modelAllowed(requestSnapshot.packageName), key, requestUiRevision, wechatUiRevision)
                    || !isCurrentSnapshot(requestSnapshot)) throw new IllegalStateException(getString(R.string.card_request_cancelled));
            });
            handler.post(() -> {
                if (requestGeneration != generation || cardBody == null
                    || !ChatSurfacePolicy.isSameWeChatUiRevision(cardUiRevision, wechatUiRevision)
                    || !modelAllowed(requestSnapshot.packageName)
                    || !isCurrentSnapshot(requestSnapshot)) return;
                renderAnalysis(target, result);
            });
        } catch (Exception error) {
            String code = error instanceof ChatModelClient.Failure ? ((ChatModelClient.Failure) error).code : "REQUEST_FAILED";
            getSharedPreferences("analysis_diagnostics", MODE_PRIVATE).edit().putString("last_error_code", code).apply();
            String safe;
            if ("NETWORK_TIMEOUT".equals(code)) safe = "模型请求超时，请稍后重试";
            else if ("OUTPUT_TRUNCATED".equals(code)) safe = "模型输出被截断，未获得完整分析";
            else if (code.startsWith("HTTP_")) safe = "模型服务返回 HTTP " + code.substring(5);
            else if (code.startsWith("RESULT_") || "RESPONSE_JSON".equals(code)) safe = "模型未返回符合要求的完整分析（" + code + "）";
            else safe = "模型请求未完成，请检查网络与模型配置";
            handler.post(() -> {
                if (requestGeneration == generation && cardBody != null
                    && ChatSurfacePolicy.isSameWeChatUiRevision(cardUiRevision, wechatUiRevision)
                    && isCurrentSnapshot(requestSnapshot)) {
                    cardBody.setText(getString(R.string.card_error, target, safe));
                }
            });
        } finally {
            synchronized (analysisConnectionLock) {
                if (requestGeneration == generation) activeAnalysisConnection = null;
            }
        }
    }

    private void renderAnalysis(String source, ChatAnalysis result) {
        if (cardResultContent == null || insertButton == null) return;
        cardResultContent.removeAllViews();
        proposedReply = "";
        insertButton.setEnabled(false);
        insertButton.setText("填入所选回复");
        TextView original = text("", 14);
        original.setLineSpacing(dp(3), 1);
        original.setPadding(dp(12), dp(12), dp(12), dp(12));
        original.setBackground(cardBackground(Color.rgb(245, 247, 251), 12, Color.rgb(235, 239, 245)));
        original.setMaxLines(2);
        original.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.text.SpannableString annotated = new android.text.SpannableString(source);
        List<KnowledgeTerms.Term> terms = new ArrayList<>(result.terms);
        terms.sort((left, right) -> Integer.compare(right.text.length(), left.text.length()));
        boolean[] occupied = new boolean[source.length()];
        for (KnowledgeTerms.Term term : terms) {
            for (KnowledgeTerms.Span span : term.spans) {
                boolean overlaps = false;
                for (int i = span.start; i < span.end; i++) if (occupied[i]) overlaps = true;
                if (overlaps) continue;
                for (int i = span.start; i < span.end; i++) occupied[i] = true;
                annotated.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(View widget) {
                        showKnowledgeCard(term, source);
                    }
                    @Override public void updateDrawState(android.text.TextPaint paint) {
                        paint.setColor(Color.rgb(25, 92, 180));
                        paint.setUnderlineText(true);
                    }
                }, span.start, span.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        original.setText(annotated);
        original.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        cardResultContent.addView(original);
        TextView expandOriginal = text("展开原文 ▾", 12);
        expandOriginal.setTextColor(Color.rgb(43, 101, 218));
        expandOriginal.setMinHeight(dp(32));
        expandOriginal.setGravity(Gravity.CENTER_VERTICAL);
        expandOriginal.setOnClickListener(v -> {
            boolean expanded = original.getMaxLines() != 2;
            original.setMaxLines(expanded ? 2 : Integer.MAX_VALUE);
            original.setEllipsize(expanded ? android.text.TextUtils.TruncateAt.END : null);
            expandOriginal.setText(expanded ? "展开原文 ▾" : "收起原文 ▴");
        });
        cardResultContent.addView(expandOriginal);
        if (terms.isEmpty()) {
            cardResultContent.addView(text("本句没有额外知识点", 12));
        } else {
            HorizontalScrollView termScroll = new HorizontalScrollView(this);
            LinearLayout termRow = new LinearLayout(this);
            termRow.setOrientation(LinearLayout.HORIZONTAL);
            for (KnowledgeTerms.Term term : terms) {
                TextView entry = text("⌕ " + term.text, 13);
                entry.setTextColor(Color.rgb(43, 101, 218));
                entry.setGravity(Gravity.CENTER_VERTICAL);
                entry.setMinHeight(dp(40));
                entry.setPadding(dp(10), dp(4), dp(10), dp(4));
                entry.setBackground(cardBackground(Color.rgb(237, 245, 255), 12, Color.rgb(211, 229, 251)));
                entry.setOnClickListener(v -> showKnowledgeCard(term, source));
                LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(-2, -2);
                chip.rightMargin = dp(6);
                termRow.addView(entry, chip);
            }
            termScroll.addView(termRow);
            cardResultContent.addView(termScroll);
        }
        addCardSection("这句话的意思");
        cardBody = text(result.explanation, 15);
        cardBody.setLineSpacing(dp(2), 1);
        cardBody.setMaxLines(2);
        cardBody.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cardResultContent.addView(cardBody);
        addCardSection("建议回复");
        cardResultContent.addView(text("选择一条后填入 · 不会自动发送", 12));
        TextView selected = text("尚未选择回复", 14);
        List<Button> choices = new ArrayList<>();
        int replyNumber = 0;
        for (ChatAnalysis.Reply reply : result.replies) {
            Button choice = new Button(this);
            choices.add(choice);
            choice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            choice.setTextColor(Color.rgb(28, 50, 84));
            choice.setTextSize(15);
            choice.setPadding(dp(14), dp(12), dp(14), dp(12));
            choice.setBackground(cardBackground(Color.rgb(240, 246, 255), 14, Color.rgb(220, 232, 250)));
            choice.setAllCaps(false);
            choice.setText("#" + (++replyNumber) + "  " + reply.label + "\n\n" + reply.text);
            choice.setOnClickListener(view -> {
                proposedReply = reply.text;
                selected.setText("已选：" + reply.label);
                insertButton.setEnabled(true);
                for (Button option : choices) {
                    boolean active = option == choice;
                    option.setSelected(active);
                    option.setBackground(cardBackground(active ? Color.rgb(218, 233, 255) : Color.rgb(240, 246, 255),
                        14, active ? Color.rgb(43, 101, 218) : Color.rgb(220, 232, 250)));
                }
            });
            LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-1, -2);
            spacing.topMargin = dp(10);
            cardResultContent.addView(choice, spacing);
        }
        cardResultContent.addView(selected);
        TextView expandDetails = text("展开完整理解与沟通线索 ▾", 13);
        expandDetails.setTextColor(Color.rgb(43, 101, 218));
        expandDetails.setMinHeight(dp(44));
        expandDetails.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        TextView fullExplanation = text(result.explanation, 15);
        fullExplanation.setLineSpacing(dp(3), 1);
        details.addView(fullExplanation);
        TextView insight = text("可能的情绪与沟通需要\n\n" + result.insight, 15);
        insight.setPadding(0, dp(12), 0, dp(12));
        insight.setLineSpacing(dp(3), 1);
        details.addView(insight);
        details.setVisibility(View.GONE);
        expandDetails.setOnClickListener(v -> {
            boolean expanded = details.getVisibility() == View.VISIBLE;
            details.setVisibility(expanded ? View.GONE : View.VISIBLE);
            expandDetails.setText(expanded ? "展开完整理解与沟通线索 ▾" : "收起完整理解与沟通线索 ▴");
        });
        cardResultContent.addView(expandDetails);
        cardResultContent.addView(details);
    }

    private void showKnowledgeCard(KnowledgeTerms.Term term, String source) {
        if (analysisCardStack != null) {
            analysisCardStack.showKnowledge(term.text, term.explanation, source);
        }
    }

    private GradientDrawable cardBackground(int color, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), border);
        return drawable;
    }

    private void addCardSection(String title) {
        TextView label = text(title, 17);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, dp(18), 0, dp(8));
        cardResultContent.addView(label);
    }

    private void insertReply() {
        if (proposedReply.isEmpty()) return;
        final long expectedUiRevision = cardUiRevision;
        final String reply = proposedReply;
        final ChatSnapshot expectedSnapshot = cardSnapshot;
        if (!cardTracksWeChatSurface || !ChatSurfacePolicy.isSameWeChatUiRevision(
            expectedUiRevision, wechatUiRevision) || !isCurrentSnapshot(expectedSnapshot)) {
            hideCard();
            proposedReply = "";
            selectedText = "";
            visibleContext.clear();
            showNotice("聊天界面或应用已变化，旧建议未填入。请在当前聊天重新分析。");
            return;
        }
        hideCard();
        handler.postDelayed(() -> {
            if (!ChatSurfacePolicy.isSameWeChatUiRevision(expectedUiRevision, wechatUiRevision)
                || !isCurrentSnapshot(expectedSnapshot)) {
                proposedReply = "";
                selectedText = "";
                visibleContext.clear();
                showNotice("聊天界面或应用已变化，旧建议未填入。请在当前聊天重新分析。");
                return;
            }
            ScreenReader.FillStatus status = screenReader.fillEmptyDraft(expectedSnapshot, wechatUiRevision, reply);
            if (status == ScreenReader.FillStatus.NONEMPTY_DRAFT) {
                showNotice("输入框已有文字。为避免覆盖草稿，请先自行清空，再点 ✦ 填入。");
            } else if (status == ScreenReader.FillStatus.STALE_SNAPSHOT) {
                showNotice("聊天界面或应用已变化，旧建议未填入。请在当前聊天重新分析。");
            } else if (status != ScreenReader.FillStatus.INSERTED) {
                showNotice("未能安全填入（" + status.name() + "），请手动复制建议。");
            }
        }, 180);
    }

    private void showNotice(String message) {
        hideStars();
        hideOcrAction();
        TextView notice = text(message, 16);
        notice.setPadding(dp(16), dp(16), dp(16), dp(16));
        notice.setBackgroundColor(Color.rgb(255, 235, 207));
        card = notice;
        try { windows.addView(card, overlayParams(getResources().getDisplayMetrics().widthPixels - dp(24), WindowManager.LayoutParams.WRAP_CONTENT, dp(12), dp(80))); }
        catch (RuntimeException ignored) { card = null; }
        handler.postDelayed(() -> { hideCard(); scheduleScan(); }, 3000);
    }

    private TextView text(String value, int sp) { TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(Color.rgb(28, 32, 40)); return view; }
    private WindowManager.LayoutParams overlayParams(int width, int height, int x, int y) {
        if (!screenAvailable()) throw new IllegalStateException("SCREEN_NOT_AVAILABLE");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        boolean rtl = getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = rtl ? getResources().getDisplayMetrics().widthPixels - x - width : x;
        params.y = y;
        return params;
    }
    private int dp(int px) { return Math.round(getResources().getDisplayMetrics().density * px); }
    private void cancelActiveAnalysis() {
        HttpURLConnection connection;
        synchronized (analysisConnectionLock) {
            connection = activeAnalysisConnection;
            activeAnalysisConnection = null;
        }
        if (connection != null) connection.disconnect();
    }

    private void hideCard() {
        generation++;
        cancelActiveAnalysis();
        if (card != null && card.getParent() != null) {
            removeOverlayView(card);
        }
        card = null;
        summaryCard = null;
        analysisCardStack = null;
        cardBody = null;
        cardResultContent = null;
        insertButton = null;
        cardTracksWeChatSurface = false;
        cardUiRevision = -1;
        cardSnapshot = null;
    }
    private void hideAll() {
        fullClears++;
        pendingPlacements.clear();
        visibleFrame = null;
        hideSummaryAction();
        ocrGeneration++;
        ocrInProgress = false;
        hideOcrAction();
        hideStars();
        stars.clear();
        hideCard();
        selectedText = "";
        proposedReply = "";
        lastComposerTop = 0;
        visibleContext.clear();
    }

    private void removeOverlayView(View view) {
        // Hide before detaching: a delayed/failed WindowManager removal must not leave visible UI.
        view.setEnabled(false);
        if (stars.containsValue(view)) setStarVisibility(view, View.INVISIBLE);
        else view.setVisibility(View.INVISIBLE);
        try { windows.removeViewImmediate(view); }
        catch (RuntimeException failure) {
            getSharedPreferences("layout_diagnostics", MODE_PRIVATE).edit()
                .putString("overlay_remove_state", "REMOVE_FAILED_HIDDEN").apply();
        }
    }

    private void setStarVisibility(View marker, int visibility) {
        if (marker.getVisibility() != visibility) {
            starVisibilityChanges++;
            marker.setVisibility(visibility);
        }
    }
    private static final class MessageText {
        final String text;
        final Rect bounds;
        final int side;
        String key = "";
        List<String> context = new ArrayList<>();
        MessageText(String text, Rect bounds, int side) {
            this.text = text;
            this.bounds = bounds;
            this.side = side;
        }
    }
}
