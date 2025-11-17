package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JavaFileResponse {
    private boolean success;
    private String content;
    private String filePath;
    private String packageName;
    private String className;
    private String error;
    private List<String> searchedPaths;
}