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
    private static native byte[] nChat(long h, String[] roles, String[] contents, int maxTokens, float temp);
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

    static boolean ready(Context c) { return lib() && model(c) != null; }

    /** Синхронно (вызывать из фонового потока). null — если не вышло. */
    static String chat(Context c, List<String[]> msgs, int maxTokens) {
        File f = model(c);
        if (f == null || !lib()) return null;
        synchronized (LOCK) {
            H.removeCallbacks(UNLOAD);
            if (handle == 0 || !f.getAbsolutePath().equals(loadedPath)) {
                if (handle != 0) { nFree(handle); handle = 0; }
                int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
                handle = nLoad(f.getAbsolutePath(), 2048, threads);
                loadedPath = handle != 0 ? f.getAbsolutePath() : null;
                if (handle == 0) return null;
            }
            String[] roles = new String[msgs.size()], cont = new String[msgs.size()];
            for (int i = 0; i < msgs.size(); i++) { roles[i] = msgs.get(i)[0]; cont[i] = msgs.get(i)[1]; }
            byte[] out = nChat(handle, roles, cont, maxTokens, 0.7f);
            H.postDelayed(UNLOAD, 3 * 60_000L); // через 3 минуты тишины освобождаем память
            return out == null ? null : new String(out, StandardCharsets.UTF_8).trim();
        }
    }

    static void stop() { if (libOk) try { nStop(); } catch (Throwable ignored) { } }

    static void unload() {
        new Thread(() -> { synchronized (LOCK) { if (handle != 0) { nFree(handle); handle = 0; loadedPath = null; } } }).start();
    }
}
