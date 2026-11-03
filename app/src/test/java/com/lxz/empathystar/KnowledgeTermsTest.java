package com.lxz.empathystar;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public final class KnowledgeTermsTest {
    private KnowledgeTerms.Candidate term(String text) { return new KnowledgeTerms.Candidate(text, "测试语境释义"); }

    @Test public void keepsExactSourceConceptsAndComputesOffsetsLocally() {
        String source = "我们用 RAG 给大模型补充外部知识。";
        List<KnowledgeTerms.Term> result = KnowledgeTerms.validate(source, Arrays.asList(term("RAG"), term("大模型")));
        assertEquals(2, result.size());
        for (KnowledgeTerms.Term item : result) {
            for (KnowledgeTerms.Span span : item.spans) assertEquals(item.text, source.substring(span.start, span.end));
        }
    }

    @Test public void rejectsIdentifierFragmentsAndUnknownShortAcronyms() {
        assertTrue(KnowledgeTerms.validate("GPT GPT4 foo_RAG PT DO", Arrays.asList(
            term("PT"), term("GPT" + "4x"), term("RAG"), term("DO"))).isEmpty());
        assertTrue(KnowledgeTerms.occurrences("GPT4 GPT_test preGPT", "GPT").isEmpty());
        assertEquals(1, KnowledgeTerms.validate("使用 AI。", Collections.singletonList(term("AI"))).size());
    }

    @Test public void rejectsNonSourceWordsMundaneWordsAndMissingMeanings() {
        assertTrue(KnowledgeTerms.validate("你好，点击按钮，等10分钟。", Arrays.asList(
            term("你好"), term("按钮"), term("10分钟"), term("RAG"),
            new KnowledgeTerms.Candidate("点击按钮", " "))).isEmpty());
    }

    @Test public void removesNestedOnlyButRetainsIndependentOccurrence() {
        List<KnowledgeTerms.Candidate> candidates = Arrays.asList(term("向量"), term("向量数据库"));
        List<KnowledgeTerms.Term> nested = KnowledgeTerms.validate("使用向量数据库", candidates);
        assertEquals(1, nested.size());
        assertEquals("向量数据库", nested.get(0).text);
        assertEquals(2, KnowledgeTerms.validate("向量数据库存储向量", candidates).size());
    }

    @Test public void deduplicatesWithoutChangingSourceCase() {
        List<KnowledgeTerms.Term> result = KnowledgeTerms.validate("使用 RAG，研究 RAG。", Arrays.asList(term("rag"), term("RAG")));
        assertEquals(1, result.size());
        assertEquals("RAG", result.get(0).text);
        assertEquals(2, result.get(0).spans.size());
    }

    @Test public void limitsConceptsAfterPruningAndHandlesUnicodeOffsets() {
        String source = "🙂 Alpha Beta Gamma Delta Epsilon Zeta";
        List<KnowledgeTerms.Term> result = KnowledgeTerms.validate(source, Arrays.asList(
            term("Alpha"), term("Beta"), term("Gamma"), term("Delta"), term("Epsilon"), term("Zeta")));
        assertEquals(5, result.size());
        assertEquals(3, result.get(0).spans.get(0).start);
    }

    @Test public void emptyCandidateListIsAValidNoJargonResult() {
        assertTrue(KnowledgeTerms.validate("今天挺开心。", Collections.emptyList()).isEmpty());
        assertTrue(KnowledgeTerms.validate(null, null).isEmpty());
    }
}
