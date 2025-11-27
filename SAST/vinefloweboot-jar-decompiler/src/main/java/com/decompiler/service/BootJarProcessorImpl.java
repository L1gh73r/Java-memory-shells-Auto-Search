package com.decompiler.service;

import com.decompiler.config.BootJarConfig;
import com.decompiler.core.decompiler.Decompiler;
import com.decompiler.core.extractor.JarExtractor;
import com.decompiler.core.pom.PomExtractor;
import com.decompiler.exception.DecompilerException;
import com.decompiler.executor.TaskExecutor;
import com.decompiler.util.FileUtils;
import com.decompiler.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class BootJarProcessorImpl implements BootJarProcessor {
    private static final Logger logger = LoggerFactory.getLogger(BootJarProcessorImpl.class);

    private final JarExtractor jarExtractor;
    private final Decompiler decompiler;
    private final PomExtractor pomExtractor;
    private final TaskExecutor taskExecutor;

    public BootJarProcessorImpl(
            JarExtractor jarExtractor,
            Decompiler decompiler,
            PomExtractor pomExtractor,
            TaskExecutor taskExecutor) {
        this.jarExtractor = jarExtractor;
        this.decompiler = decompiler;
        this.pomExtractor = pomExtractor;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public void process(BootJarConfig config) {
        long startTime = System.currentTimeMillis();

        try {
            logger.info("========================================");
            logger.info("开始处理 Boot JAR: {}", config.getInputJarPath().getFileName());
            logger.info("========================================");

            prepareWorkspace(config);

            Path extractedDir = extractMainJar(config);

            extractPomFiles(config, extractedDir);

            List<Path> libraryJars = jarExtractor.listInnerJars(extractedDir);

            logger.info("========================================");
            logger.info("Step 1: 反编译主应用代码");
            logger.info("========================================");
            decompileMainApplication(config, extractedDir, libraryJars);

            logger.info("========================================");
            logger.info("Step 2: 反编译依赖库");
            logger.info("========================================");
            if (!libraryJars.isEmpty()) {
                decompileLibraries(config, libraryJars);
            }

            cleanup(config);

            long endTime = System.currentTimeMillis();
            double totalTime = (endTime - startTime) / 1000.0;

            logger.info("========================================");
            logger.info("处理完成！总耗时: {} 秒", String.format("%.2f", totalTime));
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("处理失败", e);
            throw new DecompilerException("Boot JAR 处理失败", e);
        } finally {
            taskExecutor.shutdown();
        }
    }


    private void prepareWorkspace(BootJarConfig config) {
        try {
            FileUtils.ensureDirectory(config.getOutputPath());
            FileUtils.ensureDirectory(config.getWorkspaceDir());

            FileUtils.ensureDirectory(config.getOutputPath().resolve("src/main/java"));
            FileUtils.ensureDirectory(config.getOutputPath().resolve("libs"));

            logger.info("工作目录: {}", config.getWorkspaceDir());
            logger.info("输出目录: {}", config.getOutputPath());

        } catch (Exception e) {
            throw new DecompilerException("准备工作目录失败", e);
        }
    }


    private Path extractMainJar(BootJarConfig config) {
        try {
            logger.info("解压主 JAR");

            Path extractedDir = config.getWorkspaceDir().resolve("extracted");
            jarExtractor.extract(config.getInputJarPath(), extractedDir);

            return extractedDir;

        } catch (Exception e) {
            throw new DecompilerException("解压主 JAR 失败", e);
        }
    }

    private void extractPomFiles(BootJarConfig config, Path extractedDir) {
        try {
            logger.info("提取 POM 文件");

            Path pomOutputDir = config.getOutputPath();
            pomExtractor.extractFromDirectory(extractedDir, pomOutputDir);

        } catch (Exception e) {
            logger.error("提取 POM 失败", e);
        }
    }

    private void decompileMainApplication(BootJarConfig config, Path extractedDir, List<Path> libraryJars) {
        try {
            Path classesDir = PathUtils.resolveClassesDirectory(extractedDir);

            if (classesDir == null || !Files.exists(classesDir)) {
                logger.warn("未找到主应用代码目录，跳过主应用反编译");

                if (!libraryJars.isEmpty()) {
                    Path tempOutput = config.getWorkspaceDir().resolve("temp-context-load");
                    FileUtils.ensureDirectory(tempOutput);
                    decompiler.decompileWithLibraries(libraryJars.get(0), tempOutput, libraryJars);
                    FileUtils.deleteDirectory(tempOutput);
                }
                return;
            }

            Path mainOutputDir = config.getOutputPath().resolve("src/main/java");

            logger.info("反编译主应用代码");
            logger.info("主应用代码目录: {}", classesDir);
            logger.info("依赖库数量: {}", libraryJars.size());
            logger.info("正在加载依赖库上下文");


            decompiler.decompileWithLibraries(classesDir, mainOutputDir, libraryJars);

            logger.info("主应用代码反编译完成，依赖库上下文已加载");

        } catch (Exception e) {
            logger.error("反编译主应用代码失败", e);
        }
    }

    private void decompileLibraries(BootJarConfig config, List<Path> libraryJars) {
        logger.info("开始批量反编译 {} 个依赖库", libraryJars.size());

        Path libsOutputDir = config.getOutputPath().resolve("libs");

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failedCount = new AtomicInteger(0);
        AtomicInteger currentCount = new AtomicInteger(0);
        List<String> failedLibs = new ArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();

        for (Path jarPath : libraryJars) {
            tasks.add(() -> {
                try {
                    String libName = PathUtils.getJarBaseName(jarPath);
                    Path libOutputDir = libsOutputDir.resolve(libName);

                    decompiler.decompile(jarPath, libOutputDir);

                    int success = successCount.incrementAndGet();
                    int current = currentCount.incrementAndGet();

                    if (current % 5 == 0) {
                        logger.info("反编译进度: {}/{} (成功: {}, 失败: {})",
                                current, libraryJars.size(), success, failedCount.get());
                    }

                } catch (Exception e) {
                    failedCount.incrementAndGet();
                    currentCount.incrementAndGet();
                    String libName = jarPath.getFileName().toString();
                    synchronized (failedLibs) {
                        failedLibs.add(libName);
                    }
                    logger.error("反编译失败: {}", libName, e);
                }
                return null;
            });
        }

        List<Future<Void>> futures = taskExecutor.submitBatch(tasks);

        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                logger.error("任务执行异常", e);
            }
        }

        logger.info("========================================");
        logger.info("依赖库反编译统计:");
        logger.info("总数: {}", libraryJars.size());
        logger.info("成功: {}", successCount.get());
        logger.info("失败: {}", failedCount.get());

        if (!failedLibs.isEmpty()) {
            logger.warn("失败的库:");
            failedLibs.forEach(lib -> logger.warn("  - {}", lib));
        }
        logger.info("========================================");
    }

    /**
     * 清理临时目录
     */
    private void cleanup(BootJarConfig config) {
        if (config.isCleanWorkspace()) {
            try {
                logger.info("清理临时目录");
                FileUtils.deleteDirectory(config.getWorkspaceDir());
                logger.info("临时目录已清理");
            } catch (Exception e) {
                logger.warn("清理临时目录失败", e);
            }
        }
    }
}