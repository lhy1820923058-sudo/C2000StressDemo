package com.codex.c2000stressdemo;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.TextureView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity implements CameraController.Listener,
        NetworkStressController.Listener, A2dpRecoveryService.Listener {
    private static final int REQUEST_PERMISSIONS = 5201;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> eventLines = new ArrayDeque<>();
    private final SimpleDateFormat timeFormatter = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final ScheduledExecutorService captureScheduler = Executors.newSingleThreadScheduledExecutor(
            new NamedThreadFactory("CaptureTicker"));
    private final StressMetrics metrics = new StressMetrics();

    private CameraController cameraController;
    private NetworkStressController networkController;
    private FrameProcessor frameProcessor;
    private ScheduledFuture<?> captureFuture;
    private volatile boolean frameTransferEnabled;
    private volatile boolean cameraProcessingEnabled;
    private boolean cameraRequested;
    private String frameEndpoint = "";
    private Runnable pendingPermissionAction;
    private long metricsStartedAt = SystemClock.elapsedRealtime();
    private long previousCpuMillis;
    private long previousWallMillis;
    private int previousGattCount = -1;
    private int bleDisconnectEvents;
    private boolean a2dpServiceBound;
    private boolean a2dpBindRequested;
    private boolean updatingA2dpSwitch;
    private A2dpRecoveryService a2dpRecoveryService;
    private A2dpReconnectController.Snapshot a2dpSnapshot;

    private Button cameraButton;
    private Button wifiTransferButton;
    private Button frameTransferButton;
    private Button wifiThreadsButton;
    private Button cameraThreadsButton;
    private Button a2dpReconnectButton;
    private Button bluetoothSettingsButton;
    private EditText endpointInput;
    private EditText wifiThreadsInput;
    private EditText cameraThreadsInput;
    private EditText payloadKbInput;
    private TextView cameraStateView;
    private TextView a2dpStateView;
    private TextView bleStateView;
    private TextView resourceStateView;
    private TextView statsView;
    private TextView logView;
    private Switch a2dpAutoReconnectSwitch;

    private final ServiceConnection a2dpServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            A2dpRecoveryService.LocalBinder localBinder =
                    (A2dpRecoveryService.LocalBinder) binder;
            a2dpRecoveryService = localBinder.getService();
            a2dpServiceBound = true;
            a2dpBindRequested = true;
            a2dpRecoveryService.addListener(MainActivity.this);
            a2dpRecoveryService.refresh();
            updateA2dpUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            a2dpRecoveryService = null;
            a2dpServiceBound = false;
            a2dpBindRequested = false;
            a2dpStateView.setText("A2DP：恢复服务已断开");
            a2dpAutoReconnectSwitch.setEnabled(false);
            a2dpReconnectButton.setEnabled(false);
        }
    };

    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            uiHandler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        cameraController = new CameraController(
                this,
                (TextureView) findViewById(R.id.texture_preview),
                metrics,
                this);
        networkController = new NetworkStressController(this, metrics, this);
        frameProcessor = new FrameProcessor(metrics);
        wireControls();
        resetButtonStates();
        appendEvent("压力测试工具已就绪。BLE 诊断程序应在后台保持连接。");
        requestInitialPermissions();
        if (hasBluetoothConnectPermission()) {
            ensureA2dpRecoveryService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        previousCpuMillis = android.os.Process.getElapsedCpuTime();
        previousWallMillis = SystemClock.elapsedRealtime();
        uiHandler.removeCallbacks(statusRunnable);
        uiHandler.post(statusRunnable);
        if (hasBluetoothConnectPermission()) {
            ensureA2dpRecoveryService();
        }
    }

    @Override
    protected void onStop() {
        uiHandler.removeCallbacks(statusRunnable);
        if (!isChangingConfigurations()) {
            stopAll(false);
        }
        unbindA2dpRecoveryService();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        cancelCaptureTicker();
        captureScheduler.shutdownNow();
        frameProcessor.stop();
        networkController.shutdown();
        cameraController.shutdown();
        unbindA2dpRecoveryService();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) {
            return;
        }
        boolean cameraGranted = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean bluetoothGranted = hasBluetoothConnectPermission();
        appendEvent(cameraGranted ? "摄像头权限已授予。" : "未授予摄像头权限，Camera2 不可用。");
        appendEvent(bluetoothGranted ? "蓝牙连接权限已授予。" : "未授予蓝牙连接权限，A2DP 监控不可用。");
        Runnable action = pendingPermissionAction;
        pendingPermissionAction = null;
        if (cameraGranted && action != null) {
            action.run();
        }
        if (bluetoothGranted) {
            ensureA2dpRecoveryService();
        }
        updateStatus();
    }

    private void bindViews() {
        cameraButton = findViewById(R.id.btn_camera);
        wifiTransferButton = findViewById(R.id.btn_wifi_transfer);
        frameTransferButton = findViewById(R.id.btn_frame_transfer);
        wifiThreadsButton = findViewById(R.id.btn_wifi_threads);
        cameraThreadsButton = findViewById(R.id.btn_camera_threads);
        a2dpReconnectButton = findViewById(R.id.btn_a2dp_reconnect);
        bluetoothSettingsButton = findViewById(R.id.btn_bluetooth_settings);
        endpointInput = findViewById(R.id.et_endpoint);
        wifiThreadsInput = findViewById(R.id.et_wifi_threads);
        cameraThreadsInput = findViewById(R.id.et_camera_threads);
        payloadKbInput = findViewById(R.id.et_payload_kb);
        cameraStateView = findViewById(R.id.tv_camera_state);
        a2dpStateView = findViewById(R.id.tv_a2dp_state);
        bleStateView = findViewById(R.id.tv_ble_state);
        resourceStateView = findViewById(R.id.tv_resource_state);
        statsView = findViewById(R.id.tv_stats);
        logView = findViewById(R.id.tv_log);
        a2dpAutoReconnectSwitch = findViewById(R.id.switch_a2dp_reconnect);
        a2dpAutoReconnectSwitch.setEnabled(false);
        a2dpReconnectButton.setEnabled(false);
    }

    private void wireControls() {
        cameraButton.setOnClickListener(view -> ensureCameraPermission(this::toggleCamera));
        wifiTransferButton.setOnClickListener(view -> toggleWifiTransfer());
        frameTransferButton.setOnClickListener(view -> ensureCameraPermission(this::toggleFrameTransfer));
        wifiThreadsButton.setOnClickListener(view -> toggleWifiThreads());
        cameraThreadsButton.setOnClickListener(view -> ensureCameraPermission(this::toggleCameraProcessing));
        a2dpAutoReconnectSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingA2dpSwitch && a2dpRecoveryService != null) {
                a2dpRecoveryService.setAutoReconnect(checked);
            }
        });
        a2dpReconnectButton.setOnClickListener(view -> {
            if (a2dpRecoveryService == null) {
                toast("A2DP 恢复服务尚未连接。");
            } else {
                a2dpRecoveryService.requestReconnectNow();
            }
        });
        bluetoothSettingsButton.setOnClickListener(view ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        findViewById(R.id.btn_stop_all).setOnClickListener(view -> stopAll(true));
        findViewById(R.id.btn_reset_stats).setOnClickListener(view -> resetStats());
    }

    private void toggleCamera() {
        if (cameraRequested) {
            stopCameraLoads();
            appendEvent("摄像头及依赖的截帧压力已停止。");
        } else {
            startCameraIfNeeded();
        }
        refreshButtonStates();
    }

    private void toggleWifiTransfer() {
        if (networkController.isSingleRunning()) {
            networkController.stopSingle();
            appendEvent("单线程 WiFi 传输已停止。");
        } else {
            String endpoint = endpoint();
            if (!isHttpEndpoint(endpoint)) {
                toast("请输入有效的 HTTP/HTTPS 接收地址。");
                return;
            }
            networkController.startSingle(endpoint, payloadKb());
        }
        refreshButtonStates();
    }

    private void toggleFrameTransfer() {
        if (frameTransferEnabled) {
            frameTransferEnabled = false;
            appendEvent("500ms 摄像截帧传输已停止。");
        } else {
            String endpoint = endpoint();
            if (!isHttpEndpoint(endpoint)) {
                toast("请输入有效的 HTTP/HTTPS 接收地址。");
                return;
            }
            frameEndpoint = endpoint;
            frameTransferEnabled = true;
            startCameraIfNeeded();
            appendEvent("已启用每 500ms JPEG 截帧并通过 WiFi POST。");
        }
        updateCaptureTicker();
        refreshButtonStates();
    }

    private void toggleWifiThreads() {
        if (networkController.isMultiRunning()) {
            networkController.stopMulti();
            appendEvent("WiFi 多线程传输已停止。");
        } else {
            String endpoint = endpoint();
            if (!isHttpEndpoint(endpoint)) {
                toast("请输入有效的 HTTP/HTTPS 接收地址。");
                return;
            }
            networkController.startMulti(endpoint, payloadKb(), wifiThreads());
        }
        refreshButtonStates();
    }

    private void toggleCameraProcessing() {
        if (cameraProcessingEnabled) {
            cameraProcessingEnabled = false;
            frameProcessor.stop();
            appendEvent("摄像多线程计算已停止。");
        } else {
            int threads = cameraThreads();
            frameProcessor.start(threads);
            cameraProcessingEnabled = true;
            startCameraIfNeeded();
            appendEvent("摄像 JPEG 多线程计算已启动：" + threads + " 线程。");
        }
        updateCaptureTicker();
        refreshButtonStates();
    }

    private void startCameraIfNeeded() {
        if (cameraRequested) {
            return;
        }
        cameraRequested = true;
        cameraController.start();
        appendEvent("正在打开 Camera2 后置摄像头。");
    }

    private void stopCameraLoads() {
        frameTransferEnabled = false;
        cameraProcessingEnabled = false;
        frameProcessor.stop();
        cancelCaptureTicker();
        cameraRequested = false;
        cameraController.stop();
    }

    private void stopAll(boolean userAction) {
        stopCameraLoads();
        networkController.stopAll();
        refreshButtonStates();
        if (userAction) {
            appendEvent("全部压力负载已停止。");
        }
    }

    private void resetStats() {
        metrics.reset();
        metricsStartedAt = SystemClock.elapsedRealtime();
        bleDisconnectEvents = 0;
        previousGattCount = -1;
        if (a2dpRecoveryService != null) {
            a2dpRecoveryService.resetCounters();
        }
        appendEvent("统计计数已重置。");
        updateStatus();
    }

    private synchronized void updateCaptureTicker() {
        boolean needed = frameTransferEnabled || cameraProcessingEnabled;
        if (needed && (captureFuture == null || captureFuture.isCancelled())) {
            captureFuture = captureScheduler.scheduleAtFixedRate(
                    cameraController::captureFrame,
                    0L,
                    500L,
                    TimeUnit.MILLISECONDS);
        } else if (!needed) {
            cancelCaptureTicker();
        }
    }

    private synchronized void cancelCaptureTicker() {
        if (captureFuture != null) {
            captureFuture.cancel(false);
            captureFuture = null;
        }
    }

    @Override
    public void onCameraState(String state, boolean ready) {
        uiHandler.post(() -> {
            cameraStateView.setText("摄像头：" + state);
            if (!ready && !cameraRequested) {
                refreshButtonStates();
            }
        });
    }

    @Override
    public void onFrame(byte[] jpeg, long captureLatencyMs) {
        if (frameTransferEnabled) {
            networkController.submitFrame(frameEndpoint, jpeg);
        }
        if (cameraProcessingEnabled) {
            frameProcessor.submit(jpeg);
        }
    }

    @Override
    public void onCameraError(String message) {
        appendEvent(message);
    }

    @Override
    public void onNetworkEvent(String message) {
        appendEvent(message);
    }

    private void updateStatus() {
        updateBleState();
        updateA2dpUi();
        double cpuPercent = sampleCpuPercent();
        long usedMemoryMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024L * 1024L);
        String wifi = wifiSummary();
        int thermal = thermalStatus();
        resourceStateView.setText(String.format(Locale.CHINA,
                "进程 CPU：%.1f%% ｜ Java 内存：%d MB ｜ WiFi：%s ｜ Thermal：%d",
                cpuPercent,
                usedMemoryMb,
                wifi,
                thermal));

        long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - metricsStartedAt);
        long attempts = metrics.uploadAttempts.get();
        long processTasks = metrics.processTasks.get();
        double throughputMbps = metrics.uploadBytes.get() * 8_000.0 / elapsedMs / 1_000_000.0;
        double averageUploadMs = attempts == 0 ? 0.0 : metrics.uploadLatencyMs.get() / (double) attempts;
        double averageProcessMs = processTasks == 0 ? 0.0
                : metrics.processNanos.get() / 1_000_000.0 / processTasks;
        String modes = "Camera=" + (cameraRequested ? "ON" : "OFF")
                + " FrameTx=" + (frameTransferEnabled ? "ON" : "OFF")
                + " WiFi1=" + (networkController.isSingleRunning() ? "ON" : "OFF")
                + " WiFiN=" + (networkController.isMultiRunning() ? "ON" : "OFF")
                + " CamN=" + (cameraProcessingEnabled ? "ON" : "OFF");
        String text = modes
                + "\nJPEG：请求 " + metrics.captureRequests.get()
                + " ｜ 收到 " + metrics.framesReceived.get()
                + " ｜ 跳过 " + metrics.captureSkipped.get()
                + " ｜ stall " + metrics.captureStalls.get()
                + "\n当前帧：" + formatBytes(metrics.lastFrameBytes.get())
                + " ｜ Capture " + metrics.lastCaptureLatencyMs.get() + " ms"
                + " ｜ 尺寸 " + cameraController.getCaptureSizeLabel()
                + "\nHTTP：尝试 " + attempts
                + " ｜ 成功 " + metrics.uploadSuccess.get()
                + " ｜ 失败 " + metrics.uploadFailures.get()
                + " ｜ 丢弃 " + metrics.uploadQueueDrops.get()
                + String.format(Locale.CHINA, "\nWiFi：%.2f Mbps ｜ 平均 %.1f ms ｜ 活跃 %d ｜ 队列 %d",
                throughputMbps,
                averageUploadMs,
                networkController.getActiveThreads(),
                networkController.getQueueDepth())
                + "\n摄像计算：帧 " + metrics.processFrames.get()
                + " ｜ 任务 " + processTasks
                + " ｜ 丢弃 " + metrics.processDrops.get()
                + String.format(Locale.CHINA, " ｜ 平均 %.1f ms", averageProcessMs)
                + "\nBLE 连接下降事件：" + bleDisconnectEvents
                + "\n" + (a2dpSnapshot == null
                ? "A2DP：等待恢复服务"
                : a2dpSnapshot.countersText());
        statsView.setText(text);
    }

    @Override
    public void onA2dpSnapshot(A2dpReconnectController.Snapshot snapshot) {
        uiHandler.post(() -> {
            a2dpSnapshot = snapshot;
            updateA2dpUi();
        });
    }

    @Override
    public void onA2dpEvent(String message) {
        appendEvent(message);
    }

    private void updateA2dpUi() {
        if (a2dpStateView == null) {
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            a2dpStateView.setText("A2DP：未授予 BLUETOOTH_CONNECT 权限");
            a2dpAutoReconnectSwitch.setEnabled(false);
            a2dpReconnectButton.setEnabled(false);
            return;
        }
        if (a2dpSnapshot == null) {
            a2dpStateView.setText("A2DP：恢复服务连接中");
            a2dpAutoReconnectSwitch.setEnabled(a2dpServiceBound);
            a2dpReconnectButton.setEnabled(false);
            return;
        }
        String text = a2dpSnapshot.statusText();
        if (!TextUtils.isEmpty(a2dpSnapshot.lastError)) {
            text += "\n最近错误：" + a2dpSnapshot.lastError;
        }
        a2dpStateView.setText(text);
        updatingA2dpSwitch = true;
        a2dpAutoReconnectSwitch.setChecked(a2dpSnapshot.autoReconnect);
        updatingA2dpSwitch = false;
        a2dpAutoReconnectSwitch.setEnabled(a2dpServiceBound);
        a2dpReconnectButton.setEnabled(a2dpServiceBound
                && !a2dpSnapshot.recoveryInProgress);
    }

    private void ensureA2dpRecoveryService() {
        if (!hasBluetoothConnectPermission()) {
            return;
        }
        try {
            A2dpRecoveryService.start(this);
            if (!a2dpBindRequested) {
                Intent intent = new Intent(this, A2dpRecoveryService.class);
                a2dpBindRequested = bindService(intent, a2dpServiceConnection,
                        Context.BIND_AUTO_CREATE);
            }
        } catch (RuntimeException exception) {
            appendEvent("启动 A2DP 恢复服务失败：" + exception.getClass().getSimpleName());
        }
    }

    private void unbindA2dpRecoveryService() {
        if (!a2dpBindRequested) {
            return;
        }
        if (a2dpRecoveryService != null) {
            a2dpRecoveryService.removeListener(this);
        }
        try {
            unbindService(a2dpServiceConnection);
        } catch (IllegalArgumentException ignored) {
            // Binding may have been dropped when the process was reclaimed.
        }
        a2dpRecoveryService = null;
        a2dpServiceBound = false;
        a2dpBindRequested = false;
    }

    private void updateBleState() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            bleStateView.setText("BLE：本机无蓝牙适配器");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            bleStateView.setText("BLE：未授予连接状态权限");
            return;
        }
        try {
            boolean enabled = adapter.isEnabled();
            List<BluetoothDevice> connected = manager.getConnectedDevices(BluetoothProfile.GATT);
            int count = connected == null ? 0 : connected.size();
            if (previousGattCount > count) {
                bleDisconnectEvents += previousGattCount - count;
                appendEvent("检测到系统 GATT 连接数量从 " + previousGattCount + " 降至 " + count + "。");
            }
            previousGattCount = count;
            bleStateView.setText("蓝牙：" + (enabled ? "ON" : "OFF")
                    + " ｜ 系统 GATT 已连接：" + count
                    + " ｜ 连接下降事件：" + bleDisconnectEvents);
        } catch (SecurityException exception) {
            bleStateView.setText("BLE：系统拒绝读取连接状态");
        }
    }

    private double sampleCpuPercent() {
        long wall = SystemClock.elapsedRealtime();
        long cpu = android.os.Process.getElapsedCpuTime();
        long wallDelta = wall - previousWallMillis;
        long cpuDelta = cpu - previousCpuMillis;
        previousWallMillis = wall;
        previousCpuMillis = cpu;
        return wallDelta <= 0 ? 0.0 : Math.max(0.0, cpuDelta * 100.0 / wallDelta);
    }

    private String wifiSummary() {
        ConnectivityManager connectivity = (ConnectivityManager) getSystemService(
                Context.CONNECTIVITY_SERVICE);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = network == null || connectivity == null ? null
                : connectivity.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "未连接";
        }
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
            int frequency = info == null ? -1 : info.getFrequency();
            if (frequency >= 2_400 && frequency < 2_500) {
                return frequency + " MHz / 2.4G";
            }
            if (frequency >= 4_900 && frequency < 5_900) {
                return frequency + " MHz / 5G";
            }
            if (frequency >= 5_900) {
                return frequency + " MHz / 6G";
            }
        } catch (SecurityException ignored) {
            // Connected transport is still useful when frequency is restricted.
        }
        return "已连接 / 频段未知";
    }

    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return -1;
        }
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return power == null ? -1 : power.getCurrentThermalStatus();
    }

    private void requestInitialPermissions() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureCameraPermission(Runnable action) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingPermissionAction = action;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSIONS);
    }

    private void resetButtonStates() {
        setButtonState(cameraButton, false, "关闭摄像头", getString(R.string.open_camera));
        setButtonState(wifiTransferButton, false, "停止 WiFi 传输", getString(R.string.wifi_transfer));
        setButtonState(frameTransferButton, false, "停止 500ms 截帧传输", getString(R.string.frame_transfer));
        setButtonState(wifiThreadsButton, false, "停止 WiFi 多线程", getString(R.string.wifi_multithread));
        setButtonState(cameraThreadsButton, false, "停止摄像多线程", getString(R.string.camera_multithread));
    }

    private void refreshButtonStates() {
        setButtonState(cameraButton, cameraRequested, "关闭摄像头", getString(R.string.open_camera));
        setButtonState(wifiTransferButton, networkController.isSingleRunning(),
                "停止 WiFi 传输", getString(R.string.wifi_transfer));
        setButtonState(frameTransferButton, frameTransferEnabled,
                "停止 500ms 截帧传输", getString(R.string.frame_transfer));
        setButtonState(wifiThreadsButton, networkController.isMultiRunning(),
                "停止 WiFi 多线程", getString(R.string.wifi_multithread));
        setButtonState(cameraThreadsButton, cameraProcessingEnabled,
                "停止摄像多线程", getString(R.string.camera_multithread));
    }

    private void setButtonState(Button button, boolean active, String activeText, String idleText) {
        button.setText(active ? activeText : idleText);
        button.setBackgroundResource(active ? R.drawable.button_primary : R.drawable.button_outline);
        button.setTextColor(getColor(active ? android.R.color.white : R.color.primary));
    }

    private void appendEvent(String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            uiHandler.post(() -> appendEvent(message));
            return;
        }
        String line = timeFormatter.format(new Date()) + "  " + message;
        eventLines.addLast(line);
        while (eventLines.size() > 70) {
            eventLines.removeFirst();
        }
        logView.setText(TextUtils.join("\n", eventLines));
    }

    private String endpoint() {
        return endpointInput.getText() == null ? "" : endpointInput.getText().toString().trim();
    }

    private int wifiThreads() {
        return parseInt(wifiThreadsInput, 4, 2, 12);
    }

    private int cameraThreads() {
        return parseInt(cameraThreadsInput, 4, 1, 8);
    }

    private int payloadKb() {
        return parseInt(payloadKbInput, 64, 4, 1_024);
    }

    private static int parseInt(EditText input, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            return Math.max(min, Math.min(max, value));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private static boolean isHttpEndpoint(String raw) {
        try {
            URL url = new URL(raw);
            return "http".equalsIgnoreCase(url.getProtocol())
                    || "https".equalsIgnoreCase(url.getProtocol());
        } catch (MalformedURLException exception) {
            return false;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(Locale.CHINA, "%.2f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String name;

        NamedThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, name);
        }
    }
}
