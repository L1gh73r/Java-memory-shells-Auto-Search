package com.decompiler.executor;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * 任务执行器接口
 */
public interface TaskExecutor {

    /**
     * 提交单个任务
     */
    <T> Future<T> submit(Callable<T> task);

    /**
     * 批量提交任务
     */
    <T> List<Future<T>> submitBatch(List<Callable<T>> tasks);

    /**
     * 关闭执行器（会等待所有任务完成）
     */
    void shutdown();
}