package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingRepository extends JpaRepository<Meeting, String> {

    @Modifying
    @Query("UPDATE Meeting m SET m.status = :status WHERE m.id = :id")
    void updateStatus(@Param("id") String id, @Param("status") MeetingStatus status);

    @Modifying
    @Query("UPDATE Meeting m SET m.status = :status, m.errorCode = :code, m.errorDetail = :detail WHERE m.id = :id")
    void updateStatusWithError(@Param("id") String id,
                               @Param("status") MeetingStatus status,
                               @Param("code") String errorCode,
                               @Param("detail") String errorDetail);
}
