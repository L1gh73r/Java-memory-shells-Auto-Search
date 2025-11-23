package com.decompiler.service;

import com.decompiler.config.BootJarConfig;

/**
 * Boot JAR 处理器接口
 */
public interface BootJarProcessor {

    /**
     * 处理 Boot JAR 文件
     * @param config 配置
     */
    void process(BootJarConfig config);
}