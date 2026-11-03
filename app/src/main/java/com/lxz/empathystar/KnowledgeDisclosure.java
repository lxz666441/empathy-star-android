package com.lxz.empathystar;

/** In-memory disclosure state shared by inline links and term entries. */
final class KnowledgeDisclosure {
    private String active = "";

    boolean toggle(String term) {
        if (term == null || term.isEmpty() || term.equalsIgnoreCase(active)) {
            close();
            return false;
        }
        active = term;
        return true;
    }

    void close() { active = ""; }
}
