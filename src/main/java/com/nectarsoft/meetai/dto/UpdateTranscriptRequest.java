package com.nectarsoft.meetai.dto;

import lombok.Data;

@Data
public class UpdateTranscriptRequest {
    private Long transcriptId;
    private String speakerDisplay;
    private String content;
}
