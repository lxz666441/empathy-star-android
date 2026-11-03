package com.lxz.empathystar;

import org.junit.Test;
import static org.junit.Assert.*;

public class KnowledgeDisclosureTest {
    @Test public void sameTermTogglesClosed() {
        KnowledgeDisclosure state = new KnowledgeDisclosure();
        assertTrue(state.toggle("RAG"));
        assertFalse(state.toggle("rag"));
        assertTrue(state.toggle("RAG"));
    }

    @Test public void differentTermReplacesActiveTerm() {
        KnowledgeDisclosure state = new KnowledgeDisclosure();
        assertTrue(state.toggle("RAG"));
        assertTrue(state.toggle("大模型"));
        assertFalse(state.toggle("大模型"));
    }

    @Test public void explicitCloseResetsState() {
        KnowledgeDisclosure state = new KnowledgeDisclosure();
        assertTrue(state.toggle("RAG"));
        state.close();
        assertTrue(state.toggle("RAG"));
        assertFalse(state.toggle(null));
        assertFalse(state.toggle(""));
    }
}
