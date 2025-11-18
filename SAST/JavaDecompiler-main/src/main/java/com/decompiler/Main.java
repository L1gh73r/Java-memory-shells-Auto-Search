package com.decompiler;

import com.decompiler.config.DecompilerConfig;
import com.decompiler.config.OptionsBuilder;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Options options = new Options();

        options.addOption(Option.builder("i")
                .longOpt("input")
                .hasArg()
                .argName("path")
                .desc("输入路径：Spring Boot JAR、普通 JAR、class 文件或目录（必选）")
                .required()
                .build());

        options.addOption(Option.builder("o")
                .longOpt("output")
                .hasArg()
                .argName("path")
                .desc("输出目录，用于存放反编译后的源码（必选）")
                .required()
                .build());

        options.addOption(Option.builder("t")
                .longOpt("threads")
                .hasArg()
                .argName("n")
                .desc("线程数，默认使用 CPU 核心数")
                .required(false)
                .build());

        options.addOption(Option.builder()
                .longOpt("include-deps")
                .desc("是否包含依赖库反编译（仅对 Spring Boot Fat JAR 有效，默认开启）")
                .required(false)
                .build());

        options.addOption(Option.builder()
                .longOpt("keep-class")
                .desc("反编译完成后保留中间的 .class 文件（默认删除）")
                .required(false)
                .build());

        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("显示帮助信息")
                .required(false)
                .build());

        CommandLineParser parser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption("h")) {
                formatter.printHelp("java -jar JavaDecompiler.jar", options, true);
                return;
            }

            String input = cmd.getOptionValue("input");
            String output = cmd.getOptionValue("output");

            int threads = Runtime.getRuntime().availableProcessors();
            if (cmd.hasOption("threads")) {
                try {
                    threads = Integer.parseInt(cmd.getOptionValue("threads"));
                } catch (NumberFormatException e) {
                    logger.warn("线程数参数解析失败，使用默认值: {}", threads);
                }
            }

            boolean includeDeps = cmd.hasOption("include-deps") || true;
            boolean deleteClassFiles = !cmd.hasOption("keep-class");

            DecompilerConfig config = new DecompilerConfig.Builder()
                    .inputPath(input)
                    .outputPath(output)
                    .threadCount(threads)
                    .deleteClassFiles(deleteClassFiles)
                    .includeDependencies(includeDeps)
                    .build();

            OptionsBuilder optionsBuilder = new OptionsBuilder();
            Map<String, Object> vineflowerOptions = optionsBuilder.build();

            logger.info("========================================");
            logger.info("CLI 模式启动 JavaDecompiler");
            logger.info("输入路径: {}", config.getInputPath());
            logger.info("输出目录: {}", config.getOutputPath());
            logger.info("线程数: {}", config.getThreadCount());
            logger.info("包含依赖库: {}", config.isIncludeDependencies() ? "是" : "否");
            logger.info("反编译后删除 class 文件: {}", config.isDeleteClassFiles() ? "是" : "否");
            logger.info("========================================");

            long start = System.currentTimeMillis();
            Decompiler decompiler = new Decompiler(config, vineflowerOptions);
            decompiler.execute();
            long end = System.currentTimeMillis();

            double seconds = (end - start) / 1000.0;
            logger.info("反编译完成，总耗时: {} 秒", seconds);

        } catch (MissingOptionException e) {
            logger.error("参数缺失: {}", e.getMessage());
            formatter.printHelp("java -jar JavaDecompiler.jar", options, true);
            System.exit(1);
        } catch (ParseException e) {
            logger.error("参数解析失败: {}", e.getMessage());
            formatter.printHelp("java -jar JavaDecompiler.jar", options, true);
            System.exit(1);
        } catch (Exception e) {
            logger.error("反编译过程中发生错误", e);
            System.exit(1);
        }
    }
}