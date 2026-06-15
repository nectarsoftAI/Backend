package com.nectarsoft.meetai.repository;

import com.nectarsoft.meetai.model.LiveSession;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveSessionRepository extends JpaRepository<LiveSession, String> {
}
