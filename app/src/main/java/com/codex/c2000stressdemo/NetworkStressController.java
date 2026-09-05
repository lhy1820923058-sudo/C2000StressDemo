package com.codex.c2000stressdemo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class NetworkStressController {
    interface Listener {
        void onNetworkEvent(String message);
    }

    private final ConnectivityManager connectivityManager;
    private final StressMetrics metrics;
    private final Listener listener;
    private final AtomicBoolean singleRunning = new AtomicBoolean();
    private final AtomicBoolean multiRunning = new AtomicBoolean();
    private final ThreadPoolExecutor frameUploadPool;
    private ThreadPoolExecutor singlePool;
    private ThreadPoolExecutor multiPool;

    NetworkStressController(Context context, StressMetrics metrics, Listener listener) {
        this.connectivityManager = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.metrics = metrics;
        this.listener = listener;
        this.frameUploadPool = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8),
                new NamedThreadFactory("FrameUpload"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized boolean startSingle(String endpoint, int payloadKb) {
        URL url = parseHttpUrl(endpoint);
        if (url == null) {
            listener.onNetworkEvent("WiFi 传输未启动：HTTP 地址无效。");
            return false;
        }
        stopSingle();
        byte[] payload = createPayload(payloadKb);
        singleRunning.set(true);
        singlePool = createPool(1, "WifiSingle");
        singlePool.execute(() -> uploadLoop(singleRunning, url, payload, "synthetic-single", 40L));
        listener.onNetworkEvent("单线程 WiFi 传输已启动：" + payload.length / 1024 + " KB/请求。");
        return true;
    }

    synchronized boolean startMulti(String endpoint, int payloadKb, int requestedThreads) {
        URL url = parseHttpUrl(endpoint);
        if (url == null) {
            listener.onNetworkEvent("WiFi 多线程未启动：HTTP 地址无效。");
            return false;
        }
        stopMulti();
        int threads = Math.max(2, Math.min(12, requestedThreads));
        byte[] payload = createPayload(payloadKb);
        multiRunning.set(true);
        multiPool = createPool(threads, "WifiMulti");
        for (int index = 0; index < threads; index++) {
            multiPool.execute(() -> uploadLoop(multiRunning, url, payload, "synthetic-multi", 10L));
        }
        listener.onNetworkEvent("WiFi 多线程传输已启动：" + threads + " 线程，"
                + payload.length / 1024 + " KB/请求。");
        return true;
    }

    void submitFrame(String endpoint, byte[] jpeg) {
        URL url = parseHttpUrl(endpoint);
        if (url == null || jpeg == null || jpeg.length == 0) {
            metrics.uploadQueueDrops.incrementAndGet();
            return;
        }
        try {
            frameUploadPool.execute(() -> post(url, jpeg, "camera-jpeg"));
        } catch (RejectedExecutionException exception) {
            metrics.uploadQueueDrops.incrementAndGet();
        }
    }

    private void uploadLoop(AtomicBoolean running, URL url, byte[] payload, String source, long pauseMs) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            post(url, payload, source);
            if (pauseMs > 0) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void post(URL url, byte[] payload, String source) {
        long began = System.nanoTime();
        metrics.uploadAttempts.incrementAndGet();
        HttpURLConnection connection = null;
        try {
            Network wifiNetwork = requireWifiNetwork();
            connection = (HttpURLConnection) wifiNetwork.openConnection(url);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1_500);
            connection.setReadTimeout(3_000);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            connection.setRequestProperty("X-C2000-Source", source);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
                output.flush();
            }
            metrics.uploadBytes.addAndGet(payload.length);
            int status = connection.getResponseCode();
            drain(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 200 && status < 300) {
                metrics.uploadSuccess.incrementAndGet();
            } else {
                recordFailure("HTTP " + status);
            }
        } catch (Exception exception) {
            recordFailure(exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        } finally {
            metrics.uploadLatencyMs.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Network requireWifiNetwork() throws IOException {
        if (connectivityManager == null) {
            throw new IOException("ConnectivityManager 不可用");
        }
        Network network = connectivityManager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null
                : connectivityManager.getNetworkCapabilities(network);
        if (network == null || capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            throw new IOException("当前未连接 WiFi");
        }
        return network;
    }

    private void recordFailure(String detail) {
        long failures = metrics.uploadFailures.incrementAndGet();
        if (failures == 1 || failures % 50 == 0) {
            listener.onNetworkEvent("WiFi 失败 #" + failures + "：" + detail);
        }
    }

    private static void drain(InputStream input) {
        if (input == null) {
            return;
        }
        byte[] buffer = new byte[512];
        try (InputStream stream = input) {
            while (stream.read(buffer) >= 0) {
                // Consume the response so the connection can release resources promptly.
            }
        } catch (IOException ignored) {
            // Response content is irrelevant to the stress test.
        }
    }

    synchronized void stopSingle() {
        singleRunning.set(false);
        if (singlePool != null) {
            singlePool.shutdownNow();
            singlePool = null;
        }
    }

    synchronized void stopMulti() {
        multiRunning.set(false);
        if (multiPool != null) {
            multiPool.shutdownNow();
            multiPool = null;
        }
    }

    synchronized void stopAll() {
        stopSingle();
        stopMulti();
        frameUploadPool.getQueue().clear();
    }

    synchronized void shutdown() {
        stopAll();
        frameUploadPool.shutdownNow();
    }

    boolean isSingleRunning() {
        return singleRunning.get();
    }

    boolean isMultiRunning() {
        return multiRunning.get();
    }

    synchronized int getActiveThreads() {
        int active = frameUploadPool.getActiveCount();
        active += singlePool == null ? 0 : singlePool.getActiveCount();
        active += multiPool == null ? 0 : multiPool.getActiveCount();
        return active;
    }

    synchronized int getQueueDepth() {
        int queued = frameUploadPool.getQueue().size();
        queued += singlePool == null ? 0 : singlePool.getQueue().size();
        queued += multiPool == null ? 0 : multiPool.getQueue().size();
        return queued;
    }

    private static ThreadPoolExecutor createPool(int threads, String name) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(threads, 4)),
                new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static URL parseHttpUrl(String raw) {
        try {
            URL url = new URL(raw == null ? "" : raw.trim());
            String protocol = url.getProtocol().toLowerCase(Locale.US);
            return ("http".equals(protocol) || "https".equals(protocol)) ? url : null;
        } catch (MalformedURLException exception) {
            return null;
        }
    }

    private static byte[] createPayload(int requestedKb) {
        int size = Math.max(4, Math.min(1_024, requestedKb)) * 1_024;
        byte[] payload = new byte[size];
        int state = 0x13579BDF;
        for (int index = 0; index < payload.length; index++) {
            state = state * 1103515245 + 12345;
            payload[index] = (byte) (state >>> 16);
        }
        return payload;
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "无详情" : message;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            return new Thread(runnable, prefix + "-" + next.getAndIncrement());
        }
    }
}
