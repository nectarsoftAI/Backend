package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.MeetingParticipant;
import com.nectarsoft.meetai.model.ParticipantRole;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
public class ParticipantResponse {
    private Long participantId;
    private UUID profileId;
    private ParticipantRole role;
    private boolean canInvite;
    private boolean canEdit;
    private boolean canDelete;
    private boolean canRunMeeting;
    private OffsetDateTime joinedAt;

    public static ParticipantResponse from(MeetingParticipant p) {
        return ParticipantResponse.builder()
                .participantId(p.getParticipantId())
                .profileId(p.getProfileId())
                .role(p.getRole())
                .canInvite(p.isCanInvite())
                .canEdit(p.isCanEdit())
                .canDelete(p.isCanDelete())
                .canRunMeeting(p.isCanRunMeeting())
                .joinedAt(p.getJoinedAt())
                .build();
    }
}
