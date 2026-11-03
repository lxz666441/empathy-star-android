package com.lxz.empathystar;

import android.app.Activity;
import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.function.Consumer;

/** One-shot, loopback-only key import intended only for a trusted ADB-over-USB session. */
final class UsbApiKeyImporter {
    private static final int PORT = 43971;
    private static final int MAX_BODY_BYTES = 1024;
    private static volatile boolean listening;

    private UsbApiKeyImporter() {}

    static synchronized void start(Context context, Consumer<String> callback) {
        if (listening) {
            callback.accept("USB 密钥导入已在等待；请在 2 分钟内完成传输。");
            return;
        }
        listening = true;
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String token = Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        java.util.Arrays.fill(random, (byte) 0);
        context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString("usb_key_import_token", token).putInt("usb_key_import_port", PORT).apply();
        callback.accept("USB 安全导入已启动，仅接受一次本机 ADB 转发；等待最多 2 分钟。");

        Context appContext = context.getApplicationContext();
        new Thread(() -> receiveOnce(appContext, token, callback), "usb-key-import").start();
    }

    private static void receiveOnce(Context context, String expectedToken, Consumer<String> callback) {
        try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(120_000);
            try (Socket client = server.accept()) {
                client.setSoTimeout(10_000);
                InputStream input = client.getInputStream();
                byte[] headerBytes = readHeaders(input);
                String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
                int contentLength = parseContentLength(headers);
                String suppliedToken = header(headers, "X-Import-Token");
                if (!headers.startsWith("POST /key HTTP/1.1\r\n") || contentLength < 1
                    || contentLength > MAX_BODY_BYTES || !expectedToken.equals(suppliedToken)) {
                    respond(client.getOutputStream(), 400, "rejected");
                    return;
                }
                byte[] body = readExactly(input, contentLength);
                try {
                    String key = new String(body, StandardCharsets.UTF_8).trim();
                    if (key.length() < 8 || key.length() > MAX_BODY_BYTES) {
                        respond(client.getOutputStream(), 400, "rejected");
                        return;
                    }
                    SecureApiKeyStore.save(context, key);
                    respond(client.getOutputStream(), 200, "saved");
                    callback.accept("API Key 已通过 USB 导入并加密保存在本机；未写入明文文件或日志。");
                } finally {
                    java.util.Arrays.fill(body, (byte) 0);
                }
            }
        } catch (Exception ignored) {
            callback.accept("USB 导入未完成或超时；没有显示或记录密钥内容。");
        } finally {
            context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .remove("usb_key_import_token").remove("usb_key_import_port").apply();
            listening = false;
        }
    }

    private static byte[] readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int state = 0;
        while (bytes.size() < 4096) {
            int next = input.read();
            if (next < 0) throw new IllegalArgumentException("incomplete headers");
            bytes.write(next);
            state = state == 0 && next == '\r' ? 1
                : state == 1 && next == '\n' ? 2
                : state == 2 && next == '\r' ? 3
                : state == 3 && next == '\n' ? 4 : (next == '\r' ? 1 : 0);
            if (state == 4) return bytes.toByteArray();
        }
        throw new IllegalArgumentException("headers too large");
    }

    private static int parseContentLength(String headers) {
        String value = header(headers, "Content-Length");
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return -1; }
    }

    private static String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && name.equalsIgnoreCase(line.substring(0, colon).trim()))
                return line.substring(colon + 1).trim();
        }
        return "";
    }

    private static byte[] readExactly(InputStream input, int length) throws Exception {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(body, offset, length - offset);
            if (count < 0) throw new IllegalArgumentException("incomplete body");
            offset += count;
        }
        return body;
    }

    private static void respond(OutputStream output, int code, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        output.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Bad Request")
            + "\r\nContent-Type: text/plain\r\nContent-Length: " + bytes.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }
}
