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
public class CsvDataResponse {
    private boolean success;
    private List<CsvDataItem> data;
    private BatchInfo batchInfo;
    private String filePath;
    private String error;
    private String searchPath;
}