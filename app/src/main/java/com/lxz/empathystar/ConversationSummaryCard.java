package com.lxz.empathystar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local selection -> explicit upload -> summary -> source. Never reads nodes or sends replies. */
final class ConversationSummaryCard extends FrameLayout {
    interface Submit { void run(ConversationSelection selected); }
    private final ConversationSelection visible;
    private final Runnable close;
    private final Submit submit;
    private final boolean modelAllowed;
    private final Set<Integer> selectedIds = new HashSet<>();
    private final List<CheckBox> choices = new ArrayList<>();
    private final List<Button> tools = new ArrayList<>();
    private final String provider, model;
    private TextView count;
    private CheckBox consent;
    private Button run;
    private boolean busy;
    private ConversationSelection submitted;
    private View mainPage, sourcePage;

    ConversationSummaryCard(Context context, ConversationSelection visible, String endpoint, String model,
                            boolean modelAllowed, Runnable close, Submit submit) {
        super(context);
        this.visible = visible; this.close = close; this.submit = submit;
        this.modelAllowed = modelAllowed; this.model = model;
        String host;
        try { host = URI.create(endpoint).getHost(); } catch (RuntimeException invalid) { host = null; }
        this.provider = host == null ? "无效模型地址" : host;
        showPreview();
    }

    private void showPreview() {
        LinearLayout panel = panel();
        panel.addView(header("选择会话消息", null));
        panel.addView(label("仅当前屏幕可见文字，不是完整会话或全部未读。图片/语音/文件未纳入；"
            + (visible.group ? "群成员显示名不等于唯一身份，未取得时不猜姓名。" : "说话人姓名未识别。"), 12));
        count = label("", 13); panel.addView(count);
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout messages = column();
        for (ConversationSelection.Entry entry : visible.entries) {
            CheckBox choice = new CheckBox(getContext());
            choice.setTag("summary_choice_" + entry.id);
            choice.setText("#" + entry.id + " · " + entry.speaker() + "\n" + entry.text);
            choice.setTextSize(14); choice.setTextColor(Color.rgb(28, 40, 60));
            choice.setPadding(0, dp(6), 0, dp(6));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedIds.add(entry.id); else selectedIds.remove(entry.id);
                refreshCount();
            });
            choices.add(choice); messages.addView(choice);
        }
        if (visible.entries.isEmpty()) messages.addView(label("没有可选择的安全文字消息。", 15));
        scroll.addView(messages); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tools = new LinearLayout(getContext());
        Button all = button("全选");
        all.setOnClickListener(v -> { for (CheckBox choice : choices) choice.setChecked(true); });
        Button none = button("清空选择");
        none.setOnClickListener(v -> { for (CheckBox choice : choices) choice.setChecked(false); });
        tools.addView(all, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(none, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(tools);
        this.tools.add(all); this.tools.add(none);
        consent = new CheckBox(getContext());
        consent.setTag("summary_consent");
        consent.setText("仅本次：同意将已勾选原文、方向及已取得的显示名发送到 " + provider + " / " + model
            + " 生成总结，可能收费。服务商可能保留内容；关闭只能尽力取消，不能撤回已送达数据。");
        consent.setTextSize(12);
        consent.setOnCheckedChangeListener((button, checked) -> refreshCount());
        panel.addView(consent);
        if (!modelAllowed) panel.addView(label("还需在共情星设置中开启当前应用的模型数据授权。", 12));
        run = button("总结已选消息");
        run.setTag("summary_submit");
        run.setOnClickListener(v -> {
            if (busy || !consent.isChecked() || !modelAllowed) return;
            try {
                submitted = visible.select(selectedIds);
                busy = true; refreshCount(); submit.run(submitted);
            } catch (IllegalArgumentException invalid) {
                count.setText("请选择1—30条消息，总文字不超过12000字符；未发送。");
            }
        });
        panel.addView(run, new LinearLayout.LayoutParams(-1, dp(48)));
        mainPage = panel; addView(panel, new LayoutParams(-1, -1)); refreshCount();
    }

    private void refreshCount() {
        int characters = 0;
        for (ConversationSelection.Entry entry : visible.entries) if (selectedIds.contains(entry.id)) characters += entry.text.length();
        boolean valid = !selectedIds.isEmpty() && selectedIds.size() <= ConversationSelection.MAX_MESSAGES
            && characters <= ConversationSelection.MAX_CHARACTERS;
        count.setText((busy ? "正在总结，关闭会尽力取消请求。\n" : "")
            + "可选 " + visible.entries.size() + " 条；敏感/空白过滤 " + visible.filteredCount + " 条\n"
            + "已选 " + selectedIds.size() + " 条 / " + characters + " 字符（上限30条 / 12000字符）"
            + (!valid && !selectedIds.isEmpty() ? "\n已超上限，请取消部分选择；未静默截断。" : ""));
        if (run != null) run.setEnabled(!busy && valid && modelAllowed && consent != null && consent.isChecked());
        if (consent != null) consent.setEnabled(!busy);
        for (CheckBox choice : choices) choice.setEnabled(!busy);
        for (Button tool : tools) tool.setEnabled(!busy);
    }

    void showFailure(String safe) {
        busy = false; refreshCount();
        count.setText(safe + "\n没有有效总结；未填入、未发送回复。可调整选择后重试。");
    }

    void showResult(ConversationSummary result) {
        removeAllViews(); sourcePage = null;
        LinearLayout panel = panel();
        panel.addView(header("会话总结", null));
        panel.addView(label("仅总结本次所选 " + submitted.entries.size()
            + " 条可见文字；AI归纳可能有误，点编号核对原文。", 12));
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout body = column();
        for (ConversationSummary.Section section : ConversationSummary.Section.values()) {
            TextView title = label(section.title, 17); title.setTypeface(null, Typeface.BOLD);
            title.setPadding(0, dp(14), 0, dp(6)); body.addView(title);
            int found = 0;
            for (ConversationSummary.Item item : result.items) if (item.section == section) {
                found++; body.addView(label(item.text, 15));
                LinearLayout references = new LinearLayout(getContext());
                for (int id : item.sources) {
                    TextView source = label("  #" + id + "  ", 13);
                    source.setTextColor(Color.rgb(43, 101, 218)); source.setMinHeight(dp(40));
                    source.setGravity(Gravity.CENTER_VERTICAL);
                    source.setContentDescription("查看来源消息" + id);
                    source.setOnClickListener(v -> showSource(id)); references.addView(source);
                }
                android.widget.HorizontalScrollView row = new android.widget.HorizontalScrollView(getContext());
                row.addView(references); body.addView(row);
            }
            if (found == 0) body.addView(label("所选内容没有足够明确的信息。", 13));
        }
        scroll.addView(body); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        mainPage = panel; addView(panel, new LayoutParams(-1, -1));
    }

    private void showSource(int id) {
        if (submitted == null) return;
        ConversationSelection.Entry entry = submitted.find(id); if (entry == null) return;
        if (sourcePage != null) removeView(sourcePage);
        LinearLayout panel = panel();
        panel.addView(header("来源 #" + id, () -> {
            removeView(sourcePage); sourcePage = null; mainPage.setVisibility(VISIBLE);
        }));
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout body = column();
        body.addView(label(entry.speaker(), 13)); body.addView(label(entry.text, 17));
        body.addView(label("仅核对本次已选原文，不新增模型请求。", 12));
        scroll.addView(body); panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        mainPage.setVisibility(INVISIBLE); sourcePage = panel; addView(panel, new LayoutParams(-1, -1));
    }

    private LinearLayout header(String title, Runnable back) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label(back == null ? title : "‹ 返回总结 · " + title, back == null ? 20 : 15);
        heading.setTypeface(null, Typeface.BOLD); heading.setMinHeight(dp(48)); heading.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) heading.setOnClickListener(v -> back.run());
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dismiss = label("×", 26); dismiss.setGravity(Gravity.CENTER);
        dismiss.setContentDescription("关闭会话总结"); dismiss.setOnClickListener(v -> close.run());
        row.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48))); return row;
    }
    private LinearLayout panel() {
        LinearLayout panel = column(); panel.setPadding(dp(14), dp(10), dp(14), dp(12)); panel.setClickable(true);
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE);
        background.setCornerRadius(dp(20)); panel.setBackground(background); return panel;
    }
    private LinearLayout column() { LinearLayout value = new LinearLayout(getContext()); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView label(String value, int size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(28, 40, 60)); view.setLineSpacing(dp(2), 1); return view;
    }
    private Button button(String title) { Button view = new Button(getContext()); view.setText(title); return view; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
