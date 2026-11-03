package com.lxz.empathystar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-grounded concept spans; independent of Android, providers and storage. */
final class KnowledgeTerms {
    private static final Set<String> SHORT_ACRONYMS = new HashSet<>(Arrays.asList(
        "ai", "ar", "bi", "ci", "cd", "db", "hr", "ip", "it", "js", "ml",
        "mr", "os", "pc", "pr", "qa", "ts", "ui", "ux", "vr"));
    private static final Set<String> MUNDANE = new HashSet<>(Arrays.asList(
        "你好", "好的", "收到", "谢谢", "可以", "然后", "但是", "所以", "已经",
        "毫秒", "秒", "分钟", "小时", "天", "周", "月", "年", "帧", "像素",
        "时间", "日期", "画面", "界面", "窗口", "按钮", "菜单", "文字", "内容",
        "问题", "结果", "状态", "功能", "模式", "版本", "用户", "程序", "软件",
        "开始", "结束", "继续", "点击", "选择", "打开", "关闭", "移动", "滚动",
        "变化", "重新", "当前", "默认", "自动", "手动", "简单", "普通", "模型",
        "知识点", "术语", "消息解释", "the", "and", "hello", "message", "click",
        "window", "text", "second", "seconds", "ms", "minute", "minutes"));

    static final class Candidate {
        final String text;
        final String explanation;
        Candidate(String text, String explanation) { this.text = text; this.explanation = explanation; }
    }
    static final class Span {
        final int start;
        final int end;
        Span(int start, int end) { this.start = start; this.end = end; }
    }
    static final class Term {
        final String text;
        final String explanation;
        final List<Span> spans;
        Term(String text, String explanation, List<Span> spans) {
            this.text = text;
            this.explanation = explanation;
            this.spans = Collections.unmodifiableList(new ArrayList<>(spans));
        }
    }

    static List<Term> validate(String source, List<Candidate> candidates) {
        if (source == null || source.isEmpty() || candidates == null) return Collections.emptyList();
        Map<String, Term> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates.subList(0, Math.min(25, candidates.size()))) {
            if (candidate == null || candidate.text == null || candidate.explanation == null) continue;
            String requested = candidate.text.trim();
            String meaning = candidate.explanation.trim().replaceAll("\\s+", " ");
            String identity = requested.toLowerCase(Locale.ROOT);
            if (requested.length() < 2 || requested.length() > 80 || meaning.isEmpty()
                || meaning.length() > 400 || MUNDANE.contains(identity) || unique.containsKey(identity)
                || (requested.matches("[A-Z]{2}") && !SHORT_ACRONYMS.contains(identity))
                || requested.matches("\\d+(?:\\.\\d+)?(?:毫秒|秒|分钟|小时|天|周|月|年|帧|像素)")) continue;
            List<Span> spans = occurrences(source, requested);
            if (spans.isEmpty()) continue;
            Span first = spans.get(0);
            unique.put(identity, new Term(source.substring(first.start, first.end), meaning, spans));
        }
        List<Term> all = new ArrayList<>(unique.values());
        List<Term> retained = new ArrayList<>();
        for (Term term : all) {
            boolean onlyNested = true;
            for (Span span : term.spans) {
                boolean covered = false;
                for (Term other : all) {
                    if (other.text.length() <= term.text.length()) continue;
                    for (Span enclosing : other.spans) {
                        if (enclosing.start <= span.start && enclosing.end >= span.end) covered = true;
                    }
                }
                if (!covered) onlyNested = false;
            }
            if (!onlyNested) retained.add(term);
            if (retained.size() >= 5) break;
        }
        return Collections.unmodifiableList(retained);
    }

    static List<Span> occurrences(String source, String term) {
        List<Span> result = new ArrayList<>();
        if (term == null || term.isEmpty()) return result;
        Matcher matcher = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE).matcher(source);
        while (matcher.find()) {
            int start = matcher.start(), end = matcher.end();
            if (asciiToken(source.charAt(start)) && start > 0 && asciiToken(source.charAt(start - 1))) continue;
            if (asciiToken(source.charAt(end - 1)) && end < source.length() && asciiToken(source.charAt(end))) continue;
            result.add(new Span(start, end));
        }
        return result;
    }

    private static boolean asciiToken(char ch) {
        return ch < 128 && (Character.isLetterOrDigit(ch) || ch == '_');
    }
}
