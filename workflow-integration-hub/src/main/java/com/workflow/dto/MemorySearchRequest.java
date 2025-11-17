package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchRequest {
    private String targetClass;
    private String targetMethod;
    @Builder.Default
    private String searchId = "bp_search";
    @Builder.Default
    private int timeoutSeconds = 60;
    @Builder.Default
    private int startupTimeoutSeconds = 30;
    @Builder.Default
    private String triggerUrl = "http://localhost:8080/a.css";
    @Builder.Default
    private String triggerMethod = "GET";
    private String triggerBody;
    @Builder.Default
    private int maxTriggerRetries = 3;
    @Builder.Default
    private int triggerDelaySeconds = 2;
    
    // 启动检测策略配置
    @Builder.Default
    private String startupDetectionStrategy = "AUTO"; // AUTO, PORT, LOG_PATTERN, HTTP_CHECK, FIXED_DELAY
    private String customLogPattern; // 自定义日志模式
    private Integer targetPort; // 目标端口（用于端口检测）
    private String healthCheckUrl; // 健康检查URL
    @Builder.Default
    private int fixedStartupDelaySeconds = 10; // 固定延迟时间
}
