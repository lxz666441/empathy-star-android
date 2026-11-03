package com.lxz.empathystar;

import android.content.Context;
import java.util.function.Consumer;

/** Release builds intentionally have no USB key-import listener. */
final class UsbApiKeyImporter {
    private UsbApiKeyImporter() {}
    static void start(Context context, Consumer<String> callback) {
        callback.accept("USB 密钥导入仅存在于个人 Debug 测试版。");
    }
}
