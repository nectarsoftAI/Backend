package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TranscriptRepository extends JpaRepository<Transcript, Long> {

    List<Transcript> findByMeetingMeetingIdOrderByStartSecAsc(UUID meetingId);

    /** 화자 이름 일괄 변경 — 해당 회의에서 speakerLabel이 같은 모든 발언의 speakerDisplay를 한 번의 UPDATE로 변경 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Transcript t SET t.speakerDisplay = :display "
            + "WHERE t.meeting.meetingId = :meetingId AND t.speakerLabel = :label")
    int renameSpeaker(@Param("meetingId") UUID meetingId,
                      @Param("label") String speakerLabel,
                      @Param("display") String speakerDisplay);

    @Query("SELECT t FROM Transcript t JOIN FETCH t.meeting WHERE t.meeting.meetingId IN :meetingIds")
    List<Transcript> findByMeetingMeetingIdIn(@Param("meetingIds") Collection<UUID> meetingIds);

    List<Transcript> findBySttResultSttIdOrderByStartSecAsc(Long sttId);

    void deleteByMeetingMeetingId(UUID meetingId);
}
