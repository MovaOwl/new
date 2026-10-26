package ru.movaowl.slovopad;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Лис-ассистент: вызывается вместо Gemini (жест ассистента) или долгим нажатием на лиса. */
public class AssistActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer sr;
    private final Handler h = new Handler(Looper.getMainLooper());
    private static final Locale RU = new Locale("ru", "RU");

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) { tts.setSpeechRate(0.95f); ttsReady = true; }
        });
        tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
            @Override public void onStart(String id) { js("window.onSpeak && onSpeak('start')"); }
            @Override public void onDone(String id) { if (id.startsWith("last")) js("window.onSpeak && onSpeak('done')"); }
            @Override public void onError(String id) { js("window.onSpeak && onSpeak('done')"); }
        });

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Fox");
        setContentView(web);
        web.loadUrl("file:///android_asset/assist.html");

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        boolean ok = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
        js("window.onMicPerm && onMicPerm(" + ok + ")");
    }

    private void js(String code) { h.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    class Bridge {
        @JavascriptInterface
        public boolean hasMic() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

        @JavascriptInterface
        public boolean canListen() { return SpeechRecognizer.isRecognitionAvailable(AssistActivity.this); }

        @JavascriptInterface
        public void listen(String lang) {
            h.post(() -> {
                if (tts != null) tts.stop();
                if (!hasMic()) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3); return; }
                if (sr == null) {
                    sr = SpeechRecognizer.createSpeechRecognizer(AssistActivity.this);
                    sr.setRecognitionListener(new RecognitionListener() {
                        @Override public void onReadyForSpeech(Bundle p) { js("onSpeech('ready','')"); }
                        @Override public void onBeginningOfSpeech() { }
                        @Override public void onRmsChanged(float v) { js("window.onLevel && onLevel(" + v + ")"); }
                        @Override public void onBufferReceived(byte[] b) { }
                        @Override public void onEndOfSpeech() { js("onSpeech('end','')"); }
                        @Override public void onError(int e) { js("onSpeech('error','" + e + "')"); }
                        @Override public void onResults(Bundle r) { js("onSpeech('final'," + q(best(r)) + ")"); }
                        @Override public void onPartialResults(Bundle r) { js("onSpeech('partial'," + q(best(r)) + ")"); }
                        @Override public void onEvent(int t, Bundle p) { }
                    });
                }
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                try { sr.startListening(i); } catch (Exception e) { js("onSpeech('error','start')"); }
            });
        }

        @JavascriptInterface
        public void stopListen() { h.post(() -> { if (sr != null) sr.stopListening(); }); }

        /** Озвучка: русские куски — русским голосом, английские — американским. */
        @JavascriptInterface
        public void speak(String text) {
            h.post(() -> {
                if (!ttsReady || text == null) return;
                tts.stop();
                Matcher m = Pattern.compile("[А-Яа-яЁё][^A-Za-z]*|[^А-Яа-яЁё]+").matcher(text);
                ArrayList<String> parts = new ArrayList<>();
                while (m.find()) { String p = m.group().trim(); if (p.replaceAll("[\\p{Punct}\\s«»—–]", "").length() > 0) parts.add(p); }
                for (int i = 0; i < parts.size(); i++) {
                    String p = parts.get(i);
                    boolean ru = p.matches("(?s).*[А-Яа-яЁё].*");
                    tts.setLanguage(ru ? RU : Locale.US);
                    tts.speak(p, i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, (i == parts.size() - 1 ? "last" : "p") + i);
                }
            });
        }

        @JavascriptInterface
        public void hush() { h.post(() -> { if (tts != null) tts.stop(); }); }

        /** Запрос к Gemini в фоне; ответ приходит в onGem(id, status, text). */
        @JavascriptInterface
        public void gemini(String key, String model, String body, int id) {
            new Thread(() -> {
                int code = -1; String out;
                try {
                    URL u = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(12000); c.setReadTimeout(30000);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    c.setRequestProperty("x-goog-api-key", key);
                    c.setDoOutput(true);
                    try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close(); }
                    out = bo.toString("UTF-8");
                } catch (Exception e) { out = String.valueOf(e.getMessage()); }
                js("onGem(" + id + "," + code + "," + q(out) + ")");
            }).start();
        }

        @JavascriptInterface
        public void setTimer(int seconds, String label) {
            h.post(() -> {
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                try { startActivity(i); } catch (Exception e) { js("onToolFail('timer')"); }
            });
        }

        @JavascriptInterface
        public void setAlarm(int hour, int minute, String label) {
            h.post(() -> {
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                try { startActivity(i); } catch (Exception e) { js("onToolFail('alarm')"); }
            });
        }

        @JavascriptInterface
        public void openApp() {
            h.post(() -> {
                startActivity(new Intent(AssistActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                finish();
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            h.post(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public void close() { h.post(AssistActivity.this::finish); }
    }

    private static String best(Bundle r) {
        ArrayList<String> a = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return a != null && !a.isEmpty() ? a.get(0) : "";
    }

    @Override
    public void onBackPressed() { finish(); }

    @Override
    protected void onDestroy() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } }
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Открыть экран выбора цифрового помощника. */
    static Intent assistSettings() { return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS); }
}
