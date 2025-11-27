package com.workflow.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * N8N回调控制器
 * 用于接收n8n工作流的回调
 */
@RestController
@RequestMapping("/api/n8n")
public class N8nCallbackController {
    
    private static final Logger logger = LoggerFactory.getLogger(N8nCallbackController.class);
    
    /**
     * 接收n8n工作流结果
     * n8n工作流完成后调用此接口，传递分析结果
     */
    @PostMapping("/result")
    public ResponseEntity<Map<String, String>> receiveN8nResult(@RequestBody JsonNode resultData) {
        logger.info("接收到n8n工作流结果");
        
        try {
            // 记录接收到的数据
            logger.info("结果数据: {}", resultData.toString());
            
            // TODO: 后续将在这里实现验证容器触发逻辑
            
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "结果已接收"
            ));
        } catch (Exception e) {
            logger.error("处理n8n结果失败", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "处理结果失败: " + e.getMessage()
            ));
        }
    }
}
