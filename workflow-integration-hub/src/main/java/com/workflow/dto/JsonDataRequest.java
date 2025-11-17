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
    
    // 获取实际的文件路径，优先使用 filePath，如果为空则使用 file_path
    public String getActualFilePath() {
        if (filePath != null && !filePath.trim().isEmpty()) {
            return filePath;
        }
        return filePathUnderscore;
    }
}