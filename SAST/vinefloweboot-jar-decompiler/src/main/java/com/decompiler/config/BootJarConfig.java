package com.decompiler.config;

import java.nio.file.Path;
import java.util.Map;

public class BootJarConfig {
    private final Path inputJarPath;
    private final Path outputPath;
    private final Path workspaceDir;
    private final int threadCount;
    private final boolean cleanWorkspace;
    private final Map<String, Object> vineflowerOptions;

    private BootJarConfig(Builder builder) {
        this.inputJarPath = builder.inputJarPath;
        this.outputPath = builder.outputPath;
        this.workspaceDir = builder.workspaceDir;
        this.threadCount = builder.threadCount;
        this.cleanWorkspace = builder.cleanWorkspace;
        this.vineflowerOptions = builder.vineflowerOptions;
    }

    public Path getInputJarPath() {
        return inputJarPath;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public Path getWorkspaceDir() {
        return workspaceDir;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public boolean isCleanWorkspace() {
        return cleanWorkspace;
    }

    public Map<String, Object> getVineflowerOptions() {
        return vineflowerOptions;
    }


    public static class Builder {
        private Path inputJarPath;
        private Path outputPath;
        private Path workspaceDir;
        private int threadCount;
        private boolean cleanWorkspace = true;
        private Map<String, Object> vineflowerOptions;


        public Builder inputJarPath(String inputJarPath) {
            this.inputJarPath = Path.of(inputJarPath);
            return this;
        }


        public Builder outputPath(String outputPath) {
            this.outputPath = Path.of(outputPath);
            return this;
        }

        public Builder workspaceDir(String workspaceDir) {
            this.workspaceDir = Path.of(workspaceDir);
            return this;
        }

        public Builder threadCount(int threadCount) {
            if (threadCount <= 0) {
                throw new IllegalArgumentException("线程数必须大于 0");
            }
            this.threadCount = threadCount;
            return this;
        }

        public Builder cleanWorkspace(boolean cleanWorkspace) {
            this.cleanWorkspace = cleanWorkspace;
            return this;
        }

        public Builder vineflowerOptions(Map<String, Object> options) {
            this.vineflowerOptions = options;
            return this;
        }

        public BootJarConfig build() {
            if (inputJarPath == null) {
                throw new IllegalStateException("inputJarPath 不能为空");
            }
            if (outputPath == null) {
                throw new IllegalStateException("outputPath 不能为空");
            }

            if (workspaceDir == null) {
                workspaceDir = outputPath.resolve("workspace");
            }

            return new BootJarConfig(this);
        }
    }
}