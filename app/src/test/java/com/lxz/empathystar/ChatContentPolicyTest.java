package com.lxz.empathystar;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatContentPolicyTest {
    @Test public void ordinaryConversationAndPublicDocumentationStayAvailable() {
        assertFalse(ChatContentPolicy.sensitive("我们用 RAG 补充外部知识。"));
        assertFalse(ChatContentPolicy.sensitive("https://example.org/docs/guide"));
        assertFalse(ChatContentPolicy.sensitive(null));
    }
    @Test public void opaqueCapabilityPathsDoNotReceiveMarkersOrEnterContext() {
        String generatedPath = "x".repeat(28);
        assertTrue(ChatContentPolicy.sensitive("https://example.invalid/api/" + generatedPath));
    }
    @Test public void authenticationParametersAreBlocked() {
        assertTrue(ChatContentPolicy.sensitive("https://example.invalid/open?access_token=" + "x".repeat(20)));
        assertTrue(ChatContentPolicy.sensitive("https://example.invalid/open?code=" + "x".repeat(20)));
    }
    @Test public void credentialPrefixesAreGeneratedNotRealSecretFixtures() {
        String synthetic = new String(new char[]{'s', 'k', '-'}) + "x".repeat(24);
        assertTrue(ChatContentPolicy.sensitive(synthetic));
    }
    @Test public void apiKeyUnderscorePrefixIsBlockedWithoutAColonLabel() {
        String synthetic = "apikey_" + "x".repeat(24) + "_" + "y".repeat(24);
        assertTrue(ChatContentPolicy.sensitive(synthetic));
        assertTrue(ChatContentPolicy.sensitive("APiKEY-" + "z".repeat(24)));
        assertFalse(ChatContentPolicy.sensitive("请先配置 API Key，再测试回复。"));
    }
    @Test public void labelledCredentialsAndBearerHeadersAreBlocked() {
        assertTrue(ChatContentPolicy.sensitive("密码：" + "x".repeat(8)));
        assertTrue(ChatContentPolicy.sensitive("Bearer " + "x".repeat(24)));
        assertFalse(ChatContentPolicy.sensitive("密码需要设得复杂一些。"));
    }
}
