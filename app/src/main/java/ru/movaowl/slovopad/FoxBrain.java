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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private volatile String lastLocalErr = "";

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
        String key = ai.optString("key", "").trim().replaceAll("\\s+", "");
        String mode = ai.optString("mode", "auto"); // auto: свой мозг, если скачан; gemini: сначала Gemini
        boolean local = LocalBrain.ready(ctx);
        String off = offline(l);
        hist.add(new String[]{"user", text});
        while (hist.size() > 10) hist.remove(0);
        String err = null;
        // 1) свой мозг (без интернета)
        if (local && !"gemini".equals(mode)) {
            String r = localChat(ai, mood, stage);
            if (r != null && !r.isEmpty()) { hist.add(new String[]{"assistant", r}); return toolsFromTags(r); }
            err = "Свой мозг не ответил: " + lastLocalErr + ".";
        }
        // 2) Gemini (нужен ключ и VPN)
        if (!key.isEmpty()) {
            try {
                String r = gemini(key, prompt(ai, mood, stage));
                hist.add(new String[]{"assistant", r});
                return toolsFromTags(r);
            } catch (Exception e) {
                String why = e.getMessage() == null ? "" : e.getMessage();
                if (why.startsWith("key")) err = "Ключ Gemini не подходит: " + why.substring(3).trim();
                else if (why.startsWith("limit")) err = "Лимит Gemini на сейчас кончился.";
                else if (why.startsWith("region")) err = "Gemini видит Россию — нужен VPN.";
                else if (why.startsWith("io")) err = "Нет связи с Gemini (" + why.substring(2).trim() + ").";
                else err = "Gemini: " + why;
            }
        }
        // 3) свой мозг как запасной, если Gemini подвёл
        if (local && "gemini".equals(mode)) {
            String r = localChat(ai, mood, stage);
            if (r != null && !r.isEmpty()) { hist.add(new String[]{"assistant", r}); return toolsFromTags(r); }
        }
        hist.remove(hist.size() - 1);
        if (off != null) return (err != null ? err + " " : "") + off;
        if (err != null) return err;
        return "Без мозга я умею немного: «что значит window», «как сказать окно», таймер, будильник, «открой камеру». Скачай мой мозг: Словопад → Опции → Мозги лиса.";
    }

    /* свой мозг: короткий промт для маленькой модели */
    private String localChat(JSONObject ai, int mood, int stage) {
        String[] moods = {"happy", "hungry", "sad", "offended", "sad"};
        String sys = "You are " + ai.optString("fox", "Fox") + ", a cute white pixel arctic fox living on the phone screen of "
                + ai.optString("owner", "Igor") + ". He is a beginner learning English (knows about " + ai.optInt("known", 0) + " words, for example: "
                + cut(ai.optString("sample", ""), 300) + "). You are " + (stage <= 0 ? "sleepy" : moods[Math.max(0, Math.min(4, mood))]) + ".\n"
                + "Rules: answer VERY short, 1-2 sentences. If he writes in Russian, answer in Russian and add one simple English phrase with translation in brackets. "
                + "If he writes in English, answer in simple English; if he made a mistake, first write: Better: <correct sentence>. No emoji, no lists.";
        List<String[]> m = new ArrayList<>();
        m.add(new String[]{"system", sys});
        // примеры для маленькой модели — как отвечать
        m.add(new String[]{"user", "I likes cats"});
        m.add(new String[]{"assistant", "Better: I like cats. Cats are cute! Do you have a cat?"});
        m.add(new String[]{"user", "Как будет дом?"});
        m.add(new String[]{"assistant", "Дом — house (хаус). Например: My house is warm. (Мой дом тёплый.)"});
        for (String[] x : hist) m.add(new String[]{x[0], x[1]});
        String r = BrainClient.chat(ctx, m, 120);
        if (r == null) { lastLocalErr = BrainClient.lastError(); return null; }
        return r.replaceAll("[*_#`]", "").trim();
    }

    private static String cut(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

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

    /* ---------- Gemini (из России — через VPN) ---------- */
    private static String errMsg(String raw) {
        try { return new JSONObject(raw).getJSONObject("error").optString("message", raw); } catch (Exception e) { return raw.length() > 120 ? raw.substring(0, 120) : raw; }
    }

    /** Спросить у Google список моделей и выбрать свежую Flash. */
    private List<String> discover(String key) {
        List<String> out = new ArrayList<>();
        try {
            Map<String, String> hd = new HashMap<>();
            hd.put("x-goog-api-key", key);
            Object[] r = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", hd);
            if ((Integer) r[0] != 200) return out;
            JSONArray ms = new JSONObject((String) r[1]).optJSONArray("models");
            List<String[]> c = new ArrayList<>();
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                String name = m.optString("name").replace("models/", "");
                String meth = String.valueOf(m.optJSONArray("supportedGenerationMethods"));
                if (!meth.contains("generateContent") || !name.contains("flash")) continue;
                if (name.matches(".*(image|tts|audio|live|embed|vision|thinking-exp|exp-).*")) continue;
                Matcher v = Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)").matcher(name);
                double ver = v.find() ? Double.parseDouble(v.group(1)) : 0;
                int pen = (name.contains("lite") ? 1 : 0) + (name.contains("preview") ? 2 : 0) + (name.contains("latest") ? 1 : 0);
                c.add(new String[]{name, String.valueOf(ver * 10 - pen)});
            }
            c.sort((x, y) -> Double.compare(Double.parseDouble(y[1]), Double.parseDouble(x[1])));
            for (String[] x : c) out.add(x[0]);
        } catch (Exception ignored) { }
        return out;
    }

    private String gemini(String key, String sys) throws Exception {
        SharedPreferences p = ctx.getSharedPreferences(FoxService.PREF, Context.MODE_PRIVATE);
        List<String> order = new ArrayList<>();
        String cached = p.getString("gemModel", "");
        if (!cached.isEmpty()) order.add(cached);
        for (String m : new String[]{"gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-latest", "gemini-3.5-flash-lite", "gemini-2.5-flash"}) if (!order.contains(m)) order.add(m);
        JSONObject body = new JSONObject();
        body.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", sys))));
        JSONArray contents = new JSONArray();
        for (String[] x : hist) contents.put(new JSONObject().put("role", "user".equals(x[0]) ? "user" : "model").put("parts", new JSONArray().put(new JSONObject().put("text", x[1]))));
        body.put("contents", contents);
        body.put("generationConfig", new JSONObject().put("temperature", 0.8).put("maxOutputTokens", 400));
        Map<String, String> hd = new HashMap<>();
        hd.put("Content-Type", "application/json");
        hd.put("x-goog-api-key", key);
        boolean discovered = false;
        String lastErr = "";
        for (int i = 0; i < order.size() && i < 12; i++) {
            String m = order.get(i);
            Object[] r;
            try { r = http("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent", hd, body.toString()); }
            catch (Exception e) { throw new Exception("io " + e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")); }
            int code = (Integer) r[0]; String raw = (String) r[1];
            if (code == 200) {
                JSONObject j = new JSONObject(raw);
                JSONArray cands = j.optJSONArray("candidates");
                if (cands == null || cands.length() == 0) return "Gemini промолчал (фильтр). Спроси по-другому.";
                JSONArray parts = cands.getJSONObject(0).optJSONObject("content") != null ? cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts") : null;
                StringBuilder sb = new StringBuilder();
                for (int k = 0; parts != null && k < parts.length(); k++) if (!parts.getJSONObject(k).optBoolean("thought")) sb.append(parts.getJSONObject(k).optString("text", ""));
                if (!m.equals(cached)) p.edit().putString("gemModel", m).apply();
                String out = sb.toString().trim();
                return out.isEmpty() ? "…" : out;
            }
            String msg = errMsg(raw);
            if (msg.toLowerCase(Locale.ROOT).contains("location is not supported")) throw new Exception("region");
            if (code == 400 && msg.toLowerCase(Locale.ROOT).contains("api key") || code == 401 || code == 403 && msg.toLowerCase(Locale.ROOT).contains("key")) throw new Exception("key " + msg);
            if (code == 429) throw new Exception("limit");
            lastErr = code + " " + msg;
            if (code == 404 || code == 400 || code == 403) {
                if (m.equals(cached)) p.edit().remove("gemModel").apply();
                if (!discovered) { discovered = true; int at = i + 1; for (String d : discover(key)) if (!order.contains(d)) order.add(Math.min(at++, order.size()), d); }
                continue;
            }
            throw new Exception(lastErr);
        }
        throw new Exception(lastErr.isEmpty() ? "нет доступных моделей" : lastErr);
    }

    private static Object[] get(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n); in.close(); }
        return new Object[]{code, bo.toString("UTF-8")};
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

    /** Перевод английского слова на русский из встроенного словаря (для поиска приложений). */
    synchronized String enRu(String en) {
        loadDict();
        return enToRu.get(en.toLowerCase(Locale.ROOT).trim());
    }

    /* ---------- офлайн-словарь ---------- */
    private synchronized void loadDict() {
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
