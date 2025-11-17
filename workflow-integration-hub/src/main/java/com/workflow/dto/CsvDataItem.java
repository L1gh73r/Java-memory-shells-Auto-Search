package com.workflow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvDataItem {
    private int id;
    private String callerClass;
    private String field;
    private String targetClass;
    private String method;
}