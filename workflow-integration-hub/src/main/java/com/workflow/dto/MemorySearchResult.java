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
public class MemorySearchResult {
    private String tag;
    
    @JsonProperty("field_name") 
    private String fieldName;
    
    @JsonProperty("class")  // 保留这个，因为class是Java关键字
    private String className;
    
    private Integer depth;
    private String chain;
}
