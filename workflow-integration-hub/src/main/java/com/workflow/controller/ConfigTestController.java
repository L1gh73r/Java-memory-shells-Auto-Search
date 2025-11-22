package com.workflow.controller;

import com.workflow.config.WorkflowConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConfigTestController {

    @Autowired
    private WorkflowConfig config;

    @GetMapping("/config-test")
    public Map<String, Object> testConfig() {
        Map<String, Object> result = new HashMap<>();
        
        String jsonBaseDir = config.getData().getJsonBaseDir();
        result.put("jsonBaseDir", jsonBaseDir);
        
        File dir = new File(jsonBaseDir);
        result.put("dirExists", dir.exists());
        result.put("isDirectory", dir.isDirectory());
        
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            result.put("fileCount", files != null ? files.length : 0);
            if (files != null) {
                String[] fileNames = new String[files.length];
                for (int i = 0; i < files.length; i++) {
                    fileNames[i] = files[i].getName();
                }
                result.put("files", fileNames);
            }
        }
        
        return result;
    }
}
