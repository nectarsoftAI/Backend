package com.nectarsoft.meetai.service;

/**
 * 참여자 1인의 실시간 STT 세션 공통 인터페이스
 * - RealtimeSttSession: OpenAI Realtime API (말하는 중 partial 자막)
 * - WhisperStreamingSession: Whisper 배치 폴백 (발화 단위 자막)
 */
public interface SttStreamSession {

    /** WS로 수신한 오디오 청크 (webm/ogg 컨테이너 또는 raw 16kHz PCM) */
    void sendAudio(byte[] data);

    void close();
}
