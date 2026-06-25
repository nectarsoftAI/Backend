package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TranscriptRepository extends JpaRepository<Transcript, Long> {

    List<Transcript> findByMeetingMeetingIdOrderByStartSecAsc(UUID meetingId);

    @Query("SELECT t FROM Transcript t JOIN FETCH t.meeting WHERE t.meeting.meetingId IN :meetingIds")
    List<Transcript> findByMeetingMeetingIdIn(@Param("meetingIds") Collection<UUID> meetingIds);

    List<Transcript> findBySttResultSttIdOrderByStartSecAsc(Long sttId);

    void deleteByMeetingMeetingId(UUID meetingId);
}
