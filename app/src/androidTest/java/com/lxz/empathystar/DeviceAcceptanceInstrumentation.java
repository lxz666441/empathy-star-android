package com.lxz.empathystar;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;

/** Independent device structure probe. Never reads node text or writes screen data. */
public final class DeviceAcceptanceInstrumentation extends Instrumentation {
    private boolean analysisContract;
    private boolean summaryContract;
    private boolean cardNavigationContract;
    private String targetPackage = "com.tencent.mm";
    private boolean structure;
    private boolean knownIdLookup;
    private boolean isolateServices;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        analysisContract = arguments != null && "true".equals(arguments.getString("analysisContract"));
        summaryContract = arguments != null && "true".equals(arguments.getString("summaryContract"));
        cardNavigationContract = arguments != null && "true".equals(arguments.getString("cardNavigationContract"));
        if (arguments != null) {
            targetPackage = arguments.getString("targetPackage", "com.tencent.mm");
            structure = "true".equals(arguments.getString("structure"));
            knownIdLookup = "true".equals(arguments.getString("knownIdLookup"));
            isolateServices = "true".equals(arguments.getString("isolateServices"));
        }
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!"com.tencent.mm".equals(targetPackage) && !"com.tencent.mobileqq".equals(targetPackage)) {
                result.putString("errorCode", "UNSUPPORTED_TARGET");
                finish(Activity.RESULT_CANCELED, result);
                return;
            }
            if (analysisContract) {
                result.putInt("checksPassed", AnalysisContractChecks.run());
                result.putInt("checksFailed", 0);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (summaryContract) {
                int[] checks = {0};
                runOnMainSync(() -> checks[0] = SummaryContractChecks.run(getTargetContext()));
                result.putInt("checksPassed", checks[0]);
                result.putInt("checksFailed", 0);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (cardNavigationContract) {
                int[] checks = {0};
                runOnMainSync(() -> checks[0] = CardNavigationChecks.run(getTargetContext()));
                result.putInt("checksPassed", checks[0]);
                result.putInt("checksFailed", 0);
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (isolateServices && !onlyProjectServicesEnabled()) {
                result.putString("errorCode", "ISOLATION_UNRELATED_SERVICE");
                finish(Activity.RESULT_CANCELED, result);
                return;
            }
            result.putBoolean("servicesTemporarilySuppressed", isolateServices);
            stage("CONNECTING");
            UiAutomation automation = getUiAutomation(isolateServices ? 0
                : UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            stage("CONNECTED");
            AccessibilityServiceInfo info = automation.getServiceInfo();
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                result.putBoolean("connectionIsAccessibilityTool", info.isAccessibilityTool());
            }
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            stage("CONFIGURED");
            // A freshly connected UiAutomation can have no active root yet.
            // Do not misreport that race as the user being outside WeChat.
            AccessibilityNodeInfo root = null;
            int attempts = 0;
            long started = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - started < 3000) {
                attempts++;
                stage("QUERY_" + attempts);
                if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache();
                root = automation.getRootInActiveWindow();
                stage("ROOT_RETURNED_" + attempts);
                Bundle sample = new Bundle();
                sample.putBoolean("rootIsWeChat", root != null && "com.tencent.mm".equals(String.valueOf(root.getPackageName())));
                sample.putInt("rootChildren", root == null ? -1 : root.getChildCount());
                sendStatus(3, sample);
                if (root != null && !targetPackage.equals(String.valueOf(root.getPackageName()))) break;
                if (root != null && root.getChildCount() > 0) break;
                SystemClock.sleep(250);
            }
            result.putInt("attempts", attempts);
            result.putLong("waitMs", SystemClock.elapsedRealtime() - started);
            boolean weChat = root != null && targetPackage.equals(String.valueOf(root.getPackageName()));
            result.putString("targetPackage", targetPackage);
            result.putBoolean("rootIsTarget", weChat);
            result.putBoolean("rootIsWeChat", root != null && "com.tencent.mm".equals(String.valueOf(root.getPackageName())));
            result.putInt("rootChildren", root == null ? -1 : root.getChildCount());
            if (weChat) {
                result.putBoolean("rootImportant", root.isImportantForAccessibility());
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    result.putBoolean("rootDataSensitive", root.isAccessibilityDataSensitive());
                }
            }
            if (!weChat) {
                result.putString("errorCode", root == null ? "NO_ACTIVE_ROOT" : "TARGET_NOT_FOREGROUND");
                sendStatus(2, result);
                finish(Activity.RESULT_CANCELED, result);
                return;
            }
            if (knownIdLookup) {
                // Ordinary public SDK lookup under the same accessibility grant.
                // Generic content ID avoids assuming the old WeChat message IDs
                // are still valid in 8.0.79. Never reads text or changes a node.
                stage("LOOKUP_CONTENT_ID");
                java.util.List<AccessibilityNodeInfo> contents = root.findAccessibilityNodeInfosByViewId("android:id/content");
                result.putInt("contentIdMatches", contents == null ? 0 : contents.size());
                int contentChildren = -1;
                if (contents != null) for (AccessibilityNodeInfo content : contents) {
                    if (content.getPackageName() != null && targetPackage.contentEquals(content.getPackageName())) {
                        contentChildren = Math.max(contentChildren, content.getChildCount());
                    }
                }
                result.putInt("contentMaxChildren", contentChildren);
                stage("CONTENT_LOOKUP_COMPLETE");
                sendStatus(5, result);
            }
            int nodes = 0;
            int editors = 0;
            int messageIds = 0;
            int matched = 0;
            int incoming = 0, outgoing = 0, unknown = 0;
            int nicknameSlots = 0;
            QqGroupAdapter groupAdapter = new QqGroupAdapter();
            ChatAppAdapter adapter = "com.tencent.mobileqq".equals(targetPackage) ? new QqAdapter() : new WeChatAdapter();
            int width = getTargetContext().getResources().getDisplayMetrics().widthPixels;
            android.graphics.Rect viewport = new android.graphics.Rect(); root.getBoundsInScreen(viewport);
            int[] composerEditors = {0}, buttons = {0}, navigation = {0};
            result.putBoolean("verifiedPrivateComposer", adapter.isComposer(root, width, viewport.bottom,
                composerEditors, buttons, navigation));
            if ("com.tencent.mobileqq".equals(targetPackage)) {
                result.putBoolean("verifiedGroupComposer", groupAdapter.isComposer(root, width, viewport.bottom,
                    new int[1], new int[1], new int[1]));
            }
            ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty() && nodes < 5000) {
                AccessibilityNodeInfo node = queue.removeFirst();
                nodes++;
                if (structure && nodes <= 400) {
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    node.getBoundsInScreen(bounds);
                    Bundle metadata = new Bundle();
                    AccessibilityNodeInfo parent = node.getParent();
                    metadata.putString("node", "id=" + node.getViewIdResourceName()
                        + ";class=" + node.getClassName() + ";children=" + node.getChildCount()
                        + ";editable=" + node.isEditable() + ";visible=" + node.isVisibleToUser()
                        + ";bounds=" + bounds.toShortString()
                        + ";parent=" + (parent == null ? "none" : parent.getViewIdResourceName()));
                    sendStatus(4, metadata);
                }
                if (node.isEditable()) editors++;
                if ("com.tencent.mm:id/bkl".equals(node.getViewIdResourceName())) messageIds++;
                if (node.isVisibleToUser() && adapter.isMessageText(node, new int[5])) {
                    if ("com.tencent.mobileqq".equals(targetPackage) && groupAdapter.nicknameSlotCount(node) == 1) nicknameSlots++;
                    matched++;
                    android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                    java.util.List<ChatSurfacePolicy.Bounds> avatars = new java.util.ArrayList<>();
                    avatarGeometry(adapter.messageRow(node), avatars, 0);
                    int side = ChatSurfacePolicy.classifyMessageSide(new ChatSurfacePolicy.Bounds(
                        bounds.left, bounds.top, bounds.right, bounds.bottom), avatars);
                    if (side == ChatSurfacePolicy.MESSAGE_SIDE_INCOMING) incoming++;
                    else if (side == ChatSurfacePolicy.MESSAGE_SIDE_OUTGOING) outgoing++;
                    else unknown++;
                }
                for (int i = 0; i < node.getChildCount() && queue.size() < 5000; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) queue.addLast(child);
                }
            }
            result.putInt("nodes", nodes);
            result.putInt("verifiedNicknameSlots", nicknameSlots);
            result.putInt("editors", editors);
            result.putInt("messageIds", messageIds);
            result.putInt("matchedTextStructures", matched);
            result.putInt("incomingStructures", incoming);
            result.putInt("outgoingStructures", outgoing);
            result.putInt("unknownStructures", unknown);
            result.putBoolean("bounded", nodes >= 5000);
            sendStatus(2, result);
            finish(Activity.RESULT_OK, result);
        } catch (RuntimeException error) {
            result.putString("errorCode", error.getClass().getSimpleName());
            sendStatus(2, result);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private boolean onlyProjectServicesEnabled() {
        String enabled = android.provider.Settings.Secure.getString(
            getTargetContext().getContentResolver(),
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return true;
        for (String service : enabled.split(":")) {
            android.content.ComponentName component = android.content.ComponentName.unflattenFromString(service);
            if (component == null) return false;
            String owner = component.getPackageName();
            if (!"com.lxz.empathystar".equals(owner) && !"com.jev.probe".equals(owner)) return false;
        }
        return true;
    }

    private void stage(String value) {
        Bundle status = new Bundle();
        status.putString("probeStage", value);
        sendStatus(1, status);
    }

    private void avatarGeometry(AccessibilityNodeInfo node, java.util.List<ChatSurfacePolicy.Bounds> out, int depth) {
        if (node == null || depth > 12 || out.size() > 8) return;
        if (ChatNodeProfile.classIs(node.getClassName(), "android.widget.ImageView")
            || ChatNodeProfile.classIs(node.getClassName(), "android.widget.ImageButton")) {
            boolean insideAvatar = !"com.tencent.mobileqq".equals(targetPackage)
                || ChatAppAdapter.ancestor(node, "com.tencent.mobileqq:id/wgh") != null;
            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
            float density = getTargetContext().getResources().getDisplayMetrics().density;
            if (insideAvatar && node.isVisibleToUser() && bounds.width() >= 28 * density && bounds.width() <= 72 * density
                && bounds.height() >= 28 * density && bounds.height() <= 72 * density
                && bounds.width() <= bounds.height() * 1.25f && bounds.height() <= bounds.width() * 1.25f) {
                out.add(new ChatSurfacePolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom));
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) avatarGeometry(node.getChild(i), out, depth + 1);
    }
}
