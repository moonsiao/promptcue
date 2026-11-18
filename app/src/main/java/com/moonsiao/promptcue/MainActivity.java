package com.moonsiao.promptcue;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    static final String HOST = "app.promptcue.local";
    private static final int REQ_PERM = 1, REQ_SPEECH = 2, REQ_FILE = 3, REQ_NOTIF = 4;

    private WebView webView;
    private PermissionRequest pendingWebPerm;
    private ValueCallback<Uri[]> fileCallback;

    // video being written to the gallery
    private final Object videoLock = new Object();
    private OutputStream videoOut;
    private Uri videoUri;
    private String videoName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF4F3EF);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new Bridge(), "Android");

        // Serve the page from a fake https origin so camera, mic and IndexedDB behave like a normal secure site.
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".js") ? "application/javascript"
                            : path.endsWith(".png") ? "image/png" : "application/octet-stream";
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Android 15+ draws apps edge-to-edge: keep content clear of system bars and keyboard.
        if (Build.VERSION.SDK_INT >= 30) {
            webView.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        setContentView(webView);
        webView.loadUrl("https://" + HOST + "/index.html");
    }

    private void handleBack() {
        webView.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ---------- camera / mic permission for the page ----------
    private void handleWebPermission(PermissionRequest request) {
        List<String> need = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (need.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingWebPerm != null) pendingWebPerm.deny();
        pendingWebPerm = request;
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERM || pendingWebPerm == null) return;
        PermissionRequest req = pendingWebPerm;
        pendingWebPerm = null;
        List<String> ok = new ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && has(Manifest.permission.CAMERA)) ok.add(r);
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && has(Manifest.permission.RECORD_AUDIO)) ok.add(r);
        }
        if (ok.isEmpty()) req.deny();
        else req.grant(ok.toArray(new String[0]));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) {
                    webView.evaluateJavascript("window.onSpeech && window.onSpeech(" + JSONObject.quote(r.get(0)) + ")", null);
                }
            }
        } else if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        }
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    private String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    class Bridge {
        @JavascriptInterface
        public boolean isApp() {
            return true;
        }

        @JavascriptInterface
        public void keepOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Free speech-to-text through the phone's own recognizer (no API key). */
        @JavascriptInterface
        public void speech(String lang) {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话…");
                try {
                    startActivityForResult(i, REQ_SPEECH);
                } catch (ActivityNotFoundException e) {
                    toast("手机没有语音识别服务（请安装/更新 Google App）");
                }
            });
        }

        /** Floating teleprompter over other apps. json = {text, font, speed} */
        @JavascriptInterface
        public String startFloat(String json) {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                runOnUiThread(() -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        toast("请在系统设置里允许「显示在其他应用上层」");
                    }
                });
                return "need_permission";
            }
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF));
            }
            try {
                JSONObject o = new JSONObject(json);
                Intent i = new Intent(MainActivity.this, FloatService.class);
                i.putExtra("text", o.optString("text"));
                i.putExtra("font", o.optInt("font", 24));
                i.putExtra("speed", o.optInt("speed", 40));
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
                return "ok";
            } catch (Exception e) {
                return "error";
            }
        }

        @JavascriptInterface
        public void openCamera() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
                    } catch (Exception ignored) {
                        toast("找不到相机 App");
                    }
                }
            });
        }

        // ---- recorded video is streamed here in chunks and saved to Movies/PromptCue ----
        @JavascriptInterface
        public String videoBegin(String mime, String ext) {
            synchronized (videoLock) {
                closeVideo(false);
                try {
                    videoName = "PromptCue_" + stamp() + "." + ext;
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.DISPLAY_NAME, videoName);
                    cv.put(MediaStore.Video.Media.MIME_TYPE, mime);
                    cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PromptCue");
                    cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                    ContentResolver cr = getContentResolver();
                    videoUri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                    if (videoUri == null) return "error";
                    videoOut = cr.openOutputStream(videoUri);
                    return videoOut == null ? "error" : "ok";
                } catch (Exception e) {
                    return "error";
                }
            }
        }

        @JavascriptInterface
        public void videoChunk(String b64) {
            synchronized (videoLock) {
                if (videoOut == null) return;
                try {
                    videoOut.write(Base64.decode(b64, Base64.DEFAULT));
                } catch (Exception ignored) { }
            }
        }

        @JavascriptInterface
        public String videoEnd() {
            synchronized (videoLock) {
                String n = videoName;
                boolean ok = closeVideo(true);
                return ok ? n : "";
            }
        }

        /** Backup file to Download/ */
        @JavascriptInterface
        public boolean saveText(String name, String text) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
                if (u == null) return false;
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    if (o == null) return false;
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /** Must hold videoLock. */
    private boolean closeVideo(boolean publish) {
        if (videoUri == null) return false;
        boolean ok = true;
        try {
            if (videoOut != null) videoOut.close();
        } catch (Exception e) {
            ok = false;
        }
        try {
            if (publish && ok) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(videoUri, cv, null, null);
            } else {
                getContentResolver().delete(videoUri, null, null);
                ok = false;
            }
        } catch (Exception e) {
            ok = false;
        }
        videoOut = null;
        videoUri = null;
        return ok;
    }

    @Override
    protected void onDestroy() {
        synchronized (videoLock) {
            if (videoUri != null) closeVideo(true);
        }
        super.onDestroy();
    }
}
