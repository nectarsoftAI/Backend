package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TranscriptRepository extends JpaRepository<Transcript, Long> {

    List<Transcript> findByMeetingMeetingIdOrderByStartSecAsc(UUID meetingId);

    List<Transcript> findBySttResultSttIdOrderByStartSecAsc(Long sttId);

    void deleteByMeetingMeetingId(UUID meetingId);
}
