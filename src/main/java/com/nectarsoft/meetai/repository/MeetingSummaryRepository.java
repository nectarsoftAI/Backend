package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.MeetingSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MeetingSummaryRepository extends JpaRepository<MeetingSummary, Long> {

    Optional<MeetingSummary> findByMeetingMeetingId(UUID meetingId);
}
