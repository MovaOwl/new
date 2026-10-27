package ru.movaowl.slovopad;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/** Невидимый экран: слушает тебя и передаёт текст лису на экране. Без голоса, без меню. */
public class ListenActivity extends Activity {
    private SpeechRecognizer sr;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish(); return;
        }
        // лис должен быть на экране, чтобы показать ответ
        try { startForegroundService(new Intent(this, FoxService.class)); } catch (Exception ignored) { }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // разрешение спрашиваем в самом приложении — из прозрачного окна Xiaomi его прячет
            send(FoxService.ACT_HEARD, "error", "mic");
            startActivity(new Intent(this, MainActivity.class).putExtra("askMic", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) start();
        else { send(FoxService.ACT_HEARD, "error", "mic"); finish(); }
    }

    private void send(String action, String kind, String text) {
        Intent i = new Intent(this, FoxService.class).setAction(action).putExtra("kind", kind).putExtra("text", text);
        try { startService(i); } catch (Exception e) { try { startForegroundService(i); } catch (Exception ignored) { } }
    }

    private void start() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { send(FoxService.ACT_HEARD, "error", "none"); finish(); return; }
        String mode = getSharedPreferences(FoxService.PREF, MODE_PRIVATE).getString("lang", "auto");
        String lang = "en".equals(mode) ? "en-US" : "ru-RU";
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { send(FoxService.ACT_HEARD, "ready", ""); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] buf) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onError(int e) { send(FoxService.ACT_HEARD, "error", String.valueOf(e)); finish(); }
            @Override public void onResults(Bundle r) { send(FoxService.ACT_HEARD, "final", best(r)); finish(); }
            @Override public void onPartialResults(Bundle r) { String t = best(r); if (!t.isEmpty()) send(FoxService.ACT_HEARD, "partial", t); }
            @Override public void onEvent(int t, Bundle p) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        if ("auto".equals(mode) && Build.VERSION.SDK_INT >= 34) {
            ArrayList<String> langs = new ArrayList<>();
            langs.add("ru-RU"); langs.add("en-US");
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true);
            i.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
        }
        try { sr.startListening(i); } catch (Exception e) { send(FoxService.ACT_HEARD, "error", "start"); finish(); }
    }

    private static String best(Bundle r) {
        ArrayList<String> a = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return a != null && !a.isEmpty() ? a.get(0) : "";
    }

    @Override
    protected void onDestroy() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } }
        super.onDestroy();
    }
}
