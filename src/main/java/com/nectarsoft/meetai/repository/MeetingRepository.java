package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    List<Meeting> findAllByOrderByCreatedAtDesc();

    List<Meeting> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<Meeting> findByUserId(UUID userId, Pageable pageable);

    Optional<Meeting> findByInviteToken(String inviteToken);

    @Modifying
    @Query("UPDATE Meeting m SET m.status = :status WHERE m.meetingId = :id")
    void updateStatus(@Param("id") UUID id, @Param("status") MeetingStatus status);
}
