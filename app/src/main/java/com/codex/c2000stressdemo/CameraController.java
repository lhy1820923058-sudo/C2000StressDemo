package com.codex.c2000stressdemo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

final class CameraController {
    interface Listener {
        void onCameraState(String state, boolean ready);

        void onFrame(byte[] jpeg, long captureLatencyMs);

        void onCameraError(String message);
    }

    private static final long CAPTURE_WATCHDOG_MS = 2_000L;
    private static final int MAX_CAPTURE_PIXELS = 1280 * 720;

    private final Context context;
    private final TextureView textureView;
    private final StressMetrics metrics;
    private final Listener listener;
    private final AtomicBoolean captureInFlight = new AtomicBoolean();
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size previewSize;
    private Size jpegSize;
    private int sensorOrientation;
    private boolean requestedOpen;
    private boolean opening;
    private volatile boolean sessionReady;
    private long captureToken;
    private long captureRequestedAt;

    CameraController(Context context, TextureView textureView, StressMetrics metrics, Listener listener) {
        this.context = context.getApplicationContext();
        this.textureView = textureView;
        this.metrics = metrics;
        this.listener = listener;
        this.textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if (requestedOpen) {
                    start();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // The preview stream remains fixed-size; TextureView scales it safely.
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                stop();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // Preview frames are rendered by the system compositor.
            }
        });
    }

    synchronized void start() {
        requestedOpen = true;
        ensureThread();
        if (!textureView.isAvailable()) {
            listener.onCameraState("等待预览 Surface", false);
            return;
        }
        cameraHandler.post(this::openInternal);
    }

    synchronized void stop() {
        requestedOpen = false;
        Handler handler = cameraHandler;
        if (handler != null) {
            handler.post(this::closeInternal);
        }
    }

    synchronized void shutdown() {
        requestedOpen = false;
        Handler handler = cameraHandler;
        HandlerThread thread = cameraThread;
        if (handler != null) {
            handler.post(this::closeInternal);
        }
        if (thread != null) {
            thread.quitSafely();
        }
        cameraHandler = null;
        cameraThread = null;
    }

    void captureFrame() {
        Handler handler;
        synchronized (this) {
            handler = cameraHandler;
        }
        if (handler == null) {
            metrics.captureSkipped.incrementAndGet();
            return;
        }
        handler.post(this::captureInternal);
    }

    boolean isReady() {
        return sessionReady;
    }

    String getCaptureSizeLabel() {
        Size size = jpegSize;
        return size == null ? "--" : size.getWidth() + "x" + size.getHeight();
    }

    private synchronized void ensureThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("Camera2Thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    @SuppressLint("MissingPermission")
    private void openInternal() {
        if (!requestedOpen || opening || cameraDevice != null || !textureView.isAvailable()) {
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listener.onCameraError("缺少 CAMERA 权限");
            return;
        }
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) {
            listener.onCameraError("CameraManager 不可用");
            return;
        }
        try {
            String cameraId = chooseCamera(manager);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 0 : orientation;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "无输出配置");
            }
            jpegSize = chooseSize(map.getOutputSizes(ImageFormat.JPEG));
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), jpegSize);
            closeReaderAndSurface();
            imageReader = ImageReader.newInstance(
                    jpegSize.getWidth(),
                    jpegSize.getHeight(),
                    ImageFormat.JPEG,
                    2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, cameraHandler);
            opening = true;
            listener.onCameraState("打开中", false);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (Exception exception) {
            opening = false;
            listener.onCameraError("打开摄像头失败：" + safeMessage(exception));
            closeInternal();
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            opening = false;
            if (!requestedOpen) {
                camera.close();
                return;
            }
            cameraDevice = camera;
            createSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            listener.onCameraError("摄像头已断开");
            closeInternal();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            listener.onCameraError("Camera2 错误码=" + error);
            closeInternal();
        }
    };

    private void createSession() {
        CameraDevice camera = cameraDevice;
        ImageReader reader = imageReader;
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (camera == null || reader == null || texture == null || previewSize == null) {
            listener.onCameraError("创建预览会话所需资源不完整");
            closeInternal();
            return;
        }
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        previewSurface = new Surface(texture);
        try {
            camera.createCaptureSession(
                    Arrays.asList(previewSurface, reader.getSurface()),
                    sessionStateCallback,
                    cameraHandler);
        } catch (CameraAccessException exception) {
            listener.onCameraError("创建会话失败：" + safeMessage(exception));
            closeInternal();
        }
    }

    private final CameraCaptureSession.StateCallback sessionStateCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if (!requestedOpen || cameraDevice == null) {
                        session.close();
                        return;
                    }
                    captureSession = session;
                    try {
                        CaptureRequest.Builder preview = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        preview.addTarget(previewSurface);
                        preview.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                        preview.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        session.setRepeatingRequest(preview.build(), null, cameraHandler);
                        sessionReady = true;
                        listener.onCameraState("预览中 " + getCaptureSizeLabel(), true);
                    } catch (CameraAccessException exception) {
                        listener.onCameraError("启动预览失败：" + safeMessage(exception));
                        closeInternal();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onCameraError("Camera2 会话配置失败");
                    closeInternal();
                }
            };

    private void captureInternal() {
        if (!sessionReady || captureSession == null || cameraDevice == null || imageReader == null) {
            metrics.captureSkipped.incrementAndGet();
            return;
        }
        if (!captureInFlight.compareAndSet(false, true)) {
            metrics.captureSkipped.incrementAndGet();
            return;
        }
        metrics.captureRequests.incrementAndGet();
        captureRequestedAt = SystemClock.elapsedRealtime();
        long token = ++captureToken;
        try {
            CaptureRequest.Builder capture = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            capture.addTarget(imageReader.getSurface());
            capture.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            capture.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            capture.set(CaptureRequest.JPEG_QUALITY, (byte) 82);
            capture.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation);
            captureSession.capture(capture.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request,
                        CaptureFailure failure) {
                    captureInFlight.set(false);
                    metrics.captureSkipped.incrementAndGet();
                    listener.onCameraError("JPEG Capture 失败，reason=" + failure.getReason());
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                        TotalCaptureResult result) {
                    // ImageReader owns completion; it clears captureInFlight after copying bytes.
                }
            }, cameraHandler);
            cameraHandler.postDelayed(() -> {
                if (captureToken == token && captureInFlight.compareAndSet(true, false)) {
                    metrics.captureStalls.incrementAndGet();
                    listener.onCameraError("JPEG Capture 超过 2 秒未返回");
                }
            }, CAPTURE_WATCHDOG_MS);
        } catch (CameraAccessException exception) {
            captureInFlight.set(false);
            metrics.captureSkipped.incrementAndGet();
            listener.onCameraError("提交 JPEG Capture 失败：" + safeMessage(exception));
        }
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] jpeg = new byte[buffer.remaining()];
            buffer.get(jpeg);
            long latency = Math.max(0L, SystemClock.elapsedRealtime() - captureRequestedAt);
            captureInFlight.set(false);
            metrics.framesReceived.incrementAndGet();
            metrics.frameBytes.addAndGet(jpeg.length);
            metrics.lastFrameBytes.set(jpeg.length);
            metrics.lastCaptureLatencyMs.set(latency);
            listener.onFrame(jpeg, latency);
        } catch (Exception exception) {
            captureInFlight.set(false);
            listener.onCameraError("读取 JPEG 失败：" + safeMessage(exception));
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void closeInternal() {
        sessionReady = false;
        opening = false;
        captureInFlight.set(false);
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception ignored) {
                // The session can already be closed after a device error.
            }
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        closeReaderAndSurface();
        listener.onCameraState("关闭", false);
    }

    private void closeReaderAndSurface() {
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
    }

    private static String chooseCamera(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) {
            throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "未发现摄像头");
        }
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return ids[0];
    }

    private static Size chooseSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        return Arrays.stream(sizes)
                .filter(size -> (long) size.getWidth() * size.getHeight() <= MAX_CAPTURE_PIXELS)
                .max(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                .orElseGet(() -> Arrays.stream(sizes)
                        .min(Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()))
                        .orElse(sizes[0]));
    }

    private static Size choosePreviewSize(Size[] sizes, Size target) {
        if (sizes == null || sizes.length == 0) {
            return target;
        }
        double targetRatio = target.getWidth() / (double) target.getHeight();
        return Arrays.stream(sizes)
                .filter(size -> size.getWidth() <= 1280 && size.getHeight() <= 720)
                .min(Comparator.comparingDouble(size ->
                        Math.abs(size.getWidth() / (double) size.getHeight() - targetRatio)
                                + Math.abs((long) size.getWidth() * size.getHeight()
                                - (long) target.getWidth() * target.getHeight()) / 1_000_000.0))
                .orElse(sizes[0]);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? exception.getClass().getSimpleName() : message;
    }
}
