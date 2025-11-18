FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /opt/sast

# 仅拷贝 JavaDecompiler-main，用于构建反编译工具
COPY JavaDecompiler-main /opt/sast/JavaDecompiler-main

# 构建 JavaDecompiler 的可执行 JAR（带依赖）
RUN mvn -q -DskipTests -f /opt/sast/JavaDecompiler-main/pom.xml package

FROM eclipse-temurin:17-jdk-jammy

WORKDIR /opt

# 安装基础依赖和工具
RUN apt-get update && \
    apt-get install -y --no-install-recommends wget unzip ca-certificates && \
    rm -rf /var/lib/apt/lists/*

# 安装 CodeQL CLI
RUN wget -q https://github.com/github/codeql-cli-binaries/releases/latest/download/codeql-linux64.zip && \
    unzip codeql-linux64.zip && \
    rm codeql-linux64.zip && \
    mv codeql /opt/codeql-cli && \
    ln -s /opt/codeql-cli/codeql /usr/local/bin/codeql

# 拷贝 SAST 源（含 .ql、qlpack.yml、codeql-runner 脚本等）
WORKDIR /opt/sast
COPY . /opt/sast

# 预先安装 CodeQL 依赖包（codeql/java-all 等）
RUN codeql pack install

# 从构建阶段拷贝 JavaDecompiler 可执行包
COPY --from=builder /opt/sast/JavaDecompiler-main/target/JavaDecompiler.jar /opt/tools/JavaDecompiler.jar

# 入口脚本
RUN chmod +x /opt/sast/codeql-runner/entrypoint.sh

ENTRYPOINT ["/opt/sast/codeql-runner/entrypoint.sh"]


