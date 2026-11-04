package com.workbuddy.dailytrail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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

/**
 * 原生轮询服务：不依赖 WebView，自己在后台定时查数据库、直接发系统通知。
 * 即使网页被安卓冻结/回收，提醒依然有效。
 */
public class PollService extends Service {
    private static final String TAG = "PollService";
    private static final String CHANNEL_ID = "kaowu_service";
    private static final String MSG_CHANNEL_ID = "kaowu_messages_v2";
    private static final String BASE = "https://daily-trail-78645.app.workbuddy.host/.cloud/database/rest";
    private static final String API_KEY = "wbpk_XrCfFEr0yAsY946ghnW29f_jQcMF3iXdy3as1jlMBIDOtc2IQfDaTFI";
    private static final int POLL_INTERVAL = 20000;

    /** 应用在前台时交给网页处理，避免重复提醒 */
    public static volatile boolean appVisible = false;

    private HandlerThread thread;
    private Handler handler;
    private int notifyId = 500;
    private int tickCount = 0;
    private long lastLogAt = 0;
    private boolean lastHttpOk = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                if (!appVisible) {
                    pollOnce();
                    // 每 3 轮（约 1 分钟）上报一次在线状态，让 web 端的「最后活跃」准确
                    tickCount++;
                    if (tickCount % 3 == 0) reportClient();
                }
            } catch (Exception e) {
                Log.w(TAG, "poll error: " + e.getMessage());
            }
            if (handler != null) handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    /** 原生层直接上报在线状态（网页被冻结也能维持「最后活跃」） */
    private void reportClient() {
        SharedPreferences sp = getSharedPreferences("kaowu", MODE_PRIVATE);
        String token = sp.getString("access_token", "");
        if (token.isEmpty()) {
            logMsg("native: 没有拿到登录凭证，跳过轮询", token, true);
            return;
        }
        try {
            String body = "{\"p_platform\":\"android\",\"p_version\":\"" + BuildConfig.VERSION_NAME
                    + "\",\"p_code\":" + BuildConfig.VERSION_CODE + ",\"p_ua\":\"native-service\"}";
            post("/rpc/report_client_info", token, body);
        } catch (Exception e) {
            Log.w(TAG, "report fail: " + e.getMessage());
        }
    }

    /** 把原生侧的状况写进云端日志，便于远程排查（限流：5 分钟内最多一条；错误不限） */
    private void logMsg(String msg, String token, boolean always) {
        if (token == null || token.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!always && now - lastLogAt < 300000) return;
        lastLogAt = now;
        try {
            JSONObject o = new JSONObject();
            o.put("p_kind", "native");
            o.put("p_detail", msg);
            post("/rpc/log_client_event", token, o.toString());
        } catch (Exception e) {
            Log.w(TAG, "log fail: " + e.getMessage());
        }
    }

    private void post(String path, String token, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey", API_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.getBytes("utf-8"));
            Log.i(TAG, "report " + conn.getResponseCode());
        } catch (Exception e) {
            Log.w(TAG, "post fail: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
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
            handler.postDelayed(tick, 6000);
        }
        logMsg("native: 服务已启动 v" + BuildConfig.VERSION_NAME, sp_token(), true);
        return START_STICKY;
    }

    private String sp_token() {
        return getSharedPreferences("kaowu", MODE_PRIVATE).getString("access_token", "");
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacks(tick);
        if (thread != null) thread.quitSafely();
        handler = null;
        thread = null;
        super.onDestroy();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台保活",
                        NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("保持消息提醒可用");
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** 查一次最新消息，对新的、非本人发的消息发系统通知 */
    private void pollOnce() {
        SharedPreferences sp = getSharedPreferences("kaowu", MODE_PRIVATE);
        String token = sp.getString("access_token", "");
        String uid = sp.getString("user_id", "");
        if (token.isEmpty() || uid.isEmpty()) return;

        long lastSeen = sp.getLong("native_last_id", 0);
        JSONArray rows = fetch("/messages?select=id,conversation_id,sender_id,sender_name,content,message_type,created_at&order=id.desc&limit=15", token);
        if (rows == null) {
            logMsg("native: 查询失败（网络或凭证被拒），lastId=" + lastSeen, token, true);
            return;
        }
        lastHttpOk = true;

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
            fresh++;
            showMessage(m.optString("sender_name", "成员"), m.optString("content", ""));
        }
        if (maxId != lastSeen) sp.edit().putLong("native_last_id", maxId).apply();
        if (fresh > 0) {
            logMsg("native: 发出 " + fresh + " 条通知（lastId=" + maxId + "）", token, true);
        } else if (!lastHttpOk) {
            logMsg("native: 轮询正常（无新消息）", token, false);
        }
    }

    private JSONArray fetch(String path, String token) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("apikey", API_KEY);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "application/json");
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "http " + conn.getResponseCode());
                return null;
            }
            InputStream in = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "utf-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new JSONArray(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "fetch fail: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void showMessage(String sender, String content) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, MSG_CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentTitle("新消息 · " + sender);
        b.setContentText(content);
        b.setStyle(new Notification.BigTextStyle().bigText(content));
        b.setAutoCancel(true);
        b.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_sound));
        b.setVibrate(new long[]{0, 120, 80, 120});
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifyId++, b.build());
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
