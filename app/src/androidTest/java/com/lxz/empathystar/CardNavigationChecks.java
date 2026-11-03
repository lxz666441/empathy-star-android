package com.lxz.empathystar;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Android view checks using synthetic content only; never connects UiAutomation or a model. */
final class CardNavigationChecks {
    static int run(Context context) {
        LinearLayout analysis = new LinearLayout(context);
        analysis.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        View longContent = new View(context);
        longContent.setMinimumHeight(2400);
        scroll.addView(longContent, new ScrollView.LayoutParams(-1, 2400));
        analysis.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button reply = new Button(context);
        reply.setSelected(true);
        reply.setText("Synthetic selected reply");
        analysis.addView(reply, new LinearLayout.LayoutParams(-1, 80));
        int[] closed = {0};
        AnalysisCardStack stack = new AnalysisCardStack(context, analysis, () -> closed[0]++);
        int width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY);
        stack.measure(width, height);
        stack.layout(0, 0, 600, 900);
        scroll.scrollTo(0, 320);
        int savedScroll = scroll.getScrollY();
        check(savedScroll == 320, "SCROLL_FIXTURE");
        stack.showKnowledge("Concept A", "Contextual meaning A", "Synthetic source A");
        check(stack.isShowingKnowledge() && stack.getChildCount() == 2
            && analysis.getVisibility() == View.INVISIBLE, "DETAIL_COVERS_ANALYSIS");
        check(!reply.isShown(), "REPLY_NOT_VISIBLE_IN_DETAIL");
        stack.showKnowledge("Concept B", "Contextual meaning B", "Synthetic source B");
        check(stack.getChildCount() == 2, "DETAIL_REPLACES_PREVIOUS_DETAIL");
        stack.findViewWithTag("knowledge_back").performClick();
        check(!stack.isShowingKnowledge() && stack.getChildCount() == 1
            && analysis.getVisibility() == View.VISIBLE, "BACK_RETURNS_TO_ANALYSIS");
        check(reply.isSelected() && "Synthetic selected reply".contentEquals(reply.getText())
            && scroll.getScrollY() == savedScroll, "BACK_PRESERVES_REPLY_AND_SCROLL");
        stack.showKnowledge("Concept C", "Meaning C", "Synthetic source C");
        stack.findViewWithTag("knowledge_close").performClick();
        check(closed[0] == 1, "CLOSE_EXITS_ANALYSIS");
        return 7;
    }

    private static void check(boolean passed, String code) {
        if (!passed) throw new IllegalStateException(code);
    }
}
