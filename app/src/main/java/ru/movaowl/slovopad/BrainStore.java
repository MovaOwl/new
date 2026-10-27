package ru.movaowl.slovopad;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/** Скачивание «мозга» лиса (GGUF-модель) из релиза brain-v1 на GitHub. */
final class BrainStore {
    static final String INDEX = "https://github.com/MovaOwl/new/releases/download/brain-v2/brain.json";
    private static volatile String verifying = null;
    private static volatile String lastError = "";

    private static SharedPreferences p(Context c) { return c.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE); }

    /** Список доступных мозгов (JSON) — из сети, с кэшем. */
    static String index(Context c) {
        try {
            HttpURLConnection u = (HttpURLConnection) new URL(INDEX).openConnection();
            u.setConnectTimeout(15000); u.setReadTimeout(20000); u.setInstanceFollowRedirects(true);
            int code = u.getResponseCode();
            if (code == 200) {
                InputStream in = u.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close();
                String s = bo.toString("UTF-8");
                new JSONObject(s); // проверка
                p(c).edit().putString("brainIndex", s).apply();
                return s;
            }
            lastError = "Сервер ответил " + code;
        } catch (Exception e) { lastError = "Нет связи с GitHub: " + e.getClass().getSimpleName(); }
        return p(c).getString("brainIndex", "");
    }

    static String start(Context c, String id, boolean wifiOnly) {
        try {
            String idx = index(c);
            if (idx.isEmpty()) return "Не получил список мозгов. " + lastError;
            JSONArray ms = new JSONObject(idx).getJSONArray("models");
            JSONObject m = null;
            for (int i = 0; i < ms.length(); i++) if (id.equals(ms.getJSONObject(i).optString("id"))) m = ms.getJSONObject(i);
            if (m == null) return "Нет такого мозга";
            File dir = LocalBrain.dir(c);
            if (dir.getUsableSpace() < m.getLong("size") + 200_000_000L) return "Мало места: нужно " + (m.getLong("size") / 1_000_000) + " МБ + запас";
            String file = m.getString("file");
            File f = new File(dir, file); if (f.exists()) f.delete();
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(m.getString("url")))
                    .setTitle("Мозг лиса · " + m.optString("title"))
                    .setDescription("Словопад: своя нейросеть для лиса")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(c, "brain", file)
                    .setAllowedOverRoaming(false);
            if (wifiOnly) r.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            long dl = dm.enqueue(r);
            p(c).edit().putLong("brainDl", dl).putString("brainPending", m.toString()).apply();
            return "";
        } catch (Exception e) { return "Ошибка: " + e.getMessage(); }
    }

    /** Состояние для экрана настроек. */
    static String status(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences sp = p(c);
            File ready = LocalBrain.model(c);
            o.put("lib", LocalBrain.libPresent(c));
            o.put("ready", ready != null);
            o.put("title", sp.getString("brainTitle", ""));
            o.put("size", ready != null ? ready.length() : 0);
            o.put("error", sp.getString("brainError", ""));
            long dl = sp.getLong("brainDl", -1);
            if (verifying != null) { o.put("state", "verify"); return o.toString(); }
            if (dl < 0) { o.put("state", ready != null ? "ready" : "none"); return o.toString(); }
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(dl))) {
                if (cur == null || !cur.moveToFirst()) { sp.edit().remove("brainDl").apply(); o.put("state", ready != null ? "ready" : "none"); return o.toString(); }
                int st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                o.put("done", done); o.put("total", total);
                if (st == DownloadManager.STATUS_SUCCESSFUL) { verify(c); o.put("state", "verify"); }
                else if (st == DownloadManager.STATUS_FAILED) {
                    int reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    sp.edit().remove("brainDl").putString("brainError", "Загрузка сорвалась (код " + reason + "). Попробуй ещё раз.").apply();
                    o.put("state", "error"); o.put("error", "Загрузка сорвалась (код " + reason + ")");
                } else if (st == DownloadManager.STATUS_PAUSED) o.put("state", "paused");
                else o.put("state", "downloading");
            }
        } catch (Exception e) { try { o.put("state", "error"); o.put("error", e.getMessage()); } catch (Exception ignored) { } }
        return o.toString();
    }

    private static synchronized void verify(Context c) {
        if (verifying != null) return;
        SharedPreferences sp = p(c);
        String pend = sp.getString("brainPending", "");
        if (pend.isEmpty()) return;
        verifying = pend;
        new Thread(() -> {
            try {
                JSONObject m = new JSONObject(pend);
                File f = new File(LocalBrain.dir(c), m.getString("file"));
                String sha = sha256(f);
                if (f.length() == m.getLong("size") && sha.equalsIgnoreCase(m.getString("sha256"))) {
                    // старый мозг другого размера удаляем, чтобы не занимал место
                    String old = sp.getString("brainFile", "");
                    if (!old.isEmpty() && !old.equals(f.getName())) new File(LocalBrain.dir(c), old).delete();
                    sp.edit().putString("brainFile", f.getName()).putString("brainTitle", m.optString("title")).remove("brainDl").remove("brainPending").putString("brainError", "").apply();
                } else {
                    f.delete();
                    sp.edit().remove("brainDl").remove("brainPending").putString("brainError", "Файл повредился при загрузке — скачай ещё раз").apply();
                }
            } catch (Exception e) {
                sp.edit().remove("brainDl").putString("brainError", "Проверка не прошла: " + e.getMessage()).apply();
            }
            verifying = null;
        }).start();
    }

    static void cancel(Context c) {
        long dl = p(c).getLong("brainDl", -1);
        if (dl >= 0) ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(dl);
        p(c).edit().remove("brainDl").remove("brainPending").apply();
    }

    static void delete(Context c) {
        cancel(c);
        File[] fs = LocalBrain.dir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
        p(c).edit().remove("brainFile").remove("brainTitle").apply();
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) { byte[] b = new byte[1 << 20]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
