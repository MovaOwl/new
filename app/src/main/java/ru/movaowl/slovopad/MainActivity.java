package ru.movaowl.slovopad;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

public class MainActivity extends Activity {
    private WebView web;
    private TextToSpeech tts;
    private boolean ttsReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        styleBars();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
                ttsReady = true;
            }
        });

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc)) { openExternal(u.toString()); return true; }
                return false;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setBackgroundColor(isDark() ? Color.parseColor("#0d1322") : Color.parseColor("#e9edf4"));
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean isDark() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void styleBars() {
        Window w = getWindow();
        int bg = isDark() ? Color.parseColor("#0d1322") : Color.parseColor("#e9edf4");
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        if (!isDark()) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void openExternal(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) { }
    }

    class Bridge {
        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public void speak(String text) {
            if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w");
        }

        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Словопад", text));
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Отправить слова"));
            });
        }

        @JavascriptInterface
        public boolean foxCanOverlay() { return Settings.canDrawOverlays(MainActivity.this); }

        @JavascriptInterface
        public void foxAskOverlay() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public boolean foxRunning() { return getSharedPreferences(FoxService.PREF, MODE_PRIVATE).getBoolean("on", false); }

        @JavascriptInterface
        public void foxStart(String json) {
            if (Settings.canDrawOverlays(MainActivity.this))
                getSharedPreferences(FoxService.PREF, MODE_PRIVATE).edit().putBoolean("on", true).apply();
            runOnUiThread(() -> {
                if (!Settings.canDrawOverlays(MainActivity.this)) return;
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
                Intent i = new Intent(MainActivity.this, FoxService.class).putExtra("data", json);
                try { startForegroundService(i); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void foxFed() {
            getSharedPreferences(FoxService.PREF, MODE_PRIVATE).edit().putLong("fed", System.currentTimeMillis()).apply();
        }

        @JavascriptInterface
        public int foxTakeCatches() {
            android.content.SharedPreferences p = getSharedPreferences(FoxService.PREF, MODE_PRIVATE);
            int n = p.getInt("catches", 0);
            if (n > 0) p.edit().putInt("catches", 0).apply();
            return n;
        }

        @JavascriptInterface
        public void openAssist() {
            runOnUiThread(() -> startActivity(new Intent(MainActivity.this, ListenActivity.class)));
        }

        @JavascriptInterface
        public void openAssistSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public void foxLang(String mode) {
            getSharedPreferences(FoxService.PREF, MODE_PRIVATE).edit().putString("lang", mode).apply();
        }

        @JavascriptInterface
        public void foxStop() {
            runOnUiThread(() -> {
                getSharedPreferences(FoxService.PREF, MODE_PRIVATE).edit().putBoolean("on", false).apply();
                stopService(new Intent(MainActivity.this, FoxService.class));
            });
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.androidBack ? androidBack() : 'exit'", v -> {
            if ("\"exit\"".equals(v)) finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.androidResume && androidResume()", null);
    }

    @Override
    protected void onPause() {
        if (web != null) web.evaluateJavascript("window.androidPause && androidPause()", null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
