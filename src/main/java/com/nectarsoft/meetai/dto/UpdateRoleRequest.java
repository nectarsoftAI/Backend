package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.ParticipantRole;
import lombok.Getter;

@Getter
public class UpdateRoleRequest {
    private ParticipantRole role;
    private boolean canInvite;
    private boolean canEdit;
    private boolean canDelete;
    private boolean canRunMeeting;
}
