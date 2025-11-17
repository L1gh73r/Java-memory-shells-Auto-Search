package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySearchResponse {
    private boolean success;
    private String error;
    private String targetClass;
    private String targetMethod;
    private String searchId;
    private long executionTimeMs;
    private String resultFilePath;
    private List<Map<String, Object>> searchResults;
    private int resultCount;
    private boolean configUpdated;
}
