package com.decompiler.core.decompiler;

import java.nio.file.Path;
import java.util.List;

/**
 * 反编译器接口
 */
public interface Decompiler {

    /**
     * 反编译单个 JAR 文件
     * @param jarPath JAR 文件路径
     * @param outputDir 输出目录
     */
    void decompile(Path jarPath, Path outputDir);

    /**
     * 反编译 JAR 文件
     * @param jarPath JAR 文件路径
     * @param outputDir 输出目录
     * @param libraries 库文件列表（用于增强分析，不会被反编译）
     */
    void decompileWithLibraries(Path jarPath, Path outputDir, List<Path> libraries);
}