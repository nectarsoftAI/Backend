# MeetAI Frontend API Guide

## Base URL

```
https://backend-production-894a3.up.railway.app
```

---

## 목차

1. [STT — 파일 업로드 변환](#1-stt--파일-업로드-변환)
2. [회의 목록 조회 (Supabase REST)](#2-회의-목록-조회-supabase-rest)
3. [회의 상세 조회 (백엔드 REST)](#3-회의-상세-조회-백엔드-rest)
4. [실시간 라이브 STT (WebSocket)](#4-실시간-라이브-stt-websocket)
5. [온라인 회의 (WebSocket + WebRTC)](#5-온라인-회의-websocket--webrtc)
6. [에러 코드](#6-에러-코드)

---

## 1. STT — 파일 업로드 변환

오디오 파일을 업로드하면 AssemblyAI가 텍스트로 변환합니다.

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
  "engineUsed": "assemblyai",
  "segmentCount": 2,
  "transcripts": [
    {
      "speakerLabel": "spk_0",
      "speakerDisplay": "spk_0",
      "startSec": 0.0,
      "endSec": 4.2,
      "content": "안녕하세요 회의를 시작하겠습니다.",
      "confidence": 0.95,
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
    { method: 'POST', body: formData }
  );

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message);
  }
  return await response.json();
}
```

---

## 2. 회의 목록 조회 (Supabase REST)

회의 목록은 Supabase REST API를 프론트엔드에서 직접 호출합니다.
RLS 정책이 `auth.uid() = user_id`를 자동으로 검사하므로 **본인 회의만** 반환됩니다.

### Supabase 설정값

```
SUPABASE_URL  = https://gpnhfwtbmtvnexbxvdqi.supabase.co
SUPABASE_ANON = (환경변수 참고)
```

### Supabase RLS 정책 (최초 1회 SQL Editor에서 실행)

```sql
-- 본인 회의 또는 참여자로 등록된 회의만 조회
CREATE POLICY "users_select_accessible_meetings"
ON meetings FOR SELECT
USING (
  auth.uid() = user_id
  OR EXISTS (
    SELECT 1 FROM meeting_participants mp
    WHERE mp.meeting_id = meetings.meeting_id
    AND mp.profile_id = auth.uid()
  )
);
```

### Request

```
GET https://gpnhfwtbmtvnexbxvdqi.supabase.co/rest/v1/meetings
  ?select=*
  &order=created_at.desc
  &limit=6
  &offset=0

Headers:
  apikey: <SUPABASE_ANON_KEY>
  Authorization: Bearer <user-access-token>   ← Supabase 로그인 후 받은 JWT
```

### Response

Supabase REST 표준 배열 응답:

```json
[
  {
    "meeting_id": "550e8400-e29b-41d4-a716-446655440000",
    "user_id": "uuid",
    "title": "팀 스프린트 회의",
    "meeting_type": "DISCORD",
    "status": "COMPLETED",
    "duration_seconds": null,
    "meeting_date": "2026-07-01T10:00:00+00:00",
    "meeting_token": "a1b2c3d4e5",
    "created_at": "2026-07-01T10:00:01+00:00",
    "updated_at": "2026-07-01T10:30:00+00:00"
  }
]
```

> 전체 개수는 `Prefer: count=exact` 헤더를 추가하면 응답 헤더 `Content-Range`로 받을 수 있습니다.

### JavaScript 예시

```javascript
const SUPABASE_URL  = 'https://gpnhfwtbmtvnexbxvdqi.supabase.co';
const SUPABASE_ANON = '<SUPABASE_ANON_KEY>';

async function listMeetings(accessToken, { page = 0, size = 6 } = {}) {
  const offset = page * size;
  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/meetings?select=*&order=created_at.desc&limit=${size}&offset=${offset}`,
    {
      headers: {
        'apikey': SUPABASE_ANON,
        'Authorization': `Bearer ${accessToken}`,
        'Prefer': 'count=exact',   // 전체 개수 포함
      },
    }
  );
  if (!res.ok) throw new Error(await res.text());
  const total = res.headers.get('Content-Range')?.split('/')[1];
  return { meetings: await res.json(), total: Number(total) };
}

// 사용 예시
// accessToken은 Supabase Auth 로그인 후 data.session.access_token
const { meetings, total } = await listMeetings(session.access_token);
```

---

## 3. 회의 상세 조회 (백엔드 REST)

STT 변환 완료 후 회의 전체 정보와 대화록을 조회합니다.
트랜스크립트 + 요약이 조인되어 반환되므로 백엔드 API를 사용합니다.

### Request

```
GET /api/v1/meetings/{meetingId}
```

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
      "speakerLabel": "spk_0",
      "speakerDisplay": "spk_0",
      "startSec": 0.0,
      "endSec": 4.2,
      "content": "안녕하세요 회의를 시작하겠습니다."
    }
  ],
  "summary": {
    "keyPoints": ["예산 증액 논의"],
    "decisions": ["예산 10% 증액 승인"],
    "actionItems": ["김철수: 예산안 작성 (7/5까지)"],
    "keywords": ["기획", "예산", "일정"],
    "processingStatus": "COMPLETED",
    "processedAt": "2026-06-17T05:05:00Z"
  }
}
```

| 필드 | 타입 | 설명 |
|------|------|------|
| `meetingType` | string | `UPLOAD` \| `REALTIME` \| `DISCORD` |
| `status` | string | `PROCESSING` \| `LIVE` \| `COMPLETED` |
| `summary` | object \| null | LLM 요약 (처리 중이면 null) |

### JavaScript 예시

```javascript
async function getMeeting(meetingId) {
  const response = await fetch(
    `https://backend-production-894a3.up.railway.app/api/v1/meetings/${meetingId}`
  );
  if (!response.ok) throw new Error('회의를 찾을 수 없습니다.');
  return await response.json();
}
```

---

## 4. 실시간 라이브 STT (WebSocket)

마이크 오디오를 실시간으로 전송하면 5초마다 STT 결과를 받습니다.

### 플로우

```
1. POST /api/v1/live/sessions  →  meetingId 발급
2. wss://.../api/v1/live/ws/{meetingId}  WebSocket 연결
3. Binary 오디오 청크 전송 (5초 간격)
4. {"type":"end"} 전송 → 세션 종료
```

### 1단계 — 세션 생성

```
POST /api/v1/live/sessions
Content-Type: application/json
```

```json
{ "title": "팀 회의" }
```

**Response**
```json
{ "meetingId": "bb4eb646-d7df-4497-968c-e862b21b6b29" }
```

### 2단계 — WebSocket 연결

```
wss://backend-production-894a3.up.railway.app/api/v1/live/ws/{meetingId}
```

### 메시지 흐름

```
클라이언트                              서버
    |                                     |
    |──── WebSocket 연결 ───────────────>|
    |<─── session_ready ──────────────────|  연결 확인
    |                                     |
    |──── binary (오디오 청크) ──────────>|  5초마다 전송
    |<─── segment (STT 결과) ─────────────|  5초마다 수신
    |                                     |
    |──── {"type":"end"} ────────────────>|  녹음 종료
    |<─── session_ended ──────────────────|
```

### 서버 → 클라이언트 메시지

**session_ready** — 연결 직후 수신
```json
{ "type": "session_ready", "meeting_id": "bb4eb646-..." }
```

**segment** — STT 결과 (5초마다)
```json
{
  "type": "segment",
  "speaker_label": "spk_0",
  "start_sec": 0.0,
  "end_sec": 4.8,
  "text": "안녕하세요 회의를 시작하겠습니다.",
  "confidence": 0.95
}
```

**session_ended**
```json
{ "type": "session_ended" }
```

**error**
```json
{ "type": "error", "message": "오류 내용" }
```

### 클라이언트 → 서버

| 타입 | 형식 | 설명 |
|------|------|------|
| 오디오 | Binary (ArrayBuffer) | WebM/Opus 오디오 청크 |
| 종료 | `{"type":"end"}` | 세션 종료 요청 |

### JavaScript 예시

```javascript
class MeetAILive {
  constructor({ onReady, onSegment, onEnded, onError }) {
    this.onReady = onReady;
    this.onSegment = onSegment;
    this.onEnded = onEnded;
    this.onError = onError;
    this.ws = null;
    this.mediaRecorder = null;
    this.meetingId = null;
  }

  // 1단계: 세션 생성
  async createSession(title = '라이브 회의') {
    const res = await fetch(
      'https://backend-production-894a3.up.railway.app/api/v1/live/sessions',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title }),
      }
    );
    const { meetingId } = await res.json();
    this.meetingId = meetingId;
    return meetingId;
  }

  // 2단계: WebSocket 연결
  async connect() {
    if (!this.meetingId) throw new Error('createSession() 먼저 호출하세요.');
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(
        `wss://backend-production-894a3.up.railway.app/api/v1/live/ws/${this.meetingId}`
      );
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error('WebSocket 연결 실패'));
      this.ws.onmessage = (e) => {
        const msg = JSON.parse(e.data);
        switch (msg.type) {
          case 'session_ready': this.onReady?.(msg.meeting_id); break;
          case 'segment':       this.onSegment?.(msg); break;
          case 'session_ended': this.onEnded?.(this.meetingId); break;
          case 'error':         this.onError?.(msg.message); break;
        }
      };
    });
  }

  // 3단계: 녹음 시작
  async startRecording() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg']
      .find((t) => MediaRecorder.isTypeSupported(t)) ?? '';
    this.mediaRecorder = new MediaRecorder(stream, { mimeType });
    this.mediaRecorder.ondataavailable = async (e) => {
      if (e.data.size > 0 && this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(await e.data.arrayBuffer());
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
}

// 사용 예시
const live = new MeetAILive({
  onReady:   (id) => console.log('세션 준비:', id),
  onSegment: (seg) => console.log(`[${seg.speaker_label}] ${seg.text}`),
  onEnded:   (id) => console.log('종료, meetingId:', id),
  onError:   (msg) => console.error(msg),
});

await live.createSession('주간 회의');
await live.connect();
await live.startRecording();
```

---

## 5. 온라인 회의 (WebSocket + WebRTC)

여러 참여자가 실시간으로 화상/음성 회의를 진행하면서 STT 자막을 받습니다.

### 플로우

```
1. POST /api/v1/meetings/online  →  meetingId + token 발급 (방장)
2. token을 초대 링크로 다른 참여자에게 공유
3. wss://.../api/v1/online/ws/{meetingId}?token=xxx&profileId=xxx  연결
4. WebRTC offer/answer/ice_candidate 교환 (시그널링)
5. Binary 오디오 청크 전송  →  STT 자막 브로드캐스트
6. {"type":"end_meeting"}  →  회의 종료 (방장만)
```

### REST API

#### 회의 생성 (방장)

```
POST /api/v1/meetings/online
Content-Type: application/json
X-User-Id: {profileId}
```

```json
{ "title": "팀 스프린트 회의" }
```

**Response**
```json
{
  "meetingId": "550e8400-e29b-41d4-a716-446655440000",
  "token": "a1b2c3d4e5f6..."
}
```

> `token`을 초대 링크에 포함해 게스트에게 공유합니다.

#### 초대 토큰 재발급

```
POST /api/v1/meetings/{meetingId}/invite
X-User-Id: {profileId}
```

**Response**
```json
{ "meetingId": "...", "token": "새토큰..." }
```

#### 참여자 목록 조회

```
GET /api/v1/meetings/{meetingId}/participants
```

**Response**
```json
[
  {
    "participantId": 1,
    "profileId": "uuid",
    "role": "ADMIN",
    "canInvite": true,
    "canEdit": true,
    "canDelete": true,
    "canRunMeeting": true,
    "joinedAt": "2026-07-01T10:00:00Z"
  }
]
```

---

### WebSocket 연결

```
wss://backend-production-894a3.up.railway.app/api/v1/online/ws/{meetingId}?token={token}&profileId={profileId}
```

| 쿼리 파라미터 | 설명 |
|-------------|------|
| `token` | 회의 생성 시 발급된 초대 토큰 (방장은 생략 가능) |
| `profileId` | 본인의 UUID (Supabase auth user id) |

---

### 서버 → 클라이언트 메시지

**room_info** — 연결 직후 수신 (현재 방 상태)
```json
{
  "type": "room_info",
  "status": "PROCESSING",
  "participants": ["profileId-1", "profileId-2"]
}
```

**participant_joined** — 새 참여자 입장
```json
{ "type": "participant_joined", "profileId": "uuid", "role": "GUEST" }
```

**participant_left** — 참여자 퇴장
```json
{ "type": "participant_left", "profileId": "uuid" }
```

**meeting_started** — 회의 시작 (ADMIN이 start_meeting 발송 시)
```json
{ "type": "meeting_started" }
```

**meeting_ended** — 회의 종료
```json
{ "type": "meeting_ended" }
```

**kicked** — 본인이 강퇴당함
```json
{ "type": "kicked" }
```

**transcript** — STT 자막 (오디오 청크 처리 완료 시)
```json
{
  "type": "transcript",
  "profileId": "uuid",
  "speakerDisplay": "홍길동",
  "text": "안녕하세요.",
  "startSec": 0.0,
  "endSec": 3.2
}
```

**WebRTC 시그널링** — 중계 메시지 (from이 추가되어 수신)
```json
{ "type": "offer",         "from": "profileId", "sdp": "..." }
{ "type": "answer",        "from": "profileId", "sdp": "..." }
{ "type": "ice_candidate", "from": "profileId", "candidate": "..." }
```

**error**
```json
{ "type": "error", "message": "권한이 없습니다." }
```

---

### 클라이언트 → 서버 메시지

**WebRTC 시그널링**
```json
{ "type": "offer",         "to": "상대방profileId", "sdp": "..." }
{ "type": "answer",        "to": "상대방profileId", "sdp": "..." }
{ "type": "ice_candidate", "to": "상대방profileId", "candidate": "..." }
```

**회의 제어** (ADMIN 전용)
```json
{ "type": "start_meeting" }
{ "type": "end_meeting" }
{ "type": "kick", "profileId": "강퇴할profileId" }
```

**오디오 STT** — Binary (ArrayBuffer)로 전송
```javascript
ws.send(audioChunkArrayBuffer)  // WebM/Opus, 5초마다
```

---

### JavaScript 예시

```javascript
class MeetAIOnline {
  constructor({ profileId, onRoomInfo, onParticipantJoined, onParticipantLeft,
                onOffer, onAnswer, onIceCandidate,
                onMeetingStarted, onMeetingEnded, onKicked,
                onTranscript, onError }) {
    this.profileId = profileId;
    this.handlers = { onRoomInfo, onParticipantJoined, onParticipantLeft,
                      onOffer, onAnswer, onIceCandidate,
                      onMeetingStarted, onMeetingEnded, onKicked,
                      onTranscript, onError };
    this.ws = null;
    this.mediaRecorder = null;
    this.meetingId = null;
  }

  // 방장: 회의 생성
  async createMeeting(title) {
    const res = await fetch(
      'https://backend-production-894a3.up.railway.app/api/v1/meetings/online',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-User-Id': this.profileId },
        body: JSON.stringify({ title }),
      }
    );
    const { meetingId, token } = await res.json();
    this.meetingId = meetingId;
    this.token = token;
    return { meetingId, token };
  }

  // 게스트: 초대 링크에서 token 추출 후 연결
  setMeeting(meetingId, token) {
    this.meetingId = meetingId;
    this.token = token;
  }

  // WebSocket 연결
  async connect() {
    const url = `wss://backend-production-894a3.up.railway.app/api/v1/online/ws/${this.meetingId}`
              + `?token=${this.token}&profileId=${this.profileId}`;
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(url);
      this.ws.onopen = () => resolve();
      this.ws.onerror = () => reject(new Error('연결 실패'));
      this.ws.onmessage = (e) => this._handleMessage(JSON.parse(e.data));
    });
  }

  _handleMessage(msg) {
    const h = this.handlers;
    switch (msg.type) {
      case 'room_info':          h.onRoomInfo?.(msg); break;
      case 'participant_joined': h.onParticipantJoined?.(msg); break;
      case 'participant_left':   h.onParticipantLeft?.(msg); break;
      case 'offer':              h.onOffer?.(msg); break;
      case 'answer':             h.onAnswer?.(msg); break;
      case 'ice_candidate':      h.onIceCandidate?.(msg); break;
      case 'meeting_started':    h.onMeetingStarted?.(); break;
      case 'meeting_ended':      h.onMeetingEnded?.(); break;
      case 'kicked':             h.onKicked?.(); break;
      case 'transcript':         h.onTranscript?.(msg); break;
      case 'error':              h.onError?.(msg.message); break;
    }
  }

  // WebRTC 시그널링 전송
  sendOffer(toProfileId, sdp) {
    this._send({ type: 'offer', to: toProfileId, sdp });
  }
  sendAnswer(toProfileId, sdp) {
    this._send({ type: 'answer', to: toProfileId, sdp });
  }
  sendIceCandidate(toProfileId, candidate) {
    this._send({ type: 'ice_candidate', to: toProfileId, candidate });
  }

  // 회의 제어 (ADMIN 전용)
  startMeeting() { this._send({ type: 'start_meeting' }); }
  endMeeting()   { this._send({ type: 'end_meeting' }); }
  kick(profileId) { this._send({ type: 'kick', profileId }); }

  // 오디오 STT 스트리밍 시작
  async startAudioStream() {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg']
      .find((t) => MediaRecorder.isTypeSupported(t)) ?? '';
    this.mediaRecorder = new MediaRecorder(stream, { mimeType });
    this.mediaRecorder.ondataavailable = async (e) => {
      if (e.data.size > 0 && this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(await e.data.arrayBuffer());
      }
    };
    this.mediaRecorder.start(5000);
  }

  stopAudioStream() {
    this.mediaRecorder?.stop();
    this.mediaRecorder?.stream.getTracks().forEach((t) => t.stop());
  }

  _send(obj) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(obj));
    }
  }
}
```

### WebRTC 연동 예시 (참여자 입장 시)

```javascript
const peerConnections = {}; // profileId → RTCPeerConnection

const meeting = new MeetAIOnline({
  profileId: myProfileId,

  onParticipantJoined: async ({ profileId }) => {
    // 새 참여자 입장 → offer 생성
    const pc = createPeerConnection(profileId);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    meeting.sendOffer(profileId, offer.sdp);
  },

  onOffer: async ({ from, sdp }) => {
    const pc = createPeerConnection(from);
    await pc.setRemoteDescription({ type: 'offer', sdp });
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    meeting.sendAnswer(from, answer.sdp);
  },

  onAnswer: async ({ from, sdp }) => {
    await peerConnections[from]?.setRemoteDescription({ type: 'answer', sdp });
  },

  onIceCandidate: async ({ from, candidate }) => {
    await peerConnections[from]?.addIceCandidate(candidate);
  },

  onTranscript: (msg) => {
    console.log(`[${msg.speakerDisplay}] ${msg.text}`);
  },

  onMeetingEnded: () => {
    Object.values(peerConnections).forEach((pc) => pc.close());
  },
});

function createPeerConnection(profileId) {
  const pc = new RTCPeerConnection({ iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] });
  peerConnections[profileId] = pc;

  pc.onicecandidate = ({ candidate }) => {
    if (candidate) meeting.sendIceCandidate(profileId, candidate);
  };

  pc.ontrack = (e) => {
    // 원격 오디오/비디오 재생
    const remoteVideo = document.getElementById(`video-${profileId}`);
    if (remoteVideo) remoteVideo.srcObject = e.streams[0];
  };

  return pc;
}

// 시작
await meeting.createMeeting('스프린트 회의');  // 방장
await meeting.connect();
await meeting.startAudioStream();              // STT 스트리밍
```

---

## 6. 에러 코드

| HTTP 상태 | 상황 |
|-----------|------|
| `400` | 잘못된 파일 형식 또는 파라미터 누락 |
| `403` | 권한 없음 (초대 토큰 불일치, 권한 부족) |
| `404` | 해당 meetingId 없음 |
| `422` | 오디오에서 음성 없음 (무음) |
| `500` | 서버 내부 오류 (STT 처리 실패 등) |

### 에러 응답 형식

```json
{
  "code": "EX-013",
  "message": "권한이 없습니다."
}
```

| 에러 코드 | 설명 |
|----------|------|
| `EX-002` | 무음 감지 |
| `EX-003` | 지원하지 않는 오디오 포맷 |
| `EX-005` | STT 처리 실패 |
| `EX-009` | DB 저장 실패 |
| `EX-011` | 세션 없음 |
| `EX-012` | 회의 없음 |
| `EX-013` | 권한 없음 |

---

## API 문서 (Swagger UI)

```
https://backend-production-894a3.up.railway.app/swagger-ui/index.html
```
