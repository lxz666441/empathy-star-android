package com.lxz.empathystar;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Injectable model transport. No logging, storage, retries or provider fallback. */
interface ChatModelClient {
    final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    interface Guard { void check(HttpURLConnection connection) throws Exception; }
    ChatAnalysis analyze(String endpoint, String model, String key, String target,
                         List<String> context, Guard guard) throws Exception;

    ConversationSummary summarize(String endpoint, String model, String key, ConversationSelection selection,
                                  Guard guard) throws Exception;

    final class Https implements ChatModelClient {
        @Override public ChatAnalysis analyze(String endpoint, String model, String key, String target,
                                               List<String> context, Guard guard) throws Exception {
            if (!ChatSurfacePolicy.isSecureModelEndpoint(endpoint)) throw new IllegalArgumentException("模型地址必须为HTTPS");
            if (target == null || target.isEmpty() || target.length() > 6000) throw new IllegalArgumentException("消息长度不适合分析");
            if (ChatContentPolicy.sensitive(target)) throw new IllegalArgumentException("SENSITIVE_CONTENT");
            if (context != null) for (String item : context) {
                if (ChatContentPolicy.sensitive(item)) throw new IllegalArgumentException("SENSITIVE_CONTENT");
            }
            if (context != null && context.size() > 8) throw new IllegalArgumentException("上下文数量超限");
            String user = ChatSurfacePolicy.analysisUserPrompt(target, context);
            if (user.length() > 16000) throw new IllegalArgumentException("完整上下文过长，请选较短的聊天页面");
            String content = complete(endpoint, model, key, ChatAnalysis.SYSTEM_PROMPT, user, 1800, guard);
            try { return ChatAnalysis.parse(content, target); }
            catch (org.json.JSONException invalid) { throw new Failure("RESULT_JSON"); }
            catch (Exception invalid) { throw new Failure("RESULT_SCHEMA"); }
        }

        @Override public ConversationSummary summarize(String endpoint, String model, String key,
                                                        ConversationSelection selection, Guard guard) throws Exception {
            if (selection == null || selection.entries.isEmpty()
                || selection.entries.size() > ConversationSelection.MAX_MESSAGES
                || selection.characters() > ConversationSelection.MAX_CHARACTERS) throw new Failure("RESULT_SCHEMA");
            for (ConversationSelection.Entry entry : selection.entries)
                if (ChatContentPolicy.sensitive(entry.text)) throw new Failure("RESULT_SCHEMA");
            String content = complete(endpoint, model, key, ConversationSummary.SYSTEM_PROMPT,
                ConversationSummary.userPrompt(selection), 2600, guard);
            try { return ConversationSummary.parse(content, selection); }
            catch (org.json.JSONException invalid) { throw new Failure("RESULT_JSON"); }
            catch (Exception invalid) { throw new Failure("RESULT_SCHEMA"); }
        }

        private String complete(String endpoint, String model, String key, String system, String user,
                                int tokens, Guard guard) throws Exception {
            if (!ChatSurfacePolicy.isSecureModelEndpoint(endpoint)) throw new Failure("REQUEST_FAILED");
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("temperature", 0.3);
            payload.put("max_tokens", tokens);
            payload.put("response_format", new JSONObject().put("type", "json_object"));
            if ("api.deepseek.com".equalsIgnoreCase(new URL(endpoint).getHost())) {
                payload.put("thinking", new JSONObject().put("type", "disabled"));
            }
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", user));
            payload.put("messages", messages);
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Authorization", "Bearer " + key);
                connection.setDoOutput(true);
                guard.check(connection);
                try (OutputStream output = connection.getOutputStream()) {
                    guard.check(connection);
                    output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new Failure("HTTP_" + code);
                String body;
                try (InputStream input = connection.getInputStream()) { body = readLimited(input); }
                JSONObject choice;
                try { choice = new JSONObject(body).getJSONArray("choices").getJSONObject(0); }
                catch (org.json.JSONException invalid) { throw new Failure("RESPONSE_JSON"); }
                if ("length".equals(choice.optString("finish_reason"))) throw new Failure("OUTPUT_TRUNCATED");
                String content;
                try { content = choice.getJSONObject("message").getString("content"); }
                catch (org.json.JSONException invalid) { throw new Failure("RESPONSE_JSON"); }
                return content;
            } catch (java.net.SocketTimeoutException timeout) {
                throw new Failure("NETWORK_TIMEOUT");
            } finally { connection.disconnect(); }
        }

        private String readLimited(InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 65536) throw new IllegalArgumentException("响应过大");
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }
}
