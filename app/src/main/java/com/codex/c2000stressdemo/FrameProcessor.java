package com.codex.c2000stressdemo;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class FrameProcessor {
    private static final int HASH_ROUNDS = 40;

    private final StressMetrics metrics;
    private ThreadPoolExecutor executor;
    private int threadCount;

    FrameProcessor(StressMetrics metrics) {
        this.metrics = metrics;
    }

    synchronized void start(int requestedThreads) {
        stop();
        threadCount = Math.max(1, Math.min(8, requestedThreads));
        int queueCapacity = Math.max(8, threadCount * 3);
        executor = new ThreadPoolExecutor(
                threadCount,
                threadCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new NamedThreadFactory("CameraCpu"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized void submit(byte[] jpeg) {
        ThreadPoolExecutor pool = executor;
        if (pool == null || pool.isShutdown() || jpeg == null || jpeg.length == 0) {
            return;
        }
        if (pool.getQueue().remainingCapacity() < threadCount) {
            metrics.processDrops.incrementAndGet();
            return;
        }
        metrics.processFrames.incrementAndGet();
        int chunk = Math.max(1, (jpeg.length + threadCount - 1) / threadCount);
        for (int worker = 0; worker < threadCount; worker++) {
            int start = worker * chunk;
            if (start >= jpeg.length) {
                break;
            }
            int end = Math.min(jpeg.length, start + chunk);
            try {
                pool.execute(() -> processChunk(jpeg, start, end));
            } catch (RejectedExecutionException exception) {
                metrics.processDrops.incrementAndGet();
            }
        }
    }

    private void processChunk(byte[] bytes, int start, int end) {
        long began = System.nanoTime();
        long hash = 0x9E3779B97F4A7C15L ^ start;
        for (int round = 0; round < HASH_ROUNDS; round++) {
            for (int index = start; index < end; index++) {
                hash ^= (bytes[index] & 0xFFL) + (round * 131L);
                hash = Long.rotateLeft(hash * 0x100000001B3L, 7);
            }
        }
        metrics.processTasks.incrementAndGet();
        metrics.processNanos.addAndGet(System.nanoTime() - began);
        metrics.processChecksum.addAndGet(hash);
    }

    synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor.getQueue().clear();
            executor = null;
        }
        threadCount = 0;
    }

    synchronized int getActiveCount() {
        return executor == null ? 0 : executor.getActiveCount();
    }

    synchronized int getQueueDepth() {
        return executor == null ? 0 : executor.getQueue().size();
    }

    synchronized boolean isRunning() {
        return executor != null && !executor.isShutdown();
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger next = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + next.getAndIncrement());
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
