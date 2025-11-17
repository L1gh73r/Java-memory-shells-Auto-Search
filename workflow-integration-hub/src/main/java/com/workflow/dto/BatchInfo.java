package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchInfo {
    private int currentBatch;
    private int batchSize;
    private int totalBatches;
    private int totalRows;
    private int currentBatchSize;
    private boolean hasNextBatch;
    private boolean hasPrevBatch;
}