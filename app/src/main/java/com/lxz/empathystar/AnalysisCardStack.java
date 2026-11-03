package com.lxz.empathystar;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Local navigation inside one overlay window; the original analysis view stays intact. */
final class AnalysisCardStack extends FrameLayout {
    private final View analysis;
    private final Runnable close;
    private View explanation;

    AnalysisCardStack(Context context, View analysis, Runnable close) {
        super(context);
        this.analysis = analysis;
        this.close = close;
        addView(analysis, new LayoutParams(-1, -1));
    }

    void showKnowledge(String term, String meaning, String source) {
        if (explanation != null) removeView(explanation);
        LinearLayout page = new LinearLayout(getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), Color.rgb(225, 230, 238));
        page.setBackground(background);
        // Consume blank-area taps too: the underlying reply controls must not receive them.
        page.setClickable(true);
        page.setContentDescription("知识点解释页面");

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹ 返回分析", 16);
        back.setTag("knowledge_back");
        back.setTextColor(Color.rgb(43, 101, 218));
        back.setMinHeight(dp(48));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> returnToAnalysis());
        header.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dismiss = label("×", 28);
        dismiss.setContentDescription("关闭分析");
        dismiss.setTag("knowledge_close");
        dismiss.setGravity(Gravity.CENTER);
        dismiss.setOnClickListener(v -> close.run());
        header.addView(dismiss, new LinearLayout.LayoutParams(dp(48), dp(48)));
        page.addView(header);

        ScrollView scroll = new ScrollView(getContext());
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(term, 24);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(0, dp(12), 0, dp(16));
        body.addView(title);
        addSection(body, "本句中的含义", meaning);
        addSection(body, "所在原文", source);
        TextView note = label("释义来自本次分析结果", 12);
        note.setTextColor(Color.rgb(100, 112, 130));
        note.setPadding(0, dp(16), 0, dp(8));
        body.addView(note);
        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        analysis.setVisibility(View.INVISIBLE);
        explanation = page;
        addView(page, new LayoutParams(-1, -1));
        back.requestFocus();
    }

    void returnToAnalysis() {
        if (explanation != null) removeView(explanation);
        explanation = null;
        analysis.setVisibility(View.VISIBLE);
    }

    boolean isShowingKnowledge() { return explanation != null; }

    private void addSection(LinearLayout body, String heading, String content) {
        TextView subtitle = label(heading, 13);
        subtitle.setTextColor(Color.rgb(100, 112, 130));
        subtitle.setPadding(0, dp(16), 0, dp(8));
        body.addView(subtitle);
        TextView text = label(content, 17);
        text.setLineSpacing(dp(4), 1);
        body.addView(text);
    }

    private TextView label(String value, int size) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(28, 40, 60));
        return view;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
