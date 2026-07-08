package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.repository.MeetingRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/livekit")
@RequiredArgsConstructor
public class LiveKitTokenController {

    private final MeetAiProperties props;
    private final MeetingRepository meetingRepo;

    /**
     * GET /api/v1/livekit/token?meetingId=&profileId=&token=
     * LiveKit 입장 토큰 발급 — 프론트에서 호출
     */
    @GetMapping("/token")
    public ResponseEntity<Map<String, String>> getToken(
            @RequestParam String meetingId,
            @RequestParam String profileId,
            @RequestParam(required = false) String token) {

        log.info("[LiveKit] 토큰 요청 수신 — meetingId={}, profileId={}", meetingId, profileId);

        MeetAiProperties.LiveKit lk = props.getLivekit();
        if (lk.getApiKey().isBlank() || lk.getApiSecret().isBlank() || lk.getUrl().isBlank()) {
            log.error("[LiveKit] 환경변수 누락 — LIVEKIT_API_KEY={}, LIVEKIT_API_SECRET={}, LIVEKIT_URL={}",
                    lk.getApiKey().isBlank() ? "없음" : "OK",
                    lk.getApiSecret().isBlank() ? "없음" : "OK",
                    lk.getUrl().isBlank() ? "없음" : "OK");
            return ResponseEntity.status(500).body(Map.of("error", "LiveKit env vars not configured"));
        }

        var meeting = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
        if (meeting == null) {
            log.warn("[LiveKit] 토큰 거절 — 회의 없음: meetingId={}", meetingId);
            return ResponseEntity.notFound().build();
        }

        UUID pId = UUID.fromString(profileId);
        boolean isAdmin = pId.equals(meeting.getUserId());

        // 게스트 토큰 검증
        if (!isAdmin && (token == null || !token.equalsIgnoreCase(meeting.getInviteToken()))) {
            log.warn("[LiveKit] 토큰 거절 — 초대 토큰 불일치: meetingId={}, profileId={}", meetingId, profileId);
            return ResponseEntity.status(403).build();
        }

        String jwt = buildLiveKitToken(meetingId, profileId, true);
        log.info("[LiveKit] 토큰 발급 — meetingId={}, profileId={}, isAdmin={}", meetingId, profileId, isAdmin);
        return ResponseEntity.ok(Map.of(
                "token", jwt,
                "url", props.getLivekit().getUrl()
        ));
    }

    private String buildLiveKitToken(String roomName, String identity, boolean canPublish) {
        MeetAiProperties.LiveKit lk = props.getLivekit();
        SecretKey key = Keys.hmacShaKeyFor(lk.getApiSecret().getBytes(StandardCharsets.UTF_8));

        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(lk.getApiKey())
                .subject(identity)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .notBefore(new Date(now))
                .expiration(new Date(now + 6 * 3600 * 1000L))  // 6시간
                .claim("video", Map.of(
                        "roomJoin", true,
                        "room", roomName,
                        "canPublish", canPublish,
                        "canSubscribe", true,
                        "canPublishData", true
                ))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
