package ru.movaowl.slovopad;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Журнал сбоев: чтобы причину падения можно было увидеть в Опциях и скопировать. */
final class CrashLog {
    private static boolean installed = false;

    static synchronized void install(Context c, String where) {
        if (installed) return;
        installed = true;
        final Context app = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                String s = "[" + now() + "] " + where + " / поток " + t.getName() + "\n" + sw;
                if (s.length() > 6000) s = s.substring(0, 6000);
                try (FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), "crash.txt"), true)) { o.write((s + "\n").getBytes(StandardCharsets.UTF_8)); }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Последний шаг мозга (перезаписывается) — показывается, только если мозг упал. */
    static void step(Context c, String text) {
        try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "brainstep.txt"), false)) {
            o.write(("[" + now() + "] " + text).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    static void note(Context c, String text) {
        try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "crash.txt"), true)) {
            o.write(("[" + now() + "] " + text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static String now() { return new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date()); }

    /** Текст для экрана: свои записи + что Android знает о последних падениях. */
    static String read(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            File f = new File(c.getFilesDir(), "crash.txt");
            if (f.exists()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (s.length() > 8000) s = s.substring(s.length() - 8000);
                sb.append(s);
            }
        } catch (Exception ignored) { }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 8);
                long since = c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE).getLong("crashSeen", 0);
                for (ApplicationExitInfo x : l) {
                    int r = x.getReason();
                    if (x.getTimestamp() <= since) continue;
                    if (r != ApplicationExitInfo.REASON_CRASH && r != ApplicationExitInfo.REASON_CRASH_NATIVE && r != ApplicationExitInfo.REASON_ANR
                            && r != ApplicationExitInfo.REASON_LOW_MEMORY && r != ApplicationExitInfo.REASON_INITIALIZATION_FAILURE) continue;
                    String why = r == ApplicationExitInfo.REASON_CRASH_NATIVE ? "падение нативного кода" : r == ApplicationExitInfo.REASON_CRASH ? "падение Java"
                            : r == ApplicationExitInfo.REASON_ANR ? "завис (ANR)" : r == ApplicationExitInfo.REASON_LOW_MEMORY ? "не хватило памяти" : "ошибка запуска";
                    sb.append("[").append(new SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT).format(new Date(x.getTimestamp()))).append("] ")
                            .append(x.getProcessName()).append(": ").append(why);
                    if (x.getDescription() != null) sb.append(" — ").append(x.getDescription());
                    sb.append(" (pss ").append(x.getPss() / 1024).append(" МБ)\n");
                    if (x.getProcessName() != null && x.getProcessName().endsWith(":brain")) {
                        File st = new File(c.getFilesDir(), "brainstep.txt");
                        if (st.exists()) sb.append("   последний шаг мозга: ").append(new String(Files.readAllBytes(st.toPath()), StandardCharsets.UTF_8)).append("\n");
                    }
                }
            } catch (Exception ignored) { }
        }
        return sb.toString().trim();
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), "crash.txt").delete();
        c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE).edit().putLong("crashSeen", System.currentTimeMillis()).apply();
    }
}
