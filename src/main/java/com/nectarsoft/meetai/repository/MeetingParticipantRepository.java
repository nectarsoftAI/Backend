package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.MeetingParticipant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, Long> {

    List<MeetingParticipant> findByMeetingMeetingId(UUID meetingId);

    Optional<MeetingParticipant> findByMeetingMeetingIdAndUserId(UUID meetingId, UUID userId);

    boolean existsByMeetingMeetingIdAndUserId(UUID meetingId, UUID userId);
}
