@echo off
REM ── MeetAI 백엔드 로컬 실행 ──────────────────────────────────────
REM JDK 22의 Windows 유닉스 도메인 소켓 버그(Unable to establish loopback
REM connection) 우회: 소켓 경로를 짧은 ASCII 경로로 강제
if not exist C:\tmp mkdir C:\tmp
set JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\tmp

REM DB: SPRING_DATASOURCE_URL 미설정 시 로컬 H2 파일 DB(meetai_test) 자동 사용
REM API 키: .env 파일에서 자동 로드 (OPENAI_API_KEY 등)
REM 참고: 로컬에 ffmpeg가 없으면 실시간(Realtime) 자막 대신 Whisper 배치로 자동 폴백

call gradlew.bat bootRun
