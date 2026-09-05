package com.codex.c2000stressdemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class A2dpReconnectController {
    interface Listener {
        void onSnapshot(Snapshot snapshot);

        void onEvent(String message);
    }

    static final class Snapshot {
        final String targetName;
        final String targetAddress;
        final String connectionState;
        final String recoveryState;
        final String activeRouteState;
        final String lastError;
        final boolean autoReconnect;
        final boolean recoveryInProgress;
        final int disconnectEvents;
        final int reconnectAttempts;
        final int reconnectSuccesses;
        final int reconnectFailures;
        final long lastDisconnectAt;

        Snapshot(String targetName, String targetAddress, String connectionState,
                String recoveryState, String activeRouteState, String lastError, boolean autoReconnect,
                boolean recoveryInProgress, int disconnectEvents, int reconnectAttempts,
                int reconnectSuccesses, int reconnectFailures, long lastDisconnectAt) {
            this.targetName = targetName;
            this.targetAddress = targetAddress;
            this.connectionState = connectionState;
            this.recoveryState = recoveryState;
            this.activeRouteState = activeRouteState;
            this.lastError = lastError;
            this.autoReconnect = autoReconnect;
            this.recoveryInProgress = recoveryInProgress;
            this.disconnectEvents = disconnectEvents;
            this.reconnectAttempts = reconnectAttempts;
            this.reconnectSuccesses = reconnectSuccesses;
            this.reconnectFailures = reconnectFailures;
            this.lastDisconnectAt = lastDisconnectAt;
        }

        String statusText() {
            String target = TextUtils.isEmpty(targetName) ? "等待 WI-C100" : targetName;
            String address = TextUtils.isEmpty(targetAddress) ? "" : " / " + targetAddress;
            return "A2DP：" + connectionState + " ｜ " + target + address
                    + "\n恢复：" + recoveryState + " ｜ 自动：" + (autoReconnect ? "开启" : "关闭")
                    + "\n音频路由：" + activeRouteState;
        }

        String countersText() {
            return "A2DP 断联 " + disconnectEvents
                    + " ｜ 重连尝试 " + reconnectAttempts
                    + " ｜ 成功 " + reconnectSuccesses
                    + " ｜ 失败 " + reconnectFailures;
        }

        boolean sameContent(Snapshot other) {
            return other != null
                    && TextUtils.equals(targetName, other.targetName)
                    && TextUtils.equals(targetAddress, other.targetAddress)
                    && TextUtils.equals(connectionState, other.connectionState)
                    && TextUtils.equals(recoveryState, other.recoveryState)
                    && TextUtils.equals(activeRouteState, other.activeRouteState)
                    && TextUtils.equals(lastError, other.lastError)
                    && autoReconnect == other.autoReconnect
                    && recoveryInProgress == other.recoveryInProgress
                    && disconnectEvents == other.disconnectEvents
                    && reconnectAttempts == other.reconnectAttempts
                    && reconnectSuccesses == other.reconnectSuccesses
                    && reconnectFailures == other.reconnectFailures
                    && lastDisconnectAt == other.lastDisconnectAt;
        }
    }

    private static final String PREFS = "a2dp_recovery";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    private static final String PREF_TARGET_ADDRESS = "target_address";
    private static final String PREF_TARGET_NAME = "target_name";
    private static final String TARGET_NAME_TOKEN = "WI-C100";
    private static final long AUTOMATIC_DEBOUNCE_MS = 1_200L;
    private static final long[] NEXT_DIRECT_RETRY_MS = {3_000L, 5_000L, 8_000L};
    private static final long DIRECT_ACCEPTED_WAIT_MS = 5_000L;
    private static final long DIRECT_PHASE_TIMEOUT_MS = 40_000L;
    private static final long[] NEXT_RECOVERY_ROUND_RETRY_MS = {
            15_000L, 30_000L, 60_000L, 120_000L
    };
    private static final long PROFILE_HEALTH_CHECK_MS = 2_000L;
    private static final long PROFILE_PROXY_RETRY_MS = 5_000L;
    private static final long PROFILE_PROXY_BIND_TIMEOUT_MS = 15_000L;
    private static final long ACL_STATE_FRESHNESS_MS = 10_000L;
    private static final long[] ACTIVE_ROUTE_VERIFY_DELAYS_MS = {800L, 2_000L, 5_000L};
    private static final int MAX_DIRECT_ATTEMPTS = 4;
    private static final int MAX_RECOVERY_ROUNDS = 5;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    handler.post(() -> handleProfileConnected(profile, proxy));
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    handler.post(() -> handleProfileDisconnected(profile));
                }
            };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            handleBroadcast(intent);
        }
    };
    private final Runnable healthCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            checkProfileHealth();
            handler.postDelayed(this, PROFILE_HEALTH_CHECK_MS);
        }
    };
    private final Runnable activeRouteVerificationRunnable = new Runnable() {
        @Override
        public void run() {
            activeRouteCheckScheduled = false;
            verifyActiveDevice();
        }
    };

    private BluetoothAdapter adapter;
    private BluetoothProfile a2dpProxy;
    private BluetoothProfile headsetProxy;
    private BluetoothDevice targetDevice;
    private String targetName;
    private boolean started;
    private boolean receiverRegistered;
    private boolean a2dpProxyRequested;
    private boolean headsetProxyRequested;
    private long a2dpProxyRequestedAt;
    private long headsetProxyRequestedAt;
    private boolean autoReconnect;
    private boolean recoveryInProgress;
    private boolean targetAclConnected;
    private boolean targetAclKnown;
    private boolean automaticRecoverySuppressed;
    private boolean disconnectEpisodeRecorded;
    private boolean recoveryRetryScheduled;
    private boolean activeRouteCheckScheduled;
    private int generation;
    private int directAttemptIndex;
    private int recoveryRoundIndex;
    private int activeRouteVerificationAttempt;
    private int a2dpState = BluetoothProfile.STATE_DISCONNECTED;
    private int headsetState = BluetoothProfile.STATE_DISCONNECTED;
    private int disconnectEvents;
    private int reconnectAttempts;
    private int reconnectSuccesses;
    private int reconnectFailures;
    private long lastDisconnectAt;
    private long lastAclEventAtElapsed;
    private long recoveryStartedAtElapsed;
    private String recoveryState = "服务启动中";
    private String activeRouteState = "未验证";
    private String lastError = "";

    A2dpReconnectController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, true);
    }

    void start() {
        if (started) {
            refresh();
            return;
        }
        started = true;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(
                Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        restoreTarget();
        refresh();
        handler.removeCallbacks(healthCheckRunnable);
        handler.postDelayed(healthCheckRunnable, PROFILE_HEALTH_CHECK_MS);
    }

    void refresh() {
        if (!started) {
            start();
            return;
        }
        if (adapter == null) {
            recoveryState = "本机无蓝牙适配器";
            publish();
            return;
        }
        if (!hasConnectPermission()) {
            recoveryState = "等待 BLUETOOTH_CONNECT 权限";
            publish();
            return;
        }
        registerReceiverIfNeeded();
        requestProfileProxies();
        discoverTarget();
        refreshProfileStates();
        if (adapter.getState() == BluetoothAdapter.STATE_OFF) {
            recoveryState = "蓝牙已关闭";
        } else if (targetDevice == null) {
            recoveryState = "等待 WI-C100 连接";
        } else if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
            recoveryState = "连接正常";
        } else if (!recoveryInProgress) {
            recoveryState = "已断开，等待事件或手动重连";
        }
        publish();
    }

    void shutdown() {
        if (!started) {
            return;
        }
        started = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed by the framework.
            }
            receiverRegistered = false;
        }
        closeProfileProxy(BluetoothProfile.A2DP, a2dpProxy);
        closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy);
        a2dpProxy = null;
        headsetProxy = null;
        a2dpProxyRequested = false;
        headsetProxyRequested = false;
    }

    Snapshot getSnapshot() {
        return new Snapshot(
                targetName,
                targetDevice == null ? "" : safeAddress(targetDevice),
                a2dpProxy == null ? "Profile 服务连接中" : profileStateLabel(a2dpState),
                recoveryState,
                activeRouteState,
                lastError,
                autoReconnect,
                recoveryInProgress,
                disconnectEvents,
                reconnectAttempts,
                reconnectSuccesses,
                reconnectFailures,
                lastDisconnectAt);
    }

    void setAutoReconnect(boolean enabled) {
        if (autoReconnect == enabled) {
            publish();
            return;
        }
        autoReconnect = enabled;
        preferences.edit().putBoolean(PREF_AUTO_RECONNECT, enabled).apply();
        if (!enabled) {
            cancelRecovery("自动重连已关闭");
        } else {
            automaticRecoverySuppressed = false;
            recoveryRoundIndex = 0;
            emitEvent("A2DP 自动重连已开启。");
            if (a2dpState == BluetoothProfile.STATE_DISCONNECTED && targetDevice != null) {
                beginRecovery(false);
            }
        }
        publish();
    }

    void requestReconnectNow() {
        refresh();
        if (targetDevice == null) {
            lastError = "未找到已配对的 WI-C100";
            recoveryState = "无可重连目标";
            emitEvent("无法重连：未找到已配对或曾连接的 WI-C100。");
            return;
        }
        beginRecovery(true);
    }

    void resetCounters() {
        disconnectEvents = 0;
        reconnectAttempts = 0;
        reconnectSuccesses = 0;
        reconnectFailures = 0;
        lastDisconnectAt = 0L;
        lastError = "";
        publish();
    }

    @SuppressLint({"MissingPermission", "UnspecifiedRegisterReceiverFlag"})
    private void registerReceiverIfNeeded() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH_CONNECT,
                    handler, Context.RECEIVER_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.registerReceiver(receiver, filter, Manifest.permission.BLUETOOTH_CONNECT,
                    handler);
        } else {
            context.registerReceiver(receiver, filter, null, handler);
        }
        receiverRegistered = true;
    }

    @SuppressLint("MissingPermission")
    private void requestProfileProxies() {
        long now = SystemClock.elapsedRealtime();
        if (a2dpProxy == null && a2dpProxyRequested
                && now - a2dpProxyRequestedAt >= PROFILE_PROXY_BIND_TIMEOUT_MS) {
            a2dpProxyRequested = false;
        }
        if (headsetProxy == null && headsetProxyRequested
                && now - headsetProxyRequestedAt >= PROFILE_PROXY_BIND_TIMEOUT_MS) {
            headsetProxyRequested = false;
        }
        if (a2dpProxy == null && !a2dpProxyRequested && (a2dpProxyRequestedAt == 0L
                || now - a2dpProxyRequestedAt >= PROFILE_PROXY_RETRY_MS)) {
            a2dpProxyRequestedAt = now;
            a2dpProxyRequested = adapter.getProfileProxy(context, profileListener,
                    BluetoothProfile.A2DP);
        }
        if (headsetProxy == null && !headsetProxyRequested && (headsetProxyRequestedAt == 0L
                || now - headsetProxyRequestedAt >= PROFILE_PROXY_RETRY_MS)) {
            headsetProxyRequestedAt = now;
            headsetProxyRequested = adapter.getProfileProxy(context, profileListener,
                    BluetoothProfile.HEADSET);
        }
    }

    private void handleProfileConnected(int profile, BluetoothProfile proxy) {
        if (!started) {
            closeProfileProxy(profile, proxy);
            return;
        }
        if (profile == BluetoothProfile.A2DP) {
            if (a2dpProxy != null && a2dpProxy != proxy) {
                closeProfileProxy(profile, proxy);
                return;
            }
            a2dpProxy = proxy;
            a2dpProxyRequested = true;
            a2dpProxyRequestedAt = 0L;
        } else if (profile == BluetoothProfile.HEADSET) {
            if (headsetProxy != null && headsetProxy != proxy) {
                closeProfileProxy(profile, proxy);
                return;
            }
            headsetProxy = proxy;
            headsetProxyRequested = true;
            headsetProxyRequestedAt = 0L;
        } else {
            return;
        }
        discoverTarget();
        refreshProfileStates();
        if (profile == BluetoothProfile.A2DP
                && a2dpState == BluetoothProfile.STATE_CONNECTED) {
            scheduleActiveDeviceVerification(false);
        }
        maybeRecoverDisconnectedState();
        publish();
    }

    private void handleProfileDisconnected(int profile) {
        if (!started) {
            return;
        }
        if (profile == BluetoothProfile.A2DP) {
            a2dpProxy = null;
            a2dpProxyRequested = false;
            a2dpProxyRequestedAt = 0L;
            cancelActiveDeviceVerification("Profile 服务不可用，无法验证");
        } else if (profile == BluetoothProfile.HEADSET) {
            headsetProxy = null;
            headsetProxyRequested = false;
            headsetProxyRequestedAt = 0L;
            headsetState = BluetoothProfile.STATE_DISCONNECTED;
        }
        if (started && adapter != null && hasConnectPermission()
                && adapter.getState() == BluetoothAdapter.STATE_ON) {
            handler.postDelayed(this::requestProfileProxies, 800L);
        }
        publish();
    }

    private void handleBroadcast(Intent intent) {
        if (intent == null || !started || !hasConnectPermission()) {
            return;
        }
        String action = intent.getAction();
        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            int previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            handleA2dpState(device, previous, state);
        } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                    BluetoothProfile.STATE_DISCONNECTED);
            handleHeadsetState(device, state);
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (isTarget(device)) {
                targetAclKnown = true;
                targetAclConnected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
                lastAclEventAtElapsed = SystemClock.elapsedRealtime();
                if (!targetAclConnected && (recoveryInProgress || recoveryRetryScheduled)) {
                    cancelRecovery("ACL 已断开，不执行 A2DP 单链路恢复");
                }
                publish();
            }
        } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            handleAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR));
        }
    }

    private void handleA2dpState(BluetoothDevice device, int previous, int state) {
        if (device == null) {
            return;
        }
        if (state == BluetoothProfile.STATE_CONNECTED && isCandidate(device)) {
            adoptTarget(device);
        }
        if (!isTarget(device)) {
            return;
        }
        a2dpState = state;
        if (state == BluetoothProfile.STATE_CONNECTED) {
            automaticRecoverySuppressed = false;
            disconnectEpisodeRecorded = false;
            boolean activeRecovery = recoveryInProgress;
            boolean scheduledRecovery = recoveryRetryScheduled;
            long recoveryMs = activeRecovery
                    ? SystemClock.elapsedRealtime() - recoveryStartedAtElapsed : 0L;
            finishRecovery();
            recoveryState = "连接正常";
            lastError = "";
            if (activeRecovery) {
                reconnectSuccesses++;
                emitEvent("A2DP 已恢复连接，耗时 " + recoveryMs + " ms。");
            } else if (scheduledRecovery) {
                emitEvent("A2DP 已自行恢复，已取消待执行自动重试。");
            } else {
                emitEvent("A2DP 已连接：" + targetLabel() + "。");
            }
            trySetActiveDevice();
        } else if (state == BluetoothProfile.STATE_CONNECTING) {
            recoveryState = "正在连接 A2DP";
            emitEvent("A2DP 状态：CONNECTING。");
        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
            cancelActiveDeviceVerification("不可用");
            if (previous == BluetoothProfile.STATE_CONNECTED
                    || previous == BluetoothProfile.STATE_DISCONNECTING) {
                recordDisconnectEpisode("A2DP 广播");
            }
        }
        publish();
    }

    private void handleHeadsetState(BluetoothDevice device, int state) {
        if (device == null) {
            return;
        }
        if (state == BluetoothProfile.STATE_CONNECTED && isCandidate(device)) {
            adoptTarget(device);
        }
        if (!isTarget(device)) {
            return;
        }
        headsetState = state;
        publish();
    }

    @SuppressLint("MissingPermission")
    private void handleAdapterState(int state) {
        if (state == BluetoothAdapter.STATE_OFF) {
            cancelRecovery("蓝牙已关闭");
            a2dpState = BluetoothProfile.STATE_DISCONNECTED;
            cancelActiveDeviceVerification("不可用");
            recoveryState = "蓝牙已关闭";
            publish();
        } else if (state == BluetoothAdapter.STATE_TURNING_OFF) {
            cancelRecovery("蓝牙正在关闭");
        } else if (state == BluetoothAdapter.STATE_ON) {
            requestProfileProxies();
            handler.postDelayed(this::refresh, 500L);
        }
    }

    private void beginRecovery(boolean manual) {
        if (!started || !hasConnectPermission()) {
            lastError = "缺少 BLUETOOTH_CONNECT 权限";
            recoveryState = "无法重连";
            publish();
            return;
        }
        if (manual) {
            recoveryRetryScheduled = false;
            recoveryRoundIndex = 0;
            automaticRecoverySuppressed = false;
        }
        discoverTarget();
        refreshProfileStates();
        if (targetDevice == null) {
            lastError = "未找到 WI-C100";
            recoveryState = "无可重连目标";
            publish();
            return;
        }
        if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
            recoveryState = "连接正常";
            emitEvent("A2DP 已处于连接状态，无需重连。");
            return;
        }
        if (recoveryInProgress && !manual) {
            return;
        }
        if (!manual && recoveryRoundIndex >= MAX_RECOVERY_ROUNDS) {
            finishFailedRecovery("自动重试已达到 " + MAX_RECOVERY_ROUNDS + " 轮上限");
            return;
        }
        generation++;
        int currentGeneration = generation;
        recoveryInProgress = true;
        recoveryRetryScheduled = false;
        directAttemptIndex = 0;
        recoveryRoundIndex++;
        recoveryStartedAtElapsed = SystemClock.elapsedRealtime();
        recoveryState = manual ? "手动重连已排队"
                : "自动重连第 " + recoveryRoundIndex + "/" + MAX_RECOVERY_ROUNDS + " 轮去抖中";
        lastError = "";
        publish();
        handler.postDelayed(() -> attemptDirectConnect(currentGeneration, manual),
                manual ? 0L : AUTOMATIC_DEBOUNCE_MS);
    }

    @SuppressLint("MissingPermission")
    private void attemptDirectConnect(int expectedGeneration, boolean manual) {
        if (!isRecoveryCurrent(expectedGeneration)) {
            return;
        }
        refreshProfileStates();
        if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
            completeRecoveryFromPoll();
            return;
        }
        if (SystemClock.elapsedRealtime() - recoveryStartedAtElapsed >= DIRECT_PHASE_TIMEOUT_MS) {
            failRecovery("A2DP Profile 服务或连接状态超过 40 秒未完成", manual, true);
            return;
        }
        if (!manual && !isTargetTransportOnline()) {
            failRecovery("HFP/ACL 也已断开，不执行 A2DP 单链路恢复", manual, false);
            return;
        }
        if (adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON) {
            failRecovery("蓝牙适配器未处于 ON 状态", manual, false);
            return;
        }
        if (targetDevice.getBondState() != BluetoothDevice.BOND_BONDED) {
            failRecovery("WI-C100 已解除配对", manual, false);
            return;
        }
        if (a2dpProxy == null) {
            requestProfileProxies();
            recoveryState = "等待 A2DP Profile 服务";
            publish();
            handler.postDelayed(() -> attemptDirectConnect(expectedGeneration, manual), 1_000L);
            return;
        }
        if (a2dpState == BluetoothProfile.STATE_CONNECTING) {
            recoveryState = "等待 A2DP CONNECTING 完成";
            publish();
            handler.postDelayed(() -> attemptDirectConnect(expectedGeneration, manual), 3_000L);
            return;
        }
        if (directAttemptIndex >= MAX_DIRECT_ATTEMPTS) {
            failRecovery("4 次 A2DP Profile 重连均未收到 CONNECTED", manual, true);
            return;
        }

        directAttemptIndex++;
        reconnectAttempts++;
        recoveryState = "Profile 重连尝试 " + directAttemptIndex + "/" + MAX_DIRECT_ATTEMPTS;
        publish();
        DirectConnectResult result = invokeHiddenConnect();
        emitEvent("A2DP Profile 重连 #" + directAttemptIndex + "：" + result.message + "。");
        if (!result.supported) {
            failRecovery("系统不允许应用直接连接 A2DP，请使用蓝牙设置", manual, false);
            return;
        }
        if (directAttemptIndex >= MAX_DIRECT_ATTEMPTS) {
            handler.postDelayed(() -> {
                if (!isRecoveryCurrent(expectedGeneration)) {
                    return;
                }
                refreshProfileStates();
                if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
                    completeRecoveryFromPoll();
                } else {
                    failRecovery("4 次 A2DP Profile 重连均未收到 CONNECTED", manual, true);
                }
            }, result.accepted ? DIRECT_ACCEPTED_WAIT_MS : 1_500L);
            return;
        }
        long delay = result.accepted
                ? DIRECT_ACCEPTED_WAIT_MS
                : NEXT_DIRECT_RETRY_MS[Math.min(directAttemptIndex - 1,
                        NEXT_DIRECT_RETRY_MS.length - 1)];
        handler.postDelayed(() -> attemptDirectConnect(expectedGeneration, manual), delay);
    }

    @SuppressLint("MissingPermission")
    private DirectConnectResult invokeHiddenConnect() {
        try {
            Method connect = a2dpProxy.getClass().getMethod("connect", BluetoothDevice.class);
            Object value = connect.invoke(a2dpProxy, targetDevice);
            boolean accepted = value instanceof Boolean && (Boolean) value;
            return new DirectConnectResult(true, accepted,
                    accepted ? "系统已受理，等待 CONNECTED 广播" : "系统返回 false");
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            return new DirectConnectResult(false, false,
                    "系统隐藏 API 不可用（" + exception.getClass().getSimpleName() + "）");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            boolean permissionFailure = cause instanceof SecurityException;
            return new DirectConnectResult(!permissionFailure, false,
                    "调用失败（" + safeThrowable(cause == null ? exception : cause) + "）");
        } catch (RuntimeException exception) {
            return new DirectConnectResult(false, false,
                    "调用被系统拦截（" + safeThrowable(exception) + "）");
        }
    }

    private void finishRecovery() {
        generation++;
        recoveryInProgress = false;
        recoveryRetryScheduled = false;
        directAttemptIndex = 0;
        recoveryRoundIndex = 0;
    }

    private void cancelRecovery(String reason) {
        if (recoveryInProgress || recoveryRetryScheduled) {
            generation++;
        }
        recoveryInProgress = false;
        recoveryRetryScheduled = false;
        directAttemptIndex = 0;
        recoveryRoundIndex = 0;
        recoveryState = reason;
        publish();
    }

    private void failRecovery(String reason, boolean manual, boolean retryable) {
        if (!recoveryInProgress) {
            return;
        }
        if (retryable && !manual && scheduleNextRecoveryRound(reason)) {
            return;
        }
        finishFailedRecovery(reason);
    }

    private boolean scheduleNextRecoveryRound(String reason) {
        if (!autoReconnect || targetDevice == null
                || recoveryRoundIndex >= MAX_RECOVERY_ROUNDS
                || !isTargetTransportOnline()) {
            return false;
        }
        generation++;
        recoveryInProgress = false;
        recoveryRetryScheduled = true;
        directAttemptIndex = 0;
        lastError = reason;
        int nextRound = recoveryRoundIndex + 1;
        long retryDelayMs = NEXT_RECOVERY_ROUND_RETRY_MS[Math.min(
                recoveryRoundIndex - 1, NEXT_RECOVERY_ROUND_RETRY_MS.length - 1)];
        recoveryState = "等待自动重试 " + nextRound + "/" + MAX_RECOVERY_ROUNDS
                + "（" + retryDelayMs / 1_000L + " 秒）";
        emitEvent("A2DP 第 " + recoveryRoundIndex + "/" + MAX_RECOVERY_ROUNDS
                + " 轮恢复失败：" + reason + "；"
                + retryDelayMs / 1_000L + " 秒后继续重试。");
        int expectedGeneration = generation;
        handler.postDelayed(() -> resumeScheduledRecovery(expectedGeneration), retryDelayMs);
        return true;
    }

    private void resumeScheduledRecovery(int expectedGeneration) {
        if (!started || generation != expectedGeneration || !recoveryRetryScheduled) {
            return;
        }
        recoveryRetryScheduled = false;
        refreshProfileStates();
        if (!autoReconnect) {
            cancelRecovery("自动重连已关闭");
            return;
        }
        if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
            finishRecovery();
            recoveryState = "连接正常";
            lastError = "";
            emitEvent("A2DP 已自行恢复，取消待执行自动重试。");
            trySetActiveDevice();
            return;
        }
        if (!isTargetTransportOnline()) {
            finishFailedRecovery("HFP/ACL 也已断开，不执行 A2DP 单链路恢复");
            return;
        }
        emitEvent("开始第 " + (recoveryRoundIndex + 1) + "/"
                + MAX_RECOVERY_ROUNDS + " 轮 A2DP 自动重试。");
        beginRecovery(false);
    }

    private void finishFailedRecovery(String reason) {
        generation++;
        recoveryInProgress = false;
        recoveryRetryScheduled = false;
        directAttemptIndex = 0;
        recoveryRoundIndex = 0;
        reconnectFailures++;
        automaticRecoverySuppressed = true;
        lastError = reason;
        recoveryState = "重连失败";
        emitEvent("A2DP 重连失败：" + reason + "。");
    }

    private void completeRecoveryFromPoll() {
        if (!recoveryInProgress) {
            return;
        }
        long recoveryMs = SystemClock.elapsedRealtime() - recoveryStartedAtElapsed;
        finishRecovery();
        automaticRecoverySuppressed = false;
        disconnectEpisodeRecorded = false;
        reconnectSuccesses++;
        recoveryState = "连接正常";
        lastError = "";
        emitEvent("A2DP 状态查询确认已恢复，耗时 " + recoveryMs + " ms。");
        trySetActiveDevice();
    }

    private void checkProfileHealth() {
        if (!hasConnectPermission() || adapter == null) {
            return;
        }
        if (adapter.getState() == BluetoothAdapter.STATE_ON) {
            requestProfileProxies();
        }
        int previous = a2dpState;
        refreshProfileStates();
        if (recoveryInProgress && a2dpState == BluetoothProfile.STATE_CONNECTED) {
            completeRecoveryFromPoll();
            return;
        }
        if (a2dpState == BluetoothProfile.STATE_CONNECTED) {
            if (recoveryRetryScheduled) {
                finishRecovery();
                recoveryState = "连接正常";
                lastError = "";
                emitEvent("A2DP 已自行恢复，已取消待执行自动重试。");
                trySetActiveDevice();
                return;
            }
            disconnectEpisodeRecorded = false;
            automaticRecoverySuppressed = false;
            if (previous != BluetoothProfile.STATE_CONNECTED) {
                recoveryState = "连接正常";
                lastError = "";
                scheduleActiveDeviceVerification(false);
            }
            publish();
            return;
        }
        if (a2dpProxy != null
                && previous == BluetoothProfile.STATE_CONNECTED
                && a2dpState == BluetoothProfile.STATE_DISCONNECTED) {
            recordDisconnectEpisode("状态查询");
            return;
        }
        maybeRecoverDisconnectedState();
        publish();
    }

    private void maybeRecoverDisconnectedState() {
        if (!autoReconnect || automaticRecoverySuppressed || recoveryInProgress
                || recoveryRetryScheduled
                || targetDevice == null
                || a2dpState != BluetoothProfile.STATE_DISCONNECTED
                || adapter == null || adapter.getState() != BluetoothAdapter.STATE_ON) {
            return;
        }
        if (isTargetTransportOnline()) {
            recordDisconnectEpisode("状态同步");
        }
    }

    private void recordDisconnectEpisode(String source) {
        if (disconnectEpisodeRecorded) {
            return;
        }
        disconnectEpisodeRecorded = true;
        automaticRecoverySuppressed = false;
        disconnectEvents++;
        lastDisconnectAt = System.currentTimeMillis();
        emitEvent(source + "检测到 A2DP 断联：" + targetLabel()
                + "，HFP/ACL 状态将在重连前复核。");
        if (autoReconnect) {
            beginRecovery(false);
        } else {
            recoveryState = "已断开，自动重连关闭";
            publish();
        }
    }

    private boolean isRecoveryCurrent(int expectedGeneration) {
        return started && recoveryInProgress && generation == expectedGeneration;
    }

    @SuppressLint("MissingPermission")
    private void discoverTarget() {
        if (targetDevice != null) {
            return;
        }
        BluetoothDevice candidate = findConnectedCandidate(a2dpProxy);
        if (candidate == null) {
            candidate = findConnectedCandidate(headsetProxy);
        }
        if (candidate == null && adapter != null) {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice device : bonded) {
                        if (nameMatchesTarget(safeName(device))) {
                            candidate = device;
                            break;
                        }
                    }
                }
            } catch (SecurityException ignored) {
                // Permission state is reflected in the controller status.
            }
        }
        if (candidate != null) {
            adoptTarget(candidate);
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findConnectedCandidate(BluetoothProfile proxy) {
        if (proxy == null) {
            return null;
        }
        try {
            List<BluetoothDevice> devices = proxy.getConnectedDevices();
            if (devices != null) {
                for (BluetoothDevice device : devices) {
                    if (isCandidate(device)) {
                        return device;
                    }
                }
            }
        } catch (SecurityException ignored) {
            // Permission state is reflected in the controller status.
        }
        return null;
    }

    @SuppressLint("MissingPermission")
    private void refreshProfileStates() {
        if (targetDevice == null || !hasConnectPermission()) {
            return;
        }
        try {
            if (a2dpProxy != null) {
                a2dpState = a2dpProxy.getConnectionState(targetDevice);
            }
            if (headsetProxy != null) {
                headsetState = headsetProxy.getConnectionState(targetDevice);
            }
        } catch (SecurityException exception) {
            lastError = safeThrowable(exception);
        }
    }

    private boolean isTargetTransportOnline() {
        refreshProfileStates();
        boolean recentAclEvent = targetAclKnown
                && SystemClock.elapsedRealtime() - lastAclEventAtElapsed
                <= ACL_STATE_FRESHNESS_MS;
        if (recentAclEvent && !targetAclConnected) {
            return false;
        }
        boolean recentAclConnected = recentAclEvent && targetAclConnected;
        return (headsetProxy != null && headsetState == BluetoothProfile.STATE_CONNECTED)
                || recentAclConnected;
    }

    @SuppressLint("MissingPermission")
    private void trySetActiveDevice() {
        if (a2dpProxy == null || targetDevice == null) {
            activeRouteState = "Profile 已连接，活动路由无法检查";
            publish();
            return;
        }
        try {
            Method method = a2dpProxy.getClass().getMethod("setActiveDevice", BluetoothDevice.class);
            Object value = method.invoke(a2dpProxy, targetDevice);
            if (value instanceof Boolean && (Boolean) value) {
                activeRouteState = "切换请求已受理，等待确认";
                emitEvent("WI-C100 活动 A2DP 音频设备切换请求已受理。");
            } else {
                activeRouteState = "Profile 已连接，系统拒绝切换活动路由";
                emitEvent("A2DP 已连接，但 setActiveDevice 返回 false。");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            activeRouteState = "Profile 已连接，活动路由接口不可用";
            emitEvent("A2DP 已连接，但无法调用活动路由接口："
                    + exception.getClass().getSimpleName() + "。");
        }
        scheduleActiveDeviceVerification(true);
    }

    private void scheduleActiveDeviceVerification(boolean restart) {
        if (restart) {
            handler.removeCallbacks(activeRouteVerificationRunnable);
            activeRouteCheckScheduled = false;
            activeRouteVerificationAttempt = 0;
        }
        if (activeRouteCheckScheduled) {
            return;
        }
        if (activeRouteVerificationAttempt >= ACTIVE_ROUTE_VERIFY_DELAYS_MS.length) {
            return;
        }
        long delay = ACTIVE_ROUTE_VERIFY_DELAYS_MS[activeRouteVerificationAttempt++];
        activeRouteCheckScheduled = true;
        handler.postDelayed(activeRouteVerificationRunnable, delay);
    }

    private void cancelActiveDeviceVerification(String state) {
        handler.removeCallbacks(activeRouteVerificationRunnable);
        activeRouteCheckScheduled = false;
        activeRouteVerificationAttempt = 0;
        activeRouteState = state;
    }

    @SuppressLint("MissingPermission")
    private void verifyActiveDevice() {
        if (!started || a2dpProxy == null || targetDevice == null
                || a2dpState != BluetoothProfile.STATE_CONNECTED) {
            return;
        }
        boolean retry = false;
        try {
            Method method = a2dpProxy.getClass().getMethod("getActiveDevice");
            Object value = method.invoke(a2dpProxy);
            if (value instanceof BluetoothDevice && isTarget((BluetoothDevice) value)) {
                activeRouteState = "WI-C100 已激活";
            } else {
                activeRouteState = "Profile 已连接，WI-C100 未确认激活";
                retry = true;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if ("切换请求已受理，等待确认".equals(activeRouteState)) {
                activeRouteState = "切换已请求，系统不提供路由查询";
            } else if ("未验证".equals(activeRouteState)) {
                activeRouteState = "Profile 已连接，活动路由无法确认";
            }
        }
        publish();
        if (retry) {
            scheduleActiveDeviceVerification(false);
        }
    }

    @SuppressLint("MissingPermission")
    private boolean isCandidate(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        if (targetDevice != null && safeAddress(targetDevice).equals(safeAddress(device))) {
            return true;
        }
        return nameMatchesTarget(safeName(device));
    }

    private boolean isTarget(BluetoothDevice device) {
        return device != null && targetDevice != null
                && safeAddress(targetDevice).equals(safeAddress(device));
    }

    @SuppressLint("MissingPermission")
    private void adoptTarget(BluetoothDevice device) {
        targetDevice = device;
        String name = safeName(device);
        if (!TextUtils.isEmpty(name)) {
            targetName = name;
        } else if (TextUtils.isEmpty(targetName)) {
            targetName = TARGET_NAME_TOKEN;
        }
        preferences.edit()
                .putString(PREF_TARGET_ADDRESS, safeAddress(device))
                .putString(PREF_TARGET_NAME, targetName)
                .apply();
    }

    private void restoreTarget() {
        if (adapter == null) {
            return;
        }
        String address = preferences.getString(PREF_TARGET_ADDRESS, "");
        targetName = preferences.getString(PREF_TARGET_NAME, "");
        if (TextUtils.isEmpty(address)) {
            return;
        }
        try {
            targetDevice = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException exception) {
            preferences.edit().remove(PREF_TARGET_ADDRESS).apply();
        }
    }

    @SuppressLint("MissingPermission")
    private String targetLabel() {
        String name = TextUtils.isEmpty(targetName) ? TARGET_NAME_TOKEN : targetName;
        return targetDevice == null ? name : name + " / " + safeAddress(targetDevice);
    }

    private boolean nameMatchesTarget(String name) {
        return !TextUtils.isEmpty(name)
                && name.toUpperCase(Locale.US).contains(TARGET_NAME_TOKEN);
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            return device == null ? "" : device.getName();
        } catch (SecurityException exception) {
            return "";
        }
    }

    private static String safeAddress(BluetoothDevice device) {
        try {
            return device == null ? "" : device.getAddress();
        } catch (SecurityException exception) {
            return "";
        }
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void closeProfileProxy(int profile, BluetoothProfile proxy) {
        if (adapter == null || proxy == null) {
            return;
        }
        try {
            adapter.closeProfileProxy(profile, proxy);
        } catch (RuntimeException ignored) {
            // The Bluetooth service may already be gone during adapter shutdown.
        }
    }

    private void emitEvent(String message) {
        listener.onEvent(message);
        publish();
    }

    private void publish() {
        listener.onSnapshot(getSnapshot());
    }

    private static String profileStateLabel(int state) {
        switch (state) {
            case BluetoothProfile.STATE_CONNECTED:
                return "已连接";
            case BluetoothProfile.STATE_CONNECTING:
                return "连接中";
            case BluetoothProfile.STATE_DISCONNECTING:
                return "断开中";
            default:
                return "已断开";
        }
    }

    private static String safeThrowable(Throwable throwable) {
        if (throwable == null) {
            return "无详情";
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (TextUtils.isEmpty(message) ? "" : ": " + message);
    }

    private static final class DirectConnectResult {
        final boolean supported;
        final boolean accepted;
        final String message;

        DirectConnectResult(boolean supported, boolean accepted, String message) {
            this.supported = supported;
            this.accepted = accepted;
            this.message = message;
        }
    }
}
