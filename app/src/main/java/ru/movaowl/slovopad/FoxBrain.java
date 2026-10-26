package ru.movaowl.slovopad;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Мозги лиса: GigaChat (по умолчанию), Gemini или офлайн-навыки. Ответ — только текстом. */
class FoxBrain {
    interface Reply { void done(String text); }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String[]> hist = new ArrayList<>(); // {role, text}
    private JSONObject dict;
    private Map<String, String> ruToEn;
    private Map<String, String> enToRu;

    FoxBrain(Context c) { ctx = c.getApplicationContext(); }

    void ask(String text, JSONObject ai, int mood, int stage, Reply cb) {
        new Thread(() -> {
            String out;
            try { out = think(text, ai == null ? new JSONObject() : ai, mood, stage); }
            catch (Exception e) { out = "Что-то у меня в голове заклинило… Попробуй ещё раз."; }
            final String r = out;
            main.post(() -> cb.done(r));
        }).start();
    }

    private String think(String text, JSONObject ai, int mood, int stage) {
        String l = text.toLowerCase(Locale.ROOT);
        String tool = toolsOffline(l);
        if (tool != null) return tool;
        String key = ai.optString("key", "").trim();
        String provider = ai.optString("provider", "giga");
        String off = offline(l);
        if (key.isEmpty()) {
            return off != null ? off : "Без мозгов-нейросети я умею немного: «что значит window», «как сказать окно», таймер, будильник. Добавь ключ GigaChat в Словопаде → Опции → Мозги лиса.";
        }
        hist.add(new String[]{"user", text});
        while (hist.size() > 12) hist.remove(0);
        String sys = prompt(ai, mood, stage);
        String res;
        try {
            res = "gemini".equals(provider) ? gemini(key, sys) : giga(key, sys);
        } catch (Exception e) {
            hist.remove(hist.size() - 1);
            String why = e.getMessage() == null ? "" : e.getMessage();
            if (why.startsWith("key")) return "Ключ не подходит. Проверь его в Словопаде → Опции → Мозги лиса.";
            if (why.startsWith("limit")) return "Я на сегодня наболтался — лимит нейросети кончился." + (off != null ? " " + off : "");
            if (why.startsWith("region")) return "Gemini не работает из России без VPN. Выбери GigaChat в Опциях.";
            return "Нет связи с моими мозгами." + (off != null ? " " + off : " Без интернета умею переводить слова и ставить таймеры.");
        }
        hist.add(new String[]{"assistant", res});
        return toolsFromTags(res);
    }

    /* ---------- промт ---------- */
    private String prompt(JSONObject ai, int mood, int stage) {
        String[] moods = {"сытый и весёлый", "голодный (кормят тебя тем, что хозяин играет в Словопад)", "грустишь, давно не играли", "обиделся, хозяин долго не заходил", "ушёл, но вернулся на зов"};
        String now = new SimpleDateFormat("EEEE, HH:mm", new Locale("ru")).format(new Date());
        return "Ты — " + ai.optString("fox", "Лис") + ", белый пиксельный песец-тамагочи из приложения «Словопад». Ты живёшь на экране телефона хозяина и помогаешь ему учить английский.\n"
                + "Хозяин: " + ai.optString("owner", "Игорь") + ", риелтор из Абакана, учит английский почти с нуля, цель — читать книги. Общайтесь на «ты», можно с юмором.\n"
                + "Он знает " + ai.optInt("known", 0) + " английских слов, например: " + ai.optString("sample", "") + ".\n"
                + "Твоё состояние: " + (stage <= 0 ? "сонный, только проснулся" : moods[Math.max(0, Math.min(4, mood))]) + ". Сейчас " + now + ".\n"
                + "ПРАВИЛА: ответ показывается маленьким облачком над твоей головой, поэтому ОЧЕНЬ коротко — 1–2 предложения, до 220 символов. Без markdown, списков и эмодзи.\n"
                + "Если хозяин говорит по-русски — отвечай по-русски и добавь короткую английскую фразу по теме с переводом в скобках.\n"
                + "Если по-английски — отвечай простым английским (A1–A2) из слов, которые он знает. Ошибку сначала мягко поправь: «Лучше: …».\n"
                + "Просит перевести или объяснить слово — перевод и один короткий пример.\n"
                + "Таймер или будильник ставь сам: в КОНЦЕ ответа тег [[TIMER:секунды]] или [[ALARM:ЧЧ:ММ]].";
    }

    /* ---------- GigaChat ---------- */
    private String giga(String authKey, String sys) throws Exception {
        String token = gigaToken(authKey, false);
        String[] models = {"GigaChat-2-Pro", "GigaChat-2", "GigaChat"};
        SharedPreferences p = ctx.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE);
        String pref = p.getString("gigaModel", "");
        List<String> order = new ArrayList<>();
        if (!pref.isEmpty()) order.add(pref);
        for (String m : models) if (!order.contains(m)) order.add(m);
        Exception last = null;
        for (String m : order) {
            JSONObject body = new JSONObject();
            body.put("model", m);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            for (String[] x : hist) msgs.put(new JSONObject().put("role", x[0]).put("content", x[1]));
            body.put("messages", msgs);
            body.put("temperature", 0.8);
            body.put("max_tokens", 220);
            Map<String, String> hd = new HashMap<>();
            hd.put("Authorization", "Bearer " + token);
            hd.put("Content-Type", "application/json");
            hd.put("Accept", "application/json");
            Object[] r = http("https://gigachat.devices.sberbank.ru/api/v1/chat/completions", hd, body.toString());
            int code = (Integer) r[0];
            if (code == 401) { token = gigaToken(authKey, true); hd.put("Authorization", "Bearer " + token); r = http("https://gigachat.devices.sberbank.ru/api/v1/chat/completions", hd, body.toString()); code = (Integer) r[0]; }
            if (code == 200) {
                JSONObject j = new JSONObject((String) r[1]);
                String out = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
                if (!pref.equals(m)) p.edit().putString("gigaModel", m).apply();
                return out;
            }
            if (code == 402 || code == 403 || code == 404 || code == 400) { last = new Exception("model " + code); continue; } // кончились токены этой модели или нет доступа — пробуем следующую
            if (code == 429) throw new Exception("limit");
            throw new Exception("net " + code);
        }
        throw last != null ? new Exception("limit") : new Exception("net");
    }

    private String gigaToken(String authKey, boolean force) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE);
        String t = p.getString("gigaTok", "");
        long exp = p.getLong("gigaExp", 0);
        if (!force && !t.isEmpty() && System.currentTimeMillis() < exp - 60000) return t;
        Map<String, String> hd = new HashMap<>();
        hd.put("Authorization", "Basic " + authKey.replaceFirst("(?i)^basic\\s+", ""));
        hd.put("RqUID", UUID.randomUUID().toString());
        hd.put("Content-Type", "application/x-www-form-urlencoded");
        hd.put("Accept", "application/json");
        Object[] r = http("https://ngw.devices.sberbank.ru:9443/api/v2/oauth", hd, "scope=" + URLEncoder.encode("GIGACHAT_API_PERS", "UTF-8"));
        int code = (Integer) r[0];
        if (code == 401 || code == 400 || code == 403) throw new Exception("key");
        if (code != 200) throw new Exception("net " + code);
        JSONObject j = new JSONObject((String) r[1]);
        t = j.getString("access_token");
        exp = j.optLong("expires_at", System.currentTimeMillis() + 25 * 60000L);
        if (exp < 1e12) exp *= 1000; // на случай секунд
        p.edit().putString("gigaTok", t).putLong("gigaExp", exp).apply();
        return t;
    }

    /* ---------- Gemini (нужен VPN из России) ---------- */
    private String gemini(String key, String sys) throws Exception {
        String[] models = {"gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-flash-latest"};
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))));
        JSONArray contents = new JSONArray();
        for (String[] x : hist) contents.put(new JSONObject().put("role", "user".equals(x[0]) ? "user" : "model").put("parts", new JSONArray().put(new JSONObject().put("text", x[1]))));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject().put("temperature", 0.8).put("maxOutputTokens", 300));
        Map<String, String> hd = new HashMap<>();
        hd.put("Content-Type", "application/json");
        hd.put("x-goog-api-key", key);
        for (String m : models) {
            Object[] r = http("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent", hd, body.toString());
            int code = (Integer) r[0]; String raw = (String) r[1];
            if (code == 200) {
                JSONArray parts = new JSONObject(raw).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text", ""));
                return sb.toString().trim();
            }
            if (raw.contains("location is not supported")) throw new Exception("region");
            if (code == 404) continue;
            if (code == 401 || code == 403 || raw.contains("API key")) throw new Exception("key");
            if (code == 429) throw new Exception("limit");
            throw new Exception("net " + code);
        }
        throw new Exception("net");
    }

    private static Object[] http(String url, Map<String, String> headers, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(40000);
        c.setRequestMethod("POST");
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); }
        return new Object[]{code, bo.toString("UTF-8")};
    }

    /* ---------- таймеры и будильники ---------- */
    private String toolsFromTags(String text) {
        Matcher t = Pattern.compile("\\[\\[TIMER:(\\d+)\\]\\]").matcher(text);
        while (t.find()) timer(Integer.parseInt(t.group(1)));
        Matcher a = Pattern.compile("\\[\\[ALARM:(\\d{1,2}):(\\d{2})\\]\\]").matcher(text);
        while (a.find()) alarm(Integer.parseInt(a.group(1)), Integer.parseInt(a.group(2)));
        return text.replaceAll("\\[\\[[A-Z]+:[^\\]]*\\]\\]", "").replaceAll("[*_#`]", "").trim();
    }

    private void timer(int sec) {
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Лис: таймер").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(() -> { try { ctx.startActivity(i); } catch (Exception ignored) { } });
    }

    private void alarm(int h, int m) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Лис: подъём!").putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(() -> { try { ctx.startActivity(i); } catch (Exception ignored) { } });
    }

    private static final String[][] NUMS = {{"полчаса", "30"}, {"пятнадцать", "15"}, {"двадцать", "20"}, {"тридцать", "30"}, {"сорок", "40"}, {"десять", "10"}, {"пять", "5"}, {"три", "3"}, {"две", "2"}, {"два", "2"}, {"одну", "1"}, {"минуту", "1"}};

    private String toolsOffline(String l) {
        if (l.contains("таймер") || l.contains("timer")) {
            int n = 0;
            Matcher m = Pattern.compile("(\\d+)").matcher(l);
            if (m.find()) n = Integer.parseInt(m.group(1));
            else for (String[] x : NUMS) if (l.contains(x[0])) { n = Integer.parseInt(x[1]); break; }
            if (n == 0) n = 5;
            int sec = l.contains("сек") ? n : (l.contains("час") && !l.contains("полчас")) ? n * 3600 : n * 60;
            timer(sec);
            return "Таймер на " + (sec >= 60 ? (sec / 60) + " мин" : sec + " сек") + " поставил. Timer is set!";
        }
        Matcher a = Pattern.compile("(?:разбуди|будильник|alarm)[^\\d]*(\\d{1,2})(?:[:. ](\\d{2}))?").matcher(l);
        if (a.find()) {
            int h = Integer.parseInt(a.group(1)), m = a.group(2) != null ? Integer.parseInt(a.group(2)) : 0;
            if (h < 24) { alarm(h, m); return String.format(Locale.ROOT, "Будильник на %d:%02d. Wake up, sleepyhead!", h, m); }
        }
        return null;
    }

    /* ---------- офлайн-словарь ---------- */
    private void loadDict() {
        if (dict != null) return;
        try {
            String d = asset("dict.js");
            dict = new JSONObject(d.substring(d.indexOf('{'), d.lastIndexOf('}') + 1));
            String w = asset("words.js");
            String raw = new JSONArray("[" + w.substring(w.indexOf('"'), w.lastIndexOf('"') + 1) + "]").getString(0);
            ruToEn = new HashMap<>(); enToRu = new HashMap<>();
            for (String line : raw.split("\n")) {
                int k = line.indexOf('|'); if (k < 0) continue;
                String en = line.substring(0, k).trim(), ru = line.substring(k + 1).trim();
                enToRu.put(en.toLowerCase(Locale.ROOT), ru);
                for (String x : ru.toLowerCase(Locale.ROOT).split("[,;]")) { x = x.trim(); if (!x.isEmpty() && !ruToEn.containsKey(x)) ruToEn.put(x, en); }
            }
        } catch (Exception e) { dict = new JSONObject(); ruToEn = new HashMap<>(); enToRu = new HashMap<>(); }
    }

    private String asset(String name) throws Exception {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private String explain(String en) {
        String ru = enToRu.get(en.toLowerCase(Locale.ROOT));
        JSONArray d = dict.optJSONArray(en);
        if (d == null) {
            Iterator<String> it = dict.keys();
            while (it.hasNext()) { String k = it.next(); if (k.equalsIgnoreCase(en)) { d = dict.optJSONArray(k); en = k; break; } }
        }
        StringBuilder sb = new StringBuilder(en).append(" — ").append(ru != null ? ru : "?");
        if (d != null) {
            JSONArray ex = d.optJSONArray(1);
            if (ex != null) sb.append(". ").append(ex.optString(0)).append(" — ").append(ex.optString(1));
        }
        return sb.toString();
    }

    private String offline(String l) {
        loadDict();
        String q = l.replaceAll("[«»\"'?!.,]", "").trim();
        Matcher m = Pattern.compile("(?:что значит|что означает|переведи|перевод|what does|what is|meaning of)\\s+(?:слово\\s+)?([a-z][a-z' -]*?)(?:\\s+mean)?$").matcher(q);
        if (m.find()) { String w = m.group(1).trim(); return enToRu.containsKey(w) ? explain(w) : "Слова «" + w + "» нет в моём словаре."; }
        m = Pattern.compile("(?:как (?:будет|сказать|по-английски)|переведи на английский)\\s+(?:слово\\s+)?(.+)$").matcher(q);
        if (m.find()) { String w = m.group(1).trim(); String en = ruToEn.get(w); return en != null ? "«" + w + "» по-английски — " + explain(en) : "Не нашёл «" + w + "» в словаре."; }
        if (enToRu.containsKey(q)) return explain(q);
        if (ruToEn.containsKey(q)) return "«" + q + "» — " + explain(ruToEn.get(q));
        return null;
    }
}
