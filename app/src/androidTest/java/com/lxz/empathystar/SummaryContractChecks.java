package com.lxz.empathystar;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Synthetic Android JSON and local-view checks. No accessibility connection or network. */
final class SummaryContractChecks {
    static int run(Context context) {
        try {
            ScreenReadResult frame = new ScreenReadResult(ScreenReadResult.State.READY,
                new ChatSnapshot(ChatSnapshot.QQ, 12, 3), new ScreenReadResult.Geometry(1080, 2400, 2200, 2220, false),
                ScreenReadResult.Metrics.empty(), Arrays.asList(
                    new ScreenReadResult.Message("synthetic first", new ChatSurfacePolicy.Bounds(100, 200, 600, 300), 1),
                    new ScreenReadResult.Message("synthetic second", new ChatSurfacePolicy.Bounds(100, 350, 600, 450), 2)));
            ConversationSelection visible = ConversationSelection.from(frame);
            ConversationSelection chosen = visible.select(new HashSet<>(Collections.singletonList(1)));
            JSONObject good = new JSONObject().put("topics", new JSONArray().put(new JSONObject()
                .put("text", "synthetic topic").put("sources", new JSONArray().put(1))))
                .put("decisions", new JSONArray()).put("open_questions", new JSONArray())
                .put("actions", new JSONArray()).put("for_me", new JSONArray());
            ConversationSummary summary = ConversationSummary.parse(good.toString(), chosen);
            require(summary.items.size() == 1, "PARSE");
            require(ConversationSummary.parse("```json\n" + good + "\n```", chosen).items.size() == 1, "FENCED_PARSE");
            reject(new JSONObject(good.toString()).put("actions", "wrong").toString(), chosen, "BAD_ARRAY");
            reject(new JSONObject(good.toString()).put("topics", new JSONArray().put(new JSONObject()
                .put("text", "synthetic topic").put("sources", new JSONArray().put(2)))).toString(), chosen, "UNSELECTED_REF");
            reject(new JSONObject(good.toString()).put("topics", new JSONArray().put(new JSONObject()
                .put("text", "synthetic topic").put("sources", new JSONArray().put(1.5)))).toString(), chosen, "NONINTEGER_REF");
            String prompt = ConversationSummary.userPrompt(chosen);
            require(prompt.contains("synthetic first") && !prompt.contains("synthetic second"), "SELECTED_ONLY");
            int[] submissions = {0}, closes = {0};
            ConversationSummaryCard card = new ConversationSummaryCard(context, visible,
                "https://api.example.invalid/chat/completions", "synthetic-model", true,
                () -> closes[0]++, selected -> { require(selected.entries.size() == 1, "SUBMIT_RANGE"); submissions[0]++; });
            CheckBox first = card.findViewWithTag("summary_choice_1");
            CheckBox consent = card.findViewWithTag("summary_consent");
            Button submit = card.findViewWithTag("summary_submit");
            require(!first.isChecked() && !consent.isChecked() && !submit.isEnabled(), "DEFAULT_NO_UPLOAD");
            first.setChecked(true); require(!submit.isEnabled() && submissions[0] == 0, "SELECTION_NO_UPLOAD");
            consent.setChecked(true); require(submit.isEnabled() && submissions[0] == 0, "CONSENT_NO_UPLOAD");
            submit.performClick(); require(submissions[0] == 1 && !submit.isEnabled(), "EXPLICIT_ONCE");
            card.showResult(summary);
            View source = findDescription(card, "查看来源消息1");
            require(source != null, "SOURCE_CONTROL");
            source.performClick(); require(findText(card, "synthetic first"), "SOURCE_PAGE");
            require(submissions[0] == 1, "SOURCE_NO_REQUEST");
            ConversationSummaryCard denied = new ConversationSummaryCard(context, visible,
                "https://api.example.invalid/chat/completions", "synthetic-model", false, () -> {}, selected -> submissions[0]++);
            ((CheckBox) denied.findViewWithTag("summary_choice_1")).setChecked(true);
            ((CheckBox) denied.findViewWithTag("summary_consent")).setChecked(true);
            require(!((Button) denied.findViewWithTag("summary_submit")).isEnabled(), "PLATFORM_CONSENT_REQUIRED");
            return 14;
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("SUMMARY_CONTRACT_FAILED"); }
    }
    private static void reject(String json, ConversationSelection chosen, String code) {
        boolean rejected = false;
        try { ConversationSummary.parse(json, chosen); } catch (Exception expected) { rejected = true; }
        require(rejected, code);
    }
    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findDescription(((ViewGroup) root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }
    private static boolean findText(View root, String text) {
        if (root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) root).getText())) return true;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (findText(((ViewGroup) root).getChildAt(i), text)) return true;
        return false;
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalStateException("SUMMARY_" + code);
    }
}
