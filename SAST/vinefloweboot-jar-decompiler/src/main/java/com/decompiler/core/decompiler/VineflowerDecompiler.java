package com.decompiler.core.decompiler;

import com.decompiler.exception.DecompilerException;
import com.decompiler.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class VineflowerDecompiler implements Decompiler {
    private static final Logger logger = LoggerFactory.getLogger(VineflowerDecompiler.class);

    private final Map<String, Object> options;

    public VineflowerDecompiler(Map<String, Object> options) {
        this.options = options;
    }

    @Override
    public void decompile(Path jarPath, Path outputDir) {
        try {
            if (!Files.exists(jarPath)) {
                throw new DecompilerException("JAR 文件不存在: " + jarPath);
            }

            FileUtils.ensureDirectory(outputDir);

            org.jetbrains.java.decompiler.api.Decompiler.Builder builder =
                    new org.jetbrains.java.decompiler.api.Decompiler.Builder()
                            .inputs(jarPath.toFile())
                            .output(new org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver(outputDir.toFile()));

            for (Map.Entry<String, Object> entry : options.entrySet()) {
                builder.option(entry.getKey(), entry.getValue().toString());
            }

            org.jetbrains.java.decompiler.api.Decompiler decompiler = builder.build();
            decompiler.decompile();

        } catch (Exception e) {
            logger.error("反编译失败: {}", jarPath.getFileName(), e);
            throw new DecompilerException("反编译失败: " + jarPath, e);
        }
    }

    @Override
    public void decompileWithLibraries(Path jarPath, Path outputDir, List<Path> libraries) {
        try {
            logger.info("反编译: {}", jarPath.getFileName());

            if (!Files.exists(jarPath)) {
                throw new DecompilerException("JAR 文件不存在: " + jarPath);
            }

            FileUtils.ensureDirectory(outputDir);

            org.jetbrains.java.decompiler.api.Decompiler.Builder builder =
                    new org.jetbrains.java.decompiler.api.Decompiler.Builder()
                            .inputs(jarPath.toFile())
                            .output(new org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver(outputDir.toFile()));

            if (libraries != null && !libraries.isEmpty()) {
                logger.info("正在加载 {} 个依赖库作为上下文...", libraries.size());

                int loaded = 0;
                for (Path library : libraries) {
                    if (Files.exists(library)) {
                        builder.libraries(library.toFile());
                        loaded++;

                        // 每加载 10 个显示一次进度
                        if (loaded % 10 == 0) {
                            logger.info("已添加 {}/{} 个依赖库...", loaded, libraries.size());
                        }
                    }
                }

                logger.info("依赖库已全部添加 ({} 个)", loaded);
            }

            for (Map.Entry<String, Object> entry : options.entrySet()) {
                builder.option(entry.getKey(), entry.getValue().toString());
            }

            org.jetbrains.java.decompiler.api.Decompiler decompiler = builder.build();

            logger.info("开始反编译");
            decompiler.decompile();

            logger.info("反编译完成: {}", jarPath.getFileName());

        } catch (Exception e) {
            logger.error("反编译失败: {}", jarPath.getFileName(), e);
            throw new DecompilerException("反编译失败: " + jarPath, e);
        }
    }
}