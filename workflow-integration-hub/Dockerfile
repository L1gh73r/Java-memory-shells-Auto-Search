FROM eclipse-temurin:8-jdk AS jdk8

# 仅用于提供 JDK8，后续阶段会把 JDK8 拷贝到最终镜像中

FROM openjdk:17-jdk-slim

WORKDIR /app

# 从前一阶段复制 JDK8 到固定路径，供 DynamicTracer 目标 JVM 使用
COPY --from=jdk8 /opt/java/openjdk /opt/jdk8
ENV JAVA8_HOME=/opt/jdk8

COPY target/workflow-integration-hub-1.0.0.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]