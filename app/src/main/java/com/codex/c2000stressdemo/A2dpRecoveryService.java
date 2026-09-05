package com.codex.c2000stressdemo;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.content.pm.PackageManager;

import java.util.concurrent.CopyOnWriteArraySet;

public final class A2dpRecoveryService extends Service
        implements A2dpReconnectController.Listener {
    interface Listener {
        void onA2dpSnapshot(A2dpReconnectController.Snapshot snapshot);

        void onA2dpEvent(String message);
    }

    private static final String CHANNEL_ID = "a2dp_recovery";
    private static final int NOTIFICATION_ID = 7201;

    private final LocalBinder binder = new LocalBinder();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private A2dpReconnectController controller;
    private A2dpReconnectController.Snapshot snapshot;

    static void start(Context context) {
        Intent intent = new Intent(context, A2dpRecoveryService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("A2DP 监控正在启动"));
        controller = new A2dpReconnectController(this, this);
        controller.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (controller != null) {
            controller.refresh();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        listeners.clear();
        super.onDestroy();
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        if (snapshot != null) {
            listener.onA2dpSnapshot(snapshot);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void setAutoReconnect(boolean enabled) {
        if (controller != null) {
            controller.setAutoReconnect(enabled);
        }
    }

    void requestReconnectNow() {
        if (controller != null) {
            controller.requestReconnectNow();
        }
    }

    void resetCounters() {
        if (controller != null) {
            controller.resetCounters();
        }
    }

    void refresh() {
        if (controller != null) {
            controller.refresh();
        }
    }

    A2dpReconnectController.Snapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void onSnapshot(A2dpReconnectController.Snapshot newSnapshot) {
        if (newSnapshot.sameContent(snapshot)) {
            return;
        }
        snapshot = newSnapshot;
        NotificationManager manager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        boolean canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (manager != null && canPost) {
            manager.notify(NOTIFICATION_ID,
                    buildNotification(newSnapshot.connectionState + " / "
                            + newSnapshot.recoveryState));
        }
        for (Listener listener : listeners) {
            listener.onA2dpSnapshot(newSnapshot);
        }
    }

    @Override
    public void onEvent(String message) {
        for (Listener listener : listeners) {
            listener.onA2dpEvent(message);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "A2DP 自动重连",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("监控 WI-C100 A2DP 状态并执行受控重连");
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String detail) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("C2000 A2DP 自动重连")
                .setContentText(detail)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    final class LocalBinder extends Binder {
        A2dpRecoveryService getService() {
            return A2dpRecoveryService.this;
        }
    }
}
