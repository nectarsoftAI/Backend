package com.nectarsoft.meetai.dto;

import lombok.Data;

@Data
public class SaveSummaryRequest {
    private String keyPoints;
    private String decisions;
    private String actionItems;
    private String keywords;
    private String rawResponse;
}
