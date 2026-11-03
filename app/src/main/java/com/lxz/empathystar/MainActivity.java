package com.lxz.empathystar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

public final class MainActivity extends Activity {
    static final String PREFS = "settings";
    static final String AI_DATA_CONSENT = "ai_data_consent";
    static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    static final String DEFAULT_MODEL = "deepseek-flash";
    private TextView status;
    private EditText key;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, pad);
        scroll.addView(box);
        setContentView(scroll);
        setLightStatusBarIcons();
        if (Build.VERSION.SDK_INT >= 30) {
            box.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets system = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(pad + system.left, pad + system.top,
                    pad + system.right, pad + Math.max(system.bottom, ime.bottom));
                return insets;
            });
            scroll.requestApplyInsets();
        }

        TextView title = label("共情星 · QQ / 微信试用版", 24);
        box.addView(title);
        box.addView(label("分别暂停读取", 18));
        addPauseControl(box, ChatSnapshot.WECHAT, "暂停微信读取");
        addPauseControl(box, ChatSnapshot.QQ, "暂停 QQ 读取");
        box.addView(label(getString(R.string.app_version_intro), 16));
        box.addView(label(getString(R.string.summary_disclosure), 14));

        CheckBox consent = new CheckBox(this);
        consent.setText(R.string.accessibility_consent_text);
        consent.setChecked(getPreferences().getBoolean("consent", false));
        box.addView(consent);
        consent.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean("consent", checked).apply();
            refreshStatus();
        });

        CheckBox qqConsent = new CheckBox(this);
        qqConsent.setText(R.string.qq_accessibility_consent_text);
        qqConsent.setChecked(getPreferences().getBoolean(ChatSnapshot.readConsentKey(ChatSnapshot.QQ), false));
        box.addView(qqConsent);
        qqConsent.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean(ChatSnapshot.readConsentKey(ChatSnapshot.QQ), checked).apply();
            refreshStatus();
        });

        CheckBox qqGroupConsent = new CheckBox(this);
        qqGroupConsent.setText(R.string.qq_group_consent_text);
        qqGroupConsent.setChecked(getPreferences().getBoolean("qq_group_consent", false));
        qqGroupConsent.setOnCheckedChangeListener((button, checked) ->
            getPreferences().edit().putBoolean("qq_group_consent", checked).apply());
        box.addView(qqGroupConsent);

        Button permission = new Button(this);
        permission.setText("打开无障碍设置，启用共情星");
        permission.setOnClickListener(v -> {
            if (!consent.isChecked() && !qqConsent.isChecked()) {
                status.setText("请先阅读并勾选上面的授权说明。");
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        box.addView(permission);

        status = label("", 16);
        box.addView(status);
        Button refresh = new Button(this);
        refresh.setText("刷新识别状态");
        refresh.setOnClickListener(v -> refreshStatus());
        box.addView(refresh);
        box.addView(label("模型设置（可选；不填也能测试识别、卡片和填入）", 18));
        TextView modelPriceNotice = label(getString(R.string.default_model_paid_notice), 15);
        modelPriceNotice.setTextColor(0xff8a3d00);
        box.addView(modelPriceNotice);

        CheckBox aiConsent = new CheckBox(this);
        aiConsent.setText("微信 " + getString(R.string.ai_data_consent_text));
        aiConsent.setChecked(getPreferences().getBoolean(AI_DATA_CONSENT, false));
        box.addView(aiConsent);
        aiConsent.setOnCheckedChangeListener((button, checked) ->
            getPreferences().edit().putBoolean(AI_DATA_CONSENT, checked).apply());

        CheckBox qqAiConsent = new CheckBox(this);
        qqAiConsent.setText(R.string.qq_ai_data_consent_text);
        qqAiConsent.setChecked(getPreferences().getBoolean(ChatSnapshot.modelConsentKey(ChatSnapshot.QQ), false));
        box.addView(qqAiConsent);
        qqAiConsent.setOnCheckedChangeListener((button, checked) ->
            getPreferences().edit().putBoolean(ChatSnapshot.modelConsentKey(ChatSnapshot.QQ), checked).apply());

        EditText endpoint = field("完整的 HTTPS Chat Completions 地址", getPreferences().getString("endpoint", DEFAULT_ENDPOINT), false);
        box.addView(endpoint);
        EditText model = field("模型名称", getPreferences().getString("model", DEFAULT_MODEL), false);
        box.addView(model);
        key = field("API Key（填写一次，加密保存在本机）", "", true);
        box.addView(key);
        Button save = new Button(this);
        save.setText("保存模型设置和密钥");
        save.setOnClickListener(v -> {
            String url = endpoint.getText().toString().trim();
            if (!ChatSurfacePolicy.isSecureModelEndpoint(url)) {
                status.setText(R.string.model_endpoint_https_error);
                return;
            }
            String enteredKey = key.getText().toString().trim();
            if (!enteredKey.isEmpty()) {
                try { SecureApiKeyStore.save(this, enteredKey); }
                catch (Exception error) { status.setText(R.string.save_key_error); return; }
            }
            getPreferences().edit().putString("endpoint", url).putString("model", model.getText().toString().trim()).apply();
            key.setText("");
            refreshStatus();
        });
        box.addView(save);
        if (BuildConfig.DEBUG) {
            Button usbImport = new Button(this);
            usbImport.setText(R.string.usb_key_import_button);
            usbImport.setOnClickListener(v -> startUsbKeyImport());
            box.addView(usbImport);
        }
        Button clear = new Button(this);
        clear.setText(R.string.clear_key);
        clear.setOnClickListener(v -> { SecureApiKeyStore.clear(this); key.setText(""); refreshStatus(); });
        box.addView(clear);

        Button privacy = new Button(this);
        privacy.setText(R.string.privacy_button);
        privacy.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(getString(R.string.privacy_in_app) + "\n\n" + getString(R.string.summary_disclosure))
            .setPositiveButton(android.R.string.ok, null)
            .show());
        box.addView(privacy);

        box.addView(label(getString(R.string.test_instructions), 16));
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra("usb_key_import", false)) startUsbKeyImport();
        refreshStatus();
    }

    @Override protected void onResume() { super.onResume(); if (status != null) refreshStatus(); }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (BuildConfig.DEBUG && intent.getBooleanExtra("usb_key_import", false)) startUsbKeyImport();
    }

    private void startUsbKeyImport() {
        UsbApiKeyImporter.start(this, message -> runOnUiThread(() -> status.setText(message)));
    }

    private void setLightStatusBarIcons() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private android.content.SharedPreferences getPreferences() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private void addPauseControl(LinearLayout box, String packageName, String title) {
        CheckBox pause = new CheckBox(this);
        pause.setText(title);
        pause.setChecked(getPreferences().getBoolean(ChatReadScope.pausedKey(packageName), false));
        pause.setOnCheckedChangeListener((button, checked) -> {
            getPreferences().edit().putBoolean(ChatReadScope.pausedKey(packageName), checked).apply();
            refreshStatus();
        });
        box.addView(pause);
    }
    private void refreshStatus() {
        boolean consented = getPreferences().getBoolean("consent", false);
        boolean qqConsented = getPreferences().getBoolean(ChatSnapshot.readConsentKey(ChatSnapshot.QQ), false);
        if (!consented && !qqConsented) status.setText("状态：尚未同意读取 QQ 或微信界面。");
        else if (!serviceEnabled()) status.setText(R.string.status_service_disabled);
        else {
            long connectedAt = getPreferences().getLong("service_connected_at", 0);
            long eventAt = getPreferences().getLong("wechat_event_at", 0);
            long scanAt = getPreferences().getLong("wechat_scan_at", 0);
            String detail = getPreferences().getString("wechat_scan_result", "尚无微信内扫描记录");
            String treeState = getPreferences().getString("wechat_tree_state", "unknown");
            if ("empty_tree".equals(treeState)) {
                detail = getString(R.string.status_empty_wechat_tree) + "\n" + detail;
            } else if ("no_window".equals(treeState)) {
                detail = getString(R.string.status_no_wechat_window) + "\n" + detail;
            }
            String qqTree = getPreferences().getString("qq_tree_state", "unknown");
            String qqDetail = getPreferences().getString("qq_scan_result", "尚无QQ扫描记录");
            String qqStatus = !qqConsented ? "QQ：读取授权未开启"
                : getPreferences().getBoolean(ChatReadScope.pausedKey(ChatSnapshot.QQ), false) ? "QQ：已暂停读取"
                : "QQ：" + ("empty_tree".equals(qqTree) ? "读取受限：应用返回空窗口，不能显示星标。\n" : "")
                    + qqDetail + "（" + when(getPreferences().getLong("qq_scan_at", 0)) + "）";
            String weChatStatus = !consented ? "微信：读取授权未开启"
                : getPreferences().getBoolean(ChatReadScope.pausedKey(ChatSnapshot.WECHAT), false) ? "微信：已暂停读取"
                : getString(R.string.status_active, when(connectedAt), when(eventAt),
                detail, when(scanAt), getString(SecureApiKeyStore.hasKey(this)
                    ? R.string.api_key_present : R.string.api_key_missing));
            status.setText(qqStatus + "\n\n" + weChatStatus);
        }
    }
    private String when(long at) {
        if (at == 0) return "无记录";
        return android.text.format.DateFormat.format("HH:mm:ss", at) + "，约 "
            + Math.max(0, (System.currentTimeMillis() - at) / 1000) + " 秒前";
    }
    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String entry : enabled.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(entry);
            if (component != null && getPackageName().equals(component.getPackageName())
                && (WeChatService.class.getName().equals(component.getClassName())
                    || (BuildConfig.DEBUG && "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
                        .equals(component.getClassName())))) return true;
        }
        return false;
    }
    private TextView label(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setPadding(0, dp(10), 0, dp(10));
        return view;
    }
    private EditText field(String hint, String value, boolean password) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setText(value);
        view.setSingleLine(true);
        view.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return view;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
