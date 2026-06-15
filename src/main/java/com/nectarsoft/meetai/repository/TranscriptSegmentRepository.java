package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.TranscriptSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByMeetingIdOrderBySegmentIndex(String meetingId);
}
