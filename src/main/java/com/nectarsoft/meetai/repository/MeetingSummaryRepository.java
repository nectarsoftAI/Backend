package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.MeetingSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingSummaryRepository extends JpaRepository<MeetingSummary, Long> {

    Optional<MeetingSummary> findByMeetingMeetingId(UUID meetingId);

    @Query("SELECT s FROM MeetingSummary s JOIN FETCH s.meeting WHERE s.meeting.meetingId IN :meetingIds")
    List<MeetingSummary> findByMeetingMeetingIdIn(@Param("meetingIds") Collection<UUID> meetingIds);
}
