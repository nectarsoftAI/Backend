package com.nectarsoft.meetai.dto;

import lombok.Data;

/** 화자 이름 일괄 변경 요청 — speakerLabel(SPEAKER_A 등)에 해당하는 모든 발언의 표시 이름을 한 번에 변경 */
@Data
public class RenameSpeakerRequest {
    private String speakerLabel;    // 예: "SPEAKER_A"
    private String speakerDisplay;  // 바꿀 표시 이름, 예: "홍길동"
}
