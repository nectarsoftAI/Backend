package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.AudioFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AudioFileRepository extends JpaRepository<AudioFile, Long> {

    List<AudioFile> findByMeetingMeetingId(UUID meetingId);

    void deleteByMeetingMeetingId(UUID meetingId);
}
