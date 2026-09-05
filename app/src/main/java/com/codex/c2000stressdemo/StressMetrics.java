package com.codex.c2000stressdemo;

import java.util.concurrent.atomic.AtomicLong;

final class StressMetrics {
    final AtomicLong captureRequests = new AtomicLong();
    final AtomicLong framesReceived = new AtomicLong();
    final AtomicLong captureSkipped = new AtomicLong();
    final AtomicLong captureStalls = new AtomicLong();
    final AtomicLong frameBytes = new AtomicLong();
    final AtomicLong lastFrameBytes = new AtomicLong();
    final AtomicLong lastCaptureLatencyMs = new AtomicLong();

    final AtomicLong uploadAttempts = new AtomicLong();
    final AtomicLong uploadSuccess = new AtomicLong();
    final AtomicLong uploadFailures = new AtomicLong();
    final AtomicLong uploadBytes = new AtomicLong();
    final AtomicLong uploadLatencyMs = new AtomicLong();
    final AtomicLong uploadQueueDrops = new AtomicLong();

    final AtomicLong processFrames = new AtomicLong();
    final AtomicLong processTasks = new AtomicLong();
    final AtomicLong processDrops = new AtomicLong();
    final AtomicLong processNanos = new AtomicLong();
    final AtomicLong processChecksum = new AtomicLong();

    void reset() {
        captureRequests.set(0);
        framesReceived.set(0);
        captureSkipped.set(0);
        captureStalls.set(0);
        frameBytes.set(0);
        lastFrameBytes.set(0);
        lastCaptureLatencyMs.set(0);
        uploadAttempts.set(0);
        uploadSuccess.set(0);
        uploadFailures.set(0);
        uploadBytes.set(0);
        uploadLatencyMs.set(0);
        uploadQueueDrops.set(0);
        processFrames.set(0);
        processTasks.set(0);
        processDrops.set(0);
        processNanos.set(0);
        processChecksum.set(0);
    }
}
