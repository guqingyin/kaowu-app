package com.workbuddy.dailytrail;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String URL = "https://daily-trail-78645.app.workbuddy.host/?sharecode=DLOMHYV66G";
    private static final String CHANNEL_ID = "kaowu_messages_v2";
    private static final int REQ_NOTIFY = 2001;
    private static final int REQ_LOCATION = 2002;
    private static final int REQ_FILE = 2003;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private int notifyId = 100;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            // 原生定时唤起网页轮询：绕过安卓对后台网页定时器的冻结
            if (web != null) {
                web.evaluateJavascript("if(window.__kaowuNativePoll){window.__kaowuNativePoll()}", null);
            }
            pollHandler.postDelayed(this, 15000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        // 保持渲染器后台不被冻结（否则网页轮询停止、通知失效）
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        }
        // 注入原生版本号到 UA：网页端靠它做「发现新版本」检测（DailyTrailApp/x.y.z;code=N）
        s.setUserAgentString(s.getUserAgentString() + " DailyTrailApp/3.3.2;code=36");

        // 分享码门禁依赖 Cookie，允许并持久化
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new NotifyBridge(), "AndroidNotify");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                geoCallback = cb;
                geoOrigin = origin;
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    cb.invoke(origin, true, false);
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择文件"), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, ua, contentDisposition, mimetype, contentLength) -> {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        URLUtil.guessFileName(url, contentDisposition, mimetype));
                if (ua != null) r.addRequestHeader("User-Agent", ua);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                dm.enqueue(r);
                Toast.makeText(this, "开始下载", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
            }
        });

        ensureChannel();
        // 关键修复：首次打开就主动请求系统通知权限（Android 13+ 必须运行时请求，仅声明无效）
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }

        // 前台服务保活进程 + 原生定时唤起网页轮询（锁屏/后台也持续检测新消息）
        // 用 startService 而非 startForegroundService：后者有「5 秒内必须 startForeground」的硬性超时，
        // 在部分国产 ROM 上会被判超时直接杀掉服务；服务自己调 startForeground 即可，无需这个约束。
        startService(new Intent(this, PollService.class));
        pollHandler.postDelayed(pollTick, 15000);

        web.loadUrl(URL);
    }

    @Override
    protected void onDestroy() {
        pollHandler.removeCallbacks(pollTick);
        // 注意：不在这里 stopService —— 让前台服务继续跑，锁屏/切后台也能收消息
        super.onDestroy();
    }

    private void showNotification(String title, String body) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // 未授权时补一次授权请求，下次即可正常弹出
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentTitle(title == null ? "惠城院考务中心" : title);
        b.setContentText(body == null ? "" : body);
        b.setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body));
        b.setAutoCancel(true);
        b.setSound(soundUri()); // API 26 以下走这里，26+ 由通知渠道决定
        b.setVibrate(new long[]{0, 120, 80, 120});
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifyId++, b.build());
    }

    private Uri soundUri() {
        return Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_sound);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "消息通知",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("新消息、任务与@提醒");
            // 自定义提示音（双音「叮-咚」）
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(soundUri(), attrs);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 120, 80, 120});
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(ch);
        }
    }

    public class NotifyBridge {
        @JavascriptInterface
        public void post(String title, String body) {
            runOnUiThread(() -> showNotification(title, body));
        }

        @JavascriptInterface
        public void requestPermission() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                }
            });
        }

        /** 网页把登录凭证交给原生层，供后台轮询使用（WebView 被冻结也能继续查消息） */
        @JavascriptInterface
        public void setSession(String accessToken, String userId) {
            if (accessToken == null || accessToken.isEmpty() || userId == null || userId.isEmpty()) return;
            getSharedPreferences("kaowu", MODE_PRIVATE)
                    .edit()
                    .putString("access_token", accessToken)
                    .putString("user_id", userId)
                    .apply();
        }

        /**
         * v3.3.1：连 refresh_token 和过期时间一起交给原生层。
         * 后台长期不打开 App 时，前台服务靠它自己续期，否则 access_token 一过期就再也收不到通知。
         */
        @JavascriptInterface
        public void setSessionEx(String accessToken, String userId, String refreshToken, String expiresAt) {
            if (accessToken == null || accessToken.isEmpty() || userId == null || userId.isEmpty()) return;
            long exp = 0;
            try {
                exp = Long.parseLong(expiresAt);
            } catch (Exception ignored) {
            }
            getSharedPreferences("kaowu", MODE_PRIVATE)
                    .edit()
                    .putString("access_token", accessToken)
                    .putString("user_id", userId)
                    .putString("refresh_token", refreshToken == null ? "" : refreshToken)
                    .putLong("expires_at", exp)
                    .apply();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_LOCATION && geoCallback != null) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{data.getData()};
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
