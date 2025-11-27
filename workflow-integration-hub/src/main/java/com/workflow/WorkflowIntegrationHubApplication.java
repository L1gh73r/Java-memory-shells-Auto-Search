package com.workflow;

import com.workflow.service.WebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkflowIntegrationHubApplication {
    
    private static final Logger logger = LoggerFactory.getLogger(WorkflowIntegrationHubApplication.class);
    
    public static void main(String[] args) {
        SpringApplication.run(WorkflowIntegrationHubApplication.class, args);
    }
    
    /**
     * 应用启动后自动触发n8n webhook
     */
    @Bean
    @Order(1)
    public CommandLineRunner triggerN8nWebhook(@Autowired WebhookService webhookService) {
        return args -> {
            logger.info("Workflow Integration Hub已启动，准备触发n8n webhook...");
            
            try {
                // 等待服务完全就绪
                Thread.sleep(5000);
                
                // 触发n8n webhook
                boolean success = webhookService.triggerN8nWebhook();
                
                if (success) {
                    logger.info("n8n webhook触发成功");
                } else {
                    logger.error("n8n webhook触发失败");
                }
                
            } catch (Exception e) {
                logger.error("触发n8n webhook失败", e);
                // 不抛出异常，避免影响应用启动
            }
        };
    }
}