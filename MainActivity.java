package com.sta.domino;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK = 41, SHOT = 42, REQ_CAM_CHOOSER = 51, REQ_WEB = 52, REQ_NOTIF = 53;
    private WebView web;
    private LanBridge bridge;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest pendingWeb;
    private boolean pendingShot, playing;

    private boolean has(String p) {
        return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return url == null || !url.startsWith("file:///android_asset/"); } // لا تنقّل خارج اللعبة
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                if (p != null && p.isCaptureEnabled()) {          // زر «التقاط صورة»
                    if (has("android.permission.CAMERA")) launchCamera();
                    else { pendingShot = true; requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAM_CHOOSER); }
                    return true;
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);   // زر «من المعرض»: منتقي النظام لا يحتاج إذنًا
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try { startActivityForResult(Intent.createChooser(i, "Image"), PICK); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest r) {   // الكاميرا/الميكروفون للصفحة (مثل ماسح QR)
                runOnUiThread(new Runnable() { @Override public void run() { handleWebPermission(r); } });
            }
        });
        bridge = new LanBridge(this, web);
        web.addJavascriptInterface(bridge, "AndroidLAN");
        web.loadUrl("file:///android_asset/index.html");
    }

    private void handleWebPermission(PermissionRequest r) {
        List<String> need = new ArrayList<String>();
        for (String res : r.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) need.add("android.permission.CAMERA");
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) need.add("android.permission.RECORD_AUDIO");
        }
        if (need.isEmpty()) { r.deny(); return; }
        boolean all = true;
        for (String p : need) if (!has(p)) all = false;
        if (all) { r.grant(r.getResources()); return; }
        if (pendingWeb != null) pendingWeb.deny();
        pendingWeb = r;
        requestPermissions(need.toArray(new String[0]), REQ_WEB);
    }

    private void launchCamera() {
        try { startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), SHOT); }
        catch (Exception e) { if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; } }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        boolean ok = res.length > 0;
        for (int x : res) if (x != PackageManager.PERMISSION_GRANTED) ok = false;
        if (code == REQ_WEB && pendingWeb != null) {
            if (ok) pendingWeb.grant(pendingWeb.getResources()); else pendingWeb.deny();
            pendingWeb = null;
        } else if (code == REQ_CAM_CHOOSER) {
            if (ok && pendingShot) launchCamera();
            else if (fileCb != null) { fileCb.onReceiveValue(null); fileCb = null; }
            pendingShot = false;
        }
    }

    /** يبدأ/يوقف الخدمة الأمامية التي تُبقي المباراة تعمل بعد الخروج. */
    void setPlaying(boolean on) {
        if (on == playing) return;
        playing = on;
        Intent i = new Intent(this, KeepAliveService.class);
        if (on) {
            if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.POST_NOTIFICATIONS"))
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            try { if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i); } catch (Exception e) { /* */ }
        } else stopService(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK || req == SHOT) {
            if (fileCb != null) {
                Uri[] r = null;
                if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
                if (req == SHOT && res == RESULT_OK && data != null && data.getExtras() != null) {
                    Object o = data.getExtras().get("data");
                    if (o instanceof Bitmap) {
                        try {
                            File f = new File(getCacheDir(), "cam_" + System.currentTimeMillis() + ".jpg");
                            FileOutputStream fo = new FileOutputStream(f);
                            ((Bitmap) o).compress(Bitmap.CompressFormat.JPEG, 88, fo);
                            fo.close();
                            r = new Uri[]{Uri.fromFile(f)};
                        } catch (Exception e) { r = null; }
                    }
                }
                fileCb.onReceiveValue(r);
                fileCb = null;
            }
        } else super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){try{if(Modals.length||Cur.screen!=='home'){Nav.back();return 1;}}catch(e){}return 0;})()",
            new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) { if (!"1".equals(v)) finish(); }
            });
    }

    @Override
    protected void onDestroy() {
        if (playing) { stopService(new Intent(this, KeepAliveService.class)); playing = false; }
        if (bridge != null) bridge.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
