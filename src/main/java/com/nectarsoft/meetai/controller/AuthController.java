package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.model.Profile;
import com.nectarsoft.meetai.repository.ProfileRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final ProfileRepository profileRepo;

    @Getter @Setter @NoArgsConstructor
    static class ProfileRequest {
        @JsonProperty("display_name")
        private String displayName;
    }

    /**
     * 회원가입 후 display_name을 profiles 테이블에 저장/갱신.
     * Supabase trigger(handle_new_user)가 profiles 행을 자동 생성하지만,
     * display_name이 누락된 경우 이 엔드포인트로 보정할 수 있다.
     */
    @PutMapping("/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsertProfile(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody ProfileRequest req) {

        String displayName = req.getDisplayName();
        if (displayName == null || displayName.isBlank()) return;

        Profile profile = profileRepo.findById(userId)
                .orElse(Profile.builder().id(userId).build());
        profile.setDisplayName(displayName.trim());
        profileRepo.save(profile);

        log.info("[Auth] 프로필 업데이트 — userId={}, displayName={}", userId, displayName.trim());
    }
}
