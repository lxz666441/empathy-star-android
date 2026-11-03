package com.lxz.empathystar;

import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic parser tests run in the independent package, without network or UI. */
final class AnalysisContractChecks {
    static int run() throws RuntimeException {
        try {
            String source = "使用 RAG 和 GPT，补充外部知识。";
            JSONObject good = new JSONObject().put("explanation", "结合外部资料理解内容")
                .put("insight", "信息有限，可能是在介绍方法")
                .put("replies", new JSONArray()
                    .put(new JSONObject().put("label", "自然回应").put("text", "了解了。"))
                    .put(new JSONObject().put("label", "温和回应").put("text", "谢谢说明。"))
                    .put(new JSONObject().put("label", "澄清确认").put("text", "能举个例子吗？")))
                .put("terms", new JSONArray()
                    .put(new JSONObject().put("text", "RAG").put("explanation", "检索外部资料辅助生成"))
                    .put(new JSONObject().put("text", "PT").put("explanation", "无效碎片"))
                    .put(new JSONObject().put("text", "不存在的术语").put("explanation", "无效候选")));
            ChatAnalysis parsed = ChatAnalysis.parse(good.toString(), source);
            require(parsed.replies.size() == 3 && parsed.terms.size() == 1 && parsed.terms.get(0).text.equals("RAG"));
            expectRejected("not-json", source);
            JSONObject missing = new JSONObject(good.toString());
            missing.remove("explanation");
            expectRejected(missing.toString(), source);
            JSONObject duplicate = new JSONObject(good.toString());
            duplicate.getJSONArray("replies").getJSONObject(1).put("text", "了解了。");
            expectRejected(duplicate.toString(), source);
            JSONObject wrongType = new JSONObject(good.toString()).put("terms", "invalid");
            expectRejected(wrongType.toString(), source);
            JSONObject noTerms = new JSONObject(good.toString()).put("terms", new JSONArray());
            require(ChatAnalysis.parse(noTerms.toString(), "今天挺开心。").terms.isEmpty());
            require(ChatAnalysis.parse("```json\n" + good + "\n```", source).replies.size() == 3);
            return 7;
        } catch (Exception failure) { throw new IllegalStateException("ANALYSIS_CONTRACT_FAILED"); }
    }
    private static void expectRejected(String json, String source) throws Exception {
        boolean rejected = false;
        try { ChatAnalysis.parse(json, source); } catch (Exception expected) { rejected = true; }
        require(rejected);
    }
    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("ANALYSIS_CONTRACT_FAILED");
    }
}
