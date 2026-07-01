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
    private UUID userId;
    private ParticipantRole role;
    private boolean canInvite;
    private boolean canEdit;
    private boolean canDelete;
    private boolean canStartEnd;
    private OffsetDateTime joinedAt;

    public static ParticipantResponse from(MeetingParticipant p) {
        return ParticipantResponse.builder()
                .participantId(p.getParticipantId())
                .userId(p.getUserId())
                .role(p.getRole())
                .canInvite(p.isCanInvite())
                .canEdit(p.isCanEdit())
                .canDelete(p.isCanDelete())
                .canStartEnd(p.isCanStartEnd())
                .joinedAt(p.getJoinedAt())
                .build();
    }
}
