package com.workbuddy.dailytrail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

/**
 * v3.3.2：恢复 v2.7.0 验证过的「纯保活」架子。
 *
 * 服务唯一职责是把进程优先级抬起来，避免系统冻结 WebView；不碰网络、不碰登录凭证。
 * 实际的消息检测由 MainActivity 每 15 秒执行 window.__kaowuNativePoll() 唤起网页轮询，
 * 网页再通过 AndroidNotify.post 发系统通知。
 *
 * 3.0~3.3.1 改成「原生层自己查数据库 + 自己续期 token」，链路长、依赖凭证与请求头，
 * 且 startForegroundService 在部分 ROM 上容易被判超时导致服务起不来，实测后台收不到通知。
 */
public class PollService extends Service {
    private static final String CHANNEL_ID = "kaowu_service";
    private static final String MSG_CHANNEL_ID = "kaowu_messages_v2";

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
            startForeground(3001, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(3001, b.build());
        }
        return START_STICKY;
    }

    private Uri soundUri() {
        return Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.notify_sound);
    }

    /**
     * 顺手把消息渠道也建好：服务被 START_STICKY 单独拉起、用户还没打开过 Activity 时，
     * 网页桥发通知也不会因为渠道不存在而静默失败。
     */
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
}
