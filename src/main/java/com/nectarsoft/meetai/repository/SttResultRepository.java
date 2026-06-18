package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.SttResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SttResultRepository extends JpaRepository<SttResult, Long> {

    List<SttResult> findByMeetingMeetingIdOrderBySttIdAsc(UUID meetingId);

    void deleteByMeetingMeetingId(UUID meetingId);
}
