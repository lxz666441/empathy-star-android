package com.lxz.empathystar;

import java.util.regex.Pattern;

/** Conservative credential filter, including token-bearing URLs. Never logs input. */
final class ChatContentPolicy {
    private static final Pattern PREFIXED = Pattern.compile("(?is).*\\b(?:sk-|sf-|apikey[_-])[A-Za-z0-9_-]{16,}\\b.*");
    private static final Pattern LABELLED = Pattern.compile("(?is)(?:password|api[_ -]?key|access[_ -]?token|session[_ -]?token|密码|口令|卡密)\\s*[:=：]\\s*\\S{4,}");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~-]{16,}");
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_QUERY = Pattern.compile("(?i)[?&](?:token|key|api_key|apikey|access_token|refresh_token|session|sessionid|secret|auth|password|code)=[^&#]{4,}");
    private static final Pattern OPAQUE_PATH = Pattern.compile("/[A-Za-z0-9_-]{24,}(?=[/?#]|$)");

    static boolean sensitive(String text) {
        if (text == null || text.isEmpty()) return false;
        if (PREFIXED.matcher(text).matches() || LABELLED.matcher(text).find() || BEARER.matcher(text).find()) return true;
        java.util.regex.Matcher urls = URL.matcher(text);
        while (urls.find()) {
            String url = urls.group();
            if (SECRET_QUERY.matcher(url).find() || OPAQUE_PATH.matcher(url).find()) return true;
        }
        return false;
    }
}
