package com.nectarsoft.meetai.core.exception;

import org.springframework.http.HttpStatus;

/** 모든 도메인 예외를 중앙에서 정의 — EX 코드별 서브클래스 */
public final class Exceptions {

    private Exceptions() {}

    // EX-002: 무음 감지
    public static class SilenceDetectedError extends MeetAiException {
        public SilenceDetectedError(String detail) {
            super("EX-002", "오디오에서 무음이 감지되었습니다: " + detail, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    // EX-003: 오디오 포맷 오류
    public static class AudioFormatError extends MeetAiException {
        public AudioFormatError(String detail) {
            super("EX-003", "지원하지 않는 오디오 포맷: " + detail, HttpStatus.BAD_REQUEST);
        }
        public AudioFormatError(String detail, Throwable cause) {
            super("EX-003", "지원하지 않는 오디오 포맷: " + detail, HttpStatus.BAD_REQUEST, cause);
        }
    }

    // EX-005: STT 처리 실패
    public static class SttFailedError extends MeetAiException {
        public SttFailedError(String detail) {
            super("EX-005", "STT 처리 실패: " + detail, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        public SttFailedError(String detail, Throwable cause) {
            super("EX-005", "STT 처리 실패: " + detail, HttpStatus.INTERNAL_SERVER_ERROR, cause);
        }
    }

    // EX-006: 화자 분리 실패
    public static class SpeakerDiarizationError extends MeetAiException {
        public SpeakerDiarizationError(String detail) {
            super("EX-006", "화자 분리 실패: " + detail, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // EX-009: DB 저장 실패
    public static class DbSaveError extends MeetAiException {
        public DbSaveError(String detail) {
            super("EX-009", "DB 저장 실패: " + detail, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        public DbSaveError(String detail, Throwable cause) {
            super("EX-009", "DB 저장 실패: " + detail, HttpStatus.INTERNAL_SERVER_ERROR, cause);
        }
    }

    // EX-010: 청크 유효성 오류
    public static class InvalidChunkError extends MeetAiException {
        public InvalidChunkError(String detail) {
            super("EX-010", "유효하지 않은 오디오 청크: " + detail, HttpStatus.BAD_REQUEST);
        }
    }

    // EX-011: 세션 없음
    public static class SessionNotFoundError extends MeetAiException {
        public SessionNotFoundError(String sessionId) {
            super("EX-011", "세션을 찾을 수 없습니다: " + sessionId, HttpStatus.NOT_FOUND);
        }
    }

    // EX-012: 미팅 없음
    public static class MeetingNotFoundError extends MeetAiException {
        public MeetingNotFoundError(String meetingId) {
            super("EX-012", "미팅을 찾을 수 없습니다: " + meetingId, HttpStatus.NOT_FOUND);
        }
    }
}
