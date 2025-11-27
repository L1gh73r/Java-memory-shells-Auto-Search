package com.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Webhook服务
 * 负责调用n8n webhook
 */
@Service
public class WebhookService {
    
    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);
    
    private final RestTemplate restTemplate;
    
    @Value("${n8n.server.host}")
    private String n8nHost;
    
    @Value("${n8n.server.port}")
    private String n8nPort;
    
    @Value("${n8n.server.webhook_path}")
    private String webhookPath;
    
    @Value("${n8n.server.webhook_url:}")
    private String webhookUrl;
    
    @Value("${n8n.workflow.name:showmaker-analysis-workflow}")
    private String workflowName;
    
    @Value("${n8n.workflow.timeout:3600}")
    private int workflowTimeout;
    
    public WebhookService() {
        this.restTemplate = new RestTemplate();
    }
    
    /**
     * 触发n8n webhook
     */
    public boolean triggerN8nWebhook() {
        try {
            // 构建完整的webhook URL
            String url = getWebhookUrl();
            
            // 准备请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            
            // 准备请求体
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("workflow_name", workflowName);
            requestBody.put("trigger_data", getTriggerData());
            requestBody.put("callback_url", "http://workflow:7781/api/n8n/result");
            requestBody.put("timeout", workflowTimeout);
            
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            
            // 发送请求
            logger.info("触发n8n webhook: {}", url);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("n8n webhook触发成功: {}", response.getBody());
                return true;
            } else {
                logger.error("n8n webhook触发失败: {}", response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            logger.error("触发n8n webhook异常", e);
            return false;
        }
    }
    
    /**
     * 获取触发数据
     */
    private Map<String, Object> getTriggerData() {
        Map<String, Object> triggerData = new HashMap<>();
        
        try {
            // 添加基本信息
            triggerData.put("source", "workflow-integration-hub");
            triggerData.put("timestamp", System.currentTimeMillis());
            
            // 添加容器信息
            Map<String, String> containerInfo = new HashMap<>();
            
            // 获取容器ID
            String containerId = System.getenv("HOSTNAME");
            if (containerId != null) {
                containerInfo.put("container_id", containerId);
            }
            
            // 获取容器IP
            String containerIp = System.getenv("CONTAINER_IP");
            if (containerIp != null) {
                containerInfo.put("container_ip", containerIp);
            }
            
            // 获取Java版本
            String javaVersion = System.getProperty("java.version");
            if (javaVersion != null) {
                containerInfo.put("java_version", javaVersion);
            }
            
            triggerData.put("container_info", containerInfo);
            
        } catch (Exception e) {
            logger.warn("获取触发数据失败", e);
        }
        
        return triggerData;
    }
    
    /**
     * 构建完整的webhook URL
     */
    private String getWebhookUrl() {
        if (webhookUrl != null && !webhookUrl.isEmpty()) {
            return webhookUrl;
        }
        
        return String.format("http://%s:%s%s", n8nHost, n8nPort, webhookPath);
    }
}
