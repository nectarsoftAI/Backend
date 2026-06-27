# MeetAI Backend

> 넥타르소프트 — AI 회의록 자동화 시스템 백엔드

회의 음성을 실시간으로 텍스트로 변환하는 Spring Boot 서버입니다.  
OpenAI Whisper API를 사용해 한국어 음성 인식을 처리합니다.

---

## 주요 기능

- **파일 STT**: 오디오 파일 업로드 → 텍스트 변환 → DB 저장
- **실시간 STT**: WebSocket으로 마이크 스트리밍 → 30초 단위 처리 → 실시간 자막 전송

## 기술 스택

- Java 17 · Spring Boot 3.3 · Gradle 8.8
- OpenAI Whisper API (`whisper-1`, 한국어)
- H2 File DB (개발) / PostgreSQL (운영)
- WebSocket (JSR-356)

## 실행

```bash
# 1. API 키 설정 (src/main/resources/application.yml)
meetai.openai.api-key: sk-...

# 2. 서버 실행
./gradlew bootRun
```

- 서버: [https://backend-production-894a3.up.railway.app/](https://backend-production-894a3.up.railway.app/)
- API 문서: [Swagger UI](https://backend-production-894a3.up.railway.app/swagger-ui/index.html)

## API

| Method | Endpoint | 설명 |
|--------|----------|------|
| `POST` | `/api/v1/stt/transcribe` | 파일 업로드 STT |
| `GET` | `/api/v1/meetings/{meetingId}` | 회의 결과 조회 |
| `POST` | `/api/v1/live/sessions` | 라이브 세션 생성 |
| `WS` | `/api/v1/live/ws/{sessionId}` | 실시간 음성 스트리밍 |

## 라이브 테스트

```bash
python -m http.server 3000
```

`http://localhost:3000/live-test.html` 에서 실시간 자막 테스트 가능
