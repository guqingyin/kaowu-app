package com.workbuddy.dailytrail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * 原生轮询服务：不依赖 WebView，自己在后台查消息并直接发系统通知。
 * v3.3.1 关键修复：云端网关只认 x-wb-webapp-access-key，旧的 apikey 头会被 401 拒掉，
 * 导致后台轮询、日志、心跳全部静默失败（表现为「不打开 App 就收不到通知」）。
 */
public class PollService extends Service {
    private static final String TAG = "PollService";
    private static final String CHANNEL_ID = "kaowu_service";
    private static final String MSG_CHANNEL_ID = "kaowu_messages_v2";
    private static final String HOST = "https://daily-trail-78645.app.workbuddy.host";
    private static final String BASE = HOST + "/.cloud/database/rest";
    private static final String AUTH = HOST + "/.cloud/auth";
    private static final String API_KEY = "wbpk_XrCfFEr0yAsY946ghnW29f_jQcMF3iXdy3as1jlMBIDOtc2IQfDaTFI";
    /** 必须是这个头，写 apikey 会被网关判为 invalid_client (401) */
    private static final String KEY_HEADER = "x-wb-webapp-access-key";
    private static final int POLL_INTERVAL = 20000;
    private static final long STALE_MS = 5 * 60 * 1000L; // 超过 5 分钟的旧消息不补发，避免轰炸

    /** 应用在前台时交给网页处理，避免重复提醒 */
    public static volatile boolean appVisible = false;

    private HandlerThread thread;
    private Handler handler;
    private int notifyId = 500;
    private int tickCount = 0;
    private long lastLogAt = 0;
    private long lastSeenId = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (!appVisible) {
                    pollOnce();
                    tickCount++;
                    if (tickCount % 3 == 0) heartbeat();
                }
            } catch (Exception e) {
                Log.w(TAG, "poll error: " + e.getMessage());
            }
            if (handler != null) handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannels();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentTitle("考务中心运行中");
        b.setContentText("保持消息提醒可用");
        b.setOngoing(true);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3001, b.build(), FgType.specialUse());
        } else {
            startForeground(3001, b.build());
        }

        if (thread == null) {
            thread = new HandlerThread("kaowu-poll");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.postDelayed(tick, 4000);
        }
        logMsg("native: 服务已启动 v" + BuildConfig.VERSION_NAME
                + "（凭证=" + (token().isEmpty() ? "无" : "有") + "）", true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        if (thread != null) thread.quitSafely();
        handler = null;
        thread = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 凭证

    private SharedPreferences sp() {
        return getSharedPreferences("kaowu", MODE_PRIVATE);
    }

    private String token() {
        return sp().getString("access_token", "");
    }

    /** 登录凭证可能过期，后台长期不打开 App 时靠 refresh_token 自己续命 */
    private boolean refreshToken() {
        SharedPreferences p = sp();
        String refresh = p.getString("refresh_token", "");
        if (refresh.isEmpty()) return false;
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", refresh);
            conn = (HttpURLConnection) new URL(AUTH + "/v1/token").openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty(KEY_HEADER, API_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes("utf-8"));
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "refresh http " + conn.getResponseCode());
                return false;
            }
            JSONObject o = new JSONObject(readAll(conn.getInputStream()));
            String at = o.optString("access_token", "");
            if (at.isEmpty()) return false;
            long sec = o.optLong("expires_in", 3600);
            p.edit()
                    .putString("access_token", at)
                    .putString("refresh_token", o.optString("refresh_token", refresh))
                    .putLong("expires_at", System.currentTimeMillis() + sec * 1000L)
                    .apply();
            Log.i(TAG, "token refreshed");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "refresh fail: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 提前 5 分钟主动续期，别等 401 才补 */
    private void ensureFreshToken() {
        long exp = sp().getLong("expires_at", 0);
        if (exp > 0 && System.currentTimeMillis() > exp - 5 * 60 * 1000L) {
            if (refreshToken()) logMsg("native: 凭证已自动续期", true);
        }
    }

    // ---------------------------------------------------------------- 网络

    private String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "utf-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    /** 云端日志：即使 App 没打开，也能从后台看出服务是否在跑 */
    private void logMsg(String msg, boolean always) {
        long now = System.currentTimeMillis();
        if (!always && now - lastLogAt < 300000) return;
        lastLogAt = now;
        String tk = token();
        if (tk.isEmpty()) return; // 没凭证写不进云端，只能等用户打开一次 App 登录
        try {
            JSONObject o = new JSONObject();
            o.put("p_kind", "native");
            o.put("p_detail", msg);
            post("/rpc/log_client_event", tk, o.toString(), false);
        } catch (Exception e) {
            Log.w(TAG, "log fail: " + e.getMessage());
        }
    }

    private void heartbeat() {
        String tk = token();
        logMsg("native: 后台心跳 v" + BuildConfig.VERSION_NAME
                + "（lastId=" + lastSeenId + "，凭证=" + (tk.isEmpty() ? "无" : "有效") + "）", false);
    }

    private void post(String path, String tk, String body, boolean retried) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty(KEY_HEADER, API_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + tk);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes("utf-8"));
            int code = conn.getResponseCode();
            if (code == 401 && !retried && refreshToken()) {
                conn.disconnect();
                conn = null;
                post(path, token(), body, true);
                return;
            }
            Log.i(TAG, "post " + path + " -> " + code);
        } catch (Exception e) {
            Log.w(TAG, "post fail: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONArray fetch(String path, String tk, boolean retried) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty(KEY_HEADER, API_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + tk);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code == 401 && !retried && refreshToken()) {
                conn.disconnect();
                conn = null;
                return fetch(path, token(), true);
            }
            if (code != 200) {
                Log.w(TAG, "fetch http " + code);
                return null;
            }
            return new JSONArray(readAll(conn.getInputStream()));
        } catch (Exception e) {
            Log.w(TAG, "fetch fail: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- 轮询

    private void pollOnce() {
        String tk = token();
        String uid = sp().getString("user_id", "");
        if (tk.isEmpty() || uid.isEmpty()) {
            logMsg("native: 无凭证，需打开一次 App 登录", false);
            return;
        }
        ensureFreshToken();
        tk = token();

        long lastSeen = sp().getLong("native_last_id", 0);
        lastSeenId = lastSeen;
        JSONArray rows = fetch("/messages?select=id,conversation_id,sender_id,sender_name,content,message_type,created_at&order=id.desc&limit=15", tk, false);
        if (rows == null) {
            logMsg("native: 查询失败（网络或凭证被拒）", true);
            return;
        }

        int fresh = 0;
        long maxId = lastSeen;
        for (int i = rows.length() - 1; i >= 0; i--) { // 从旧到新
            JSONObject m = rows.optJSONObject(i);
            if (m == null) continue;
            long id = m.optLong("id", 0);
            if (id > maxId) maxId = id;
            if (lastSeen == 0) continue;      // 首次仅建立基线
            if (id <= lastSeen) continue;
            if (uid.equals(m.optString("sender_id", ""))) continue;
            if (tooOld(m.optString("created_at", ""))) continue;
            if (fresh >= 3) continue;         // 单轮最多 3 条，避免积压轰炸
            fresh++;
            showMessage(m.optString("sender_name", "成员"), m.optString("content", ""));
        }
        if (maxId != lastSeen) {
            sp().edit().putLong("native_last_id", maxId).apply();
            lastSeenId = maxId;
        }
        if (fresh > 0) {
            logMsg("native: 发出 " + fresh + " 条通知（lastId=" + maxId + "）", true);
        } else {
            logMsg("native: 轮询正常，无新消息（lastId=" + maxId + "）", false);
        }
    }

    /** 云端时间形如 2026-11-04T22:50:45.759227+08:00，取前 19 位按本地时区比 */
    private boolean tooOld(String iso) {
        if (iso == null || iso.length() < 19) return false;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            long t = f.parse(iso.substring(0, 19)).getTime();
            return System.currentTimeMillis() - t > STALE_MS;
        } catch (Exception e) {
            return false;
        }
    }

    private void showMessage(String sender, String content) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannels(); // 服务被系统单独拉起时 Activity 可能从未运行，渠道得自己建
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, MSG_CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentTitle("新消息 · " + sender);
        b.setContentText(content);
        b.setStyle(new Notification.BigTextStyle().bigText(content));
        b.setAutoCancel(true);
        b.setSound(soundUri());
        b.setVibrate(new long[]{0, 120, 80, 120});
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                b.setContentIntent(pi);
            }
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifyId++, b.build());
    }

    // ---------------------------------------------------------------- 渠道

    private Uri soundUri() {
        return Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_sound);
    }

    private void ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台保活",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持消息提醒可用");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(MSG_CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(MSG_CHANNEL_ID, "消息通知",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("新消息、任务与@提醒");
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(soundUri(), attrs);
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 120, 80, 120});
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    /** Android 14+ 前台服务类型（避免直接引用高版本常量导致低版本崩溃） */
    private static class FgType {
        static int specialUse() {
            if (Build.VERSION.SDK_INT >= 34) {
                return android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            }
            return 0;
        }
    }
}
