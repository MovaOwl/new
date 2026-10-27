package ru.movaowl.slovopad;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Свой мозг лиса: маленькая нейросеть прямо в телефоне (llama.cpp + GGUF-модель). */
final class LocalBrain {
    private static boolean libOk = false, libTried = false;
    private static long handle = 0;
    private static String loadedPath = null;
    private static final Object LOCK = new Object();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable UNLOAD = LocalBrain::unload;

    private static native long nLoad(String path, int nCtx, int threads);
    private static native byte[] nChat(long h, String[] roles, String[] contents, int nPrefix, int maxTokens, float temp, Object cb);

    /** Куски ответа по мере печати (JNI зовёт piece(byte[])). */
    interface Piece { void piece(byte[] utf8); }
    private static native void nStop();
    private static native void nFree(long h);

    static boolean lib() {
        if (!libTried) {
            libTried = true;
            try { System.loadLibrary("foxbrain"); libOk = true; } catch (Throwable t) { libOk = false; }
        }
        return libOk;
    }

    static File dir(Context c) {
        File d = c.getExternalFilesDir("brain");
        if (d == null) d = new File(c.getFilesDir(), "brain");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** Установленная модель, если есть и прошла проверку. */
    static File model(Context c) {
        String name = c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE).getString("brainFile", "");
        if (name.isEmpty()) return null;
        File f = new File(dir(c), name);
        return f.exists() && f.length() > 50_000_000L ? f : null;
    }

    /** Есть ли движок в APK (без загрузки — в основном процессе .so не трогаем). */
    static boolean libPresent(Context c) {
        try {
            if (new File(c.getApplicationInfo().nativeLibraryDir, "libfoxbrain.so").exists()) return true;
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(c.getApplicationInfo().sourceDir)) {
                return z.getEntry("lib/arm64-v8a/libfoxbrain.so") != null;
            }
        } catch (Exception e) { return false; }
    }

    static boolean ready(Context c) { return libPresent(c) && model(c) != null; }

    /** Только в процессе :brain (BrainService). */
    static String chatFile(String path, List<String[]> msgs, int nPrefix, int maxTokens, Piece cb) {
        File f = new File(path);
        if (!f.exists() || !lib()) return null;
        synchronized (LOCK) {
            H.removeCallbacks(UNLOAD);
            if (handle == 0 || !f.getAbsolutePath().equals(loadedPath)) {
                if (handle != 0) { nFree(handle); handle = 0; }
                // только большие ядра: медленные «малыши» тормозят всех (у Dimensity 8300 больших 4)
                int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
                handle = nLoad(f.getAbsolutePath(), 2048, threads);
                loadedPath = handle != 0 ? f.getAbsolutePath() : null;
                if (handle == 0) return null;
            }
            String[] roles = new String[msgs.size()], cont = new String[msgs.size()];
            for (int i = 0; i < msgs.size(); i++) { roles[i] = msgs.get(i)[0]; cont[i] = msgs.get(i)[1]; }
            byte[] out = nChat(handle, roles, cont, nPrefix, maxTokens, 0.7f, cb);
            H.postDelayed(UNLOAD, 10 * 60_000L); // через 10 минут тишины освобождаем память
            return out == null ? null : new String(out, StandardCharsets.UTF_8).trim();
        }
    }

    static void stop() { if (libOk) try { nStop(); } catch (Throwable ignored) { } }

    static void unload() {
        new Thread(() -> { synchronized (LOCK) { if (handle != 0) { nFree(handle); handle = 0; loadedPath = null; } } }).start();
    }
}
