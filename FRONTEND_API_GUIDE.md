# MeetAI Frontend API Guide

## Base URL

```
https://backend-production-894a3.up.railway.app
```

---

## 목차

1. [STT — 파일 업로드 변환](#1-stt--파일-업로드-변환)
2. [회의 결과 조회](#2-회의-결과-조회)
3. [실시간 라이브 STT (WebSocket)](#3-실시간-라이브-stt-websocket)
4. [에러 코드](#4-에러-코드)

---

## 1. STT — 파일 업로드 변환

오디오 파일을 업로드하면 Whisper AI가 텍스트로 변환합니다.

### Request

```
POST /api/v1/stt/transcribe
Content-Type: multipart/form-data
```

| 파라미터 | 타입 | 필수 | 설명 |
|---------|------|------|------|
| `file` | File | ✅ | 오디오 파일 (wav, mp3, m4a, ogg, flac, webm) |

최대 파일 크기: **100MB**

### Response

```json
{
  "meetingId": "550e8400-e29b-41d4-a716-446655440000",
  "engineUsed": "openai_whisper",
  "segmentCount": 2,
  "transcripts": [
    {
      "speakerLabel": "SPEAKER_00",
      "speakerDisplay": "SPEAKER_00",
      "startSec": 0.0,
      "endSec": 4.2,
      "content": "안녕하세요 회의를 시작하겠습니다.",
      "confidence": 0.95,
      "lowConfidence": false
    },
    {
      "speakerLabel": "SPEAKER_00",
      "speakerDisplay": "SPEAKER_00",
      "startSec": 4.5,
      "endSec": 9.1,
      "content": "오늘 논의할 안건은 세 가지입니다.",
      "confidence": 0.91,
      "lowConfidence": false
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `meetingId` | string (UUID) | 생성된 회의 ID — 결과 조회 시 사용 |
| `engineUsed` | string | 사용된 STT 엔진 |
| `segmentCount` | number | 변환된 구간 수 |
| `transcripts[].speakerLabel` | string | 화자 식별자 |
| `transcripts[].startSec` | number | 구간 시작 시간 (초) |
| `transcripts[].endSec` | number | 구간 종료 시간 (초) |
| `transcripts[].content` | string | 변환된 텍스트 |
| `transcripts[].confidence` | number | 신뢰도 (0~1) |
| `transcripts[].lowConfidence` | boolean | 신뢰도 낮음 여부 |

### JavaScript 예시

```javascript
async function transcribeFile(audioFile) {
  const formData = new FormData();
  formData.append('file', audioFile);

  const response = await fetch(
    'https://backend-production-894a3.up.railway.app/api/v1/stt/transcribe',
    {
      method: 'POST',
      body: formData,
    }
  );

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message);
  }

  return await response.json();
}

// 사용 예시
const input = document.querySelector('input[type="file"]');
input.addEventListener('change', async (e) => {
  const file = e.target.files[0];
  const result = await transcribeFile(file);

  console.log('Meeting ID:', result.meetingId);
  result.transcripts.forEach((t) => {
    console.log(`[${t.startSec}s] ${t.content}`);
  });
});
```

---

## 2. 회의 결과 조회

STT 변환 완료 후 회의 전체 정보와 대화록을 조회합니다.

### Request

```
GET /api/v1/meetings/{meetingId}
```

| 파라미터 | 타입 | 설명 |
|---------|------|------|
| `meetingId` | string (UUID) | STT 응답에서 받은 meetingId |

### Response

```json
{
  "meetingId": "550e8400-e29b-41d4-a716-446655440000",
  "title": "회의녹음.wav",
  "meetingType": "UPLOAD",
  "status": "COMPLETED",
  "durationSeconds": null,
  "meetingDate": "2026-06-17T05:00:00Z",
  "createdAt": "2026-06-17T05:00:01Z",
  "transcripts": [
    {
      "speakerLabel": "SPEAKER_00",
      "speakerDisplay": "SPEAKER_00",
      "startSec": 0.0,
      "endSec": 4.2,
      "content": "안녕하세요 회의를 시작하겠습니다."
    }
  ]
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `meetingType` | string | `UPLOAD` (파일 업로드) \| `REALTIME` (라이브) |
| `status` | string | `PROCESSING` \| `COMPLETED` \| `LIVE` |

### JavaScript 예시

```javascript
async function getMeeting(meetingId) {
  const response = await fetch(
    `https://backend-production-894a3.up.railway.app/api/v1/meetings/${meetingId}`
  );

  if (!response.ok) throw new Error('회의를 찾을 수 없습니다.');
  return await response.json();
}

// 사용 예시
const meeting = await getMeeting('550e8400-e29b-41d4-a716-446655440000');
meeting.transcripts.forEach((t) => {
  console.log(`${t.speakerDisplay}: ${t.content}`);
});
```

---

## 3. 실시간 라이브 STT (WebSocket)

마이크 오디오를 실시간으로 전송하면 5초마다 STT 결과를 받습니다.

### WebSocket URL

```
wss://backend-production-894a3.up.railway.app/api/v1/live/ws
```

### 메시지 흐름

```
클라이언트                          서버
    |                                 |
    |──── WebSocket 연결 ────────────>|
    |                                 |
    |<─── session_created ────────────|  meeting_id 수신
    |                                 |
    |──── binary (오디오 청크) ──────>|  5초마다 전송
    |<─── segment (STT 결과) ─────────|  5초마다 수신
    |                                 |
    |──── binary (오디오 청크) ──────>|
    |<─── segment (STT 결과) ─────────|
    |                                 |
    |──── {"type":"end"} ────────────>|  녹음 종료
    |<─── session_ended ──────────────|
    |                                 |
```

### 서버 → 클라이언트 메시지 타입

**session_created** — 연결 직후 수신
```json
{
  "type": "session_created",
  "meeting_id": "bb4eb646-d7df-4497-968c-e862b21b6b29"
}
```

**segment** — STT 결과 (5초마다)
```json
{
  "type": "segment",
  "speaker_label": "SPEAKER_00",
  "start_sec": 0.0,
  "end_sec": 4.8,
  "text": "안녕하세요 회의를 시작하겠습니다.",
  "confidence": 0.95
}
```

**session_ended** — 세션 종료 확인
```json
{
  "type": "session_ended"
}
```

**error** — 서버 오류
```json
{
  "type": "error",
  "message": "오류 내용"
}
```

### 클라이언트 → 서버 메시지 타입

| 타입 | 형식 | 설명 |
|------|------|------|
| 오디오 | binary (ArrayBuffer) | WebM/Opus 오디오 청크 |
| 종료 | `{"type":"end"}` | 세션 종료 요청 |

### JavaScript 예시 (전체 구현)

```javascript
class MeetAILive {
  constructor({ onSessionCreated, onSegment, onEnded, onError }) {
    this.onSessionCreated = onSessionCreated;
    this.onSegment = onSegment;
    this.onEnded = onEnded;
    this.onError = onError;

    this.ws = null;
    this.mediaRecorder = null;
    this.meetingId = null;
  }

  async connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(
        'wss://backend-production-894a3.up.railway.app/api/v1/live/ws'
      );

      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error('WebSocket 연결 실패'));

      this.ws.onmessage = (e) => {
        const msg = JSON.parse(e.data);

        switch (msg.type) {
          case 'session_created':
            this.meetingId = msg.meeting_id;
            this.onSessionCreated?.(msg.meeting_id);
            break;
          case 'segment':
            this.onSegment?.(msg);
            break;
          case 'session_ended':
            this.onEnded?.(this.meetingId);
            break;
          case 'error':
            this.onError?.(msg.message);
            break;
        }
      };

      this.ws.onclose = () => {
        this.ws = null;
        this.mediaRecorder = null;
      };
    });
  }

  async startRecording() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

    // 지원 MIME 타입 자동 선택
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg']
      .find((t) => MediaRecorder.isTypeSupported(t)) ?? '';

    this.mediaRecorder = new MediaRecorder(stream, { mimeType });

    // 5초마다 오디오 청크 전송
    this.mediaRecorder.ondataavailable = async (e) => {
      if (e.data.size > 0 && this.ws?.readyState === WebSocket.OPEN) {
        const buffer = await e.data.arrayBuffer();
        this.ws.send(buffer);
      }
    };

    this.mediaRecorder.start(5000);
  }

  stop() {
    this.mediaRecorder?.stop();
    this.mediaRecorder?.stream.getTracks().forEach((t) => t.stop());

    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'end' }));
    }
  }

  getMeetingId() {
    return this.meetingId;
  }
}
```

### React 예시

```jsx
import { useRef, useState } from 'react';

export default function LiveRecorder() {
  const liveRef = useRef(null);
  const [meetingId, setMeetingId] = useState(null);
  const [segments, setSegments] = useState([]);
  const [recording, setRecording] = useState(false);

  const handleStart = async () => {
    const live = new MeetAILive({
      onSessionCreated: (id) => setMeetingId(id),
      onSegment: (seg) => setSegments((prev) => [...prev, seg]),
      onEnded: () => setRecording(false),
      onError: (msg) => console.error(msg),
    });

    await live.connect();
    await live.startRecording();
    liveRef.current = live;
    setRecording(true);
  };

  const handleStop = () => {
    liveRef.current?.stop();
    setRecording(false);
  };

  return (
    <div>
      <button onClick={handleStart} disabled={recording}>녹음 시작</button>
      <button onClick={handleStop} disabled={!recording}>녹음 종료</button>

      {meetingId && <p>Meeting ID: {meetingId}</p>}

      <div>
        {segments.map((seg, i) => (
          <div key={i}>
            <span>[{seg.speaker_label}]</span>
            <span>{seg.text}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
```

---

## 4. 에러 코드

| HTTP 상태 | 상황 |
|-----------|------|
| `400` | 잘못된 파일 형식 또는 파라미터 누락 |
| `404` | meetingId에 해당하는 회의 없음 |
| `500` | 서버 내부 오류 (STT 처리 실패 등) |

### 에러 응답 형식

```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "오류 내용"
}
```

---

## 테스트 페이지

브라우저에서 바로 테스트 가능합니다:

```
https://backend-production-894a3.up.railway.app/ws_test.html
```

API 문서 (Swagger UI):

```
https://backend-production-894a3.up.railway.app/swagger-ui/index.html
```
