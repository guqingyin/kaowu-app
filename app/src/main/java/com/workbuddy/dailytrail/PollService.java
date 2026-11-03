package com.workbuddy.dailytrail;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务：唯一职责是提升进程优先级，避免安卓冻结 WebView（网页轮询因此停止）。
 * 实际的消息检测由 MainActivity 定时对 WebView 执行 window.__kaowuNativePoll() 完成。
 */
public class PollService extends Service {
    private static final String CHANNEL_ID = "kaowu_service";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台保活",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持消息提醒可用");
            nm.createNotificationChannel(ch);
        }
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
}
