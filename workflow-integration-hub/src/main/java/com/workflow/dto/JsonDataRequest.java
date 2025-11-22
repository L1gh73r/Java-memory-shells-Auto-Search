package com.workflow.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JsonDataRequest {
    @JsonProperty(value = "filePath")
    private String filePath;
    
    @JsonProperty(value = "file_path")
    private String filePathUnderscore;
    
    @JsonProperty(value = "includeSourceCode")
    private Boolean includeSourceCode;

    // 是否在读取 JSON 前触发一次内存搜索（JOSEarcher）
    @JsonProperty(value = "runMemorySearch")
    private Boolean runMemorySearch;

    // 内存搜索目标类与方法（如果不指定，可在后续根据 JSON 内容推断）
    @JsonProperty(value = "memoryTargetClass")
    private String memoryTargetClass;

    @JsonProperty(value = "memoryTargetMethod")
    private String memoryTargetMethod;

    // 可选：自定义 searchId，默认沿用 MemorySearchRequest 的默认值 bp_search
    @JsonProperty(value = "memorySearchId")
    private String memorySearchId;
    
    // 每条记录最大返回多少条 MEMORY_RESULTS（内存搜索结果），默认 10
    @JsonProperty(value = "memoryResultLimit")
    private Integer memoryResultLimit;
    
    // 获取实际的文件路径，优先使用 filePath，如果为空则使用 file_path
    public String getActualFilePath() {
        if (filePath != null && !filePath.trim().isEmpty()) {
            return filePath;
        }
        return filePathUnderscore;
    }
    
    // 判断是否需要包含源码
    public boolean shouldIncludeSourceCode() {
        return includeSourceCode != null && includeSourceCode;
    }

    // 判断是否需要在读取 JSON 之前执行内存搜索
    public boolean shouldRunMemorySearch() {
        return runMemorySearch != null && runMemorySearch;
    }
    
    // 获取内存结果的最大条数，未指定时默认返回前 10 条
    public int getMemoryResultLimitOrDefault() {
        if (memoryResultLimit == null || memoryResultLimit <= 0) {
            return 10;
        }
        return memoryResultLimit;
    }
}