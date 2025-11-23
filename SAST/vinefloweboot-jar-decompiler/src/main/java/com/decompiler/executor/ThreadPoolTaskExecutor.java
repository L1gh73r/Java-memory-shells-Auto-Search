package com.decompiler.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class ThreadPoolTaskExecutor implements TaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ThreadPoolTaskExecutor.class);

    private final ExecutorService executorService;

    public ThreadPoolTaskExecutor(int threadCount) {
        if (threadCount <= 0) {
            throw new IllegalArgumentException("线程数必须大于 0");
        }

        this.executorService = Executors.newFixedThreadPool(threadCount);
        logger.info("线程池已创建，线程数: {}", threadCount);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return executorService.submit(task);
    }

    @Override
    public <T> List<Future<T>> submitBatch(List<Callable<T>> tasks) {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(executorService.submit(task));
        }
        logger.debug("已提交 {} 个任务", tasks.size());
        return futures;
    }

    @Override
    public void shutdown() {
        logger.info("关闭线程池...");
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                logger.warn("等待超时，强制关闭");
                executorService.shutdownNow();
            } else {
                logger.info("线程池已关闭");
            }
        } catch (InterruptedException e) {
            logger.error("等待被中断", e);
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}