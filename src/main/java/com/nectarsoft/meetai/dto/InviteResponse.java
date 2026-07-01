package com.nectarsoft.meetai.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class InviteResponse {
    private String meetingId;
    private String token;
}
