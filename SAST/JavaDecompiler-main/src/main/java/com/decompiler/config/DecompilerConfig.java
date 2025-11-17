package com.decompiler.config;

public class DecompilerConfig {
    private final String inputPath;
    private final String outputPath;
    private final int threadCount;
    private final boolean deleteClassFiles;
    private final boolean includeDependencies;

    private DecompilerConfig(Builder builder) {
        this.inputPath = builder.inputPath;
        this.outputPath = builder.outputPath;
        this.threadCount = builder.threadCount;
        this.deleteClassFiles = builder.deleteClassFiles;
        this.includeDependencies = builder.includeDependencies;
    }

    public String getInputPath() {
        return inputPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public boolean isDeleteClassFiles() {
        return deleteClassFiles;
    }

    public boolean isIncludeDependencies() {
        return includeDependencies;
    }

    public static class Builder {
        private String inputPath;
        private String outputPath;
        private int threadCount = Runtime.getRuntime().availableProcessors();
        private boolean deleteClassFiles = false; // 默认不删除class文件
        private boolean includeDependencies = true; // 默认包含依赖库

        public Builder inputPath(String inputPath) {
            this.inputPath = inputPath;
            return this;
        }

        public Builder outputPath(String outputPath) {
            this.outputPath = outputPath;
            return this;
        }

        public Builder threadCount(int threadCount) {
            this.threadCount = threadCount <= 0 ?
                    Runtime.getRuntime().availableProcessors() : threadCount;
            return this;
        }

        public Builder deleteClassFiles(boolean deleteClassFiles) {
            this.deleteClassFiles = deleteClassFiles;
            return this;
        }

        public Builder includeDependencies(boolean includeDependencies) {
            this.includeDependencies = includeDependencies;
            return this;
        }

        public DecompilerConfig build() {
            return new DecompilerConfig(this);
        }
    }
}