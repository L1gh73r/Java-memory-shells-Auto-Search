package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDataRequest {
    private String filePath;
    @Builder.Default
    private int batchSize = 50;
    @Builder.Default
    private int currentBatch = 0;
}