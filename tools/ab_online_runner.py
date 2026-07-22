#!/usr/bin/env python3
"""
온라인 회의 STT A/B 실행 하네스.

브라우저로는 이 측정이 불가능하다 — 마이크에 실제 음성이 들어가야 세그먼트가 생기는데
자동화 도구는 소리를 낼 수 없다. 대신 TTS로 만든 WAV를 WebSocket에 직접 흘려보내
마이크를 우회한다. 입력이 매번 동일해 사람이 말하는 것보다 A/B 조건 통제에 유리하다.

회차마다:
  1) POST /api/v1/meetings/online          회의 생성
  2) WS 연결 (방장은 토큰 불필요)
  3) start_meeting
  4) PCM 250ms 청크를 실시간 속도로 전송   ← 빨리 밀면 endpointing/VAD가 깨져 측정 무의미
  5) end_meeting                           ← 이게 있어야 [AB요약] 로그가 나온다
  6) meetingId 기록 (사후 정리용)

엔진 전환(DEEPGRAM_ONLINE_ENABLED)은 Railway 대시보드에서 직접 해야 한다.
이 스크립트는 "지금 설정된 조건으로 N회" 만 담당한다.

사용법:
    python tools/ab_online_runner.py --wav ab_test_ko.wav --runs 1  --label gpt
    python tools/ab_online_runner.py --wav ab_test_ko.wav --runs 10 --label deepgram
"""
import argparse
import asyncio
import json
import time
import urllib.request
import wave
from pathlib import Path

import websockets

DEFAULT_BASE = "https://backend-production-894a3.up.railway.app"
DEFAULT_USER = "6169a9af-3709-4567-a9f2-4da234388f0b"  # 테스트 계정
CHUNK_MS = 250


def create_meeting(base: str, user_id: str, title: str, attempts: int = 4) -> str:
    """
    네트워크 순단(DNS 실패 등)에 재시도한다. 순단 한 번에 남은 회차가 연쇄로
    죽으면 장시간 실행이 통째로 날아가므로, 회차 단위로 버티는 편이 낫다.
    """
    last = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(
                f"{base}/api/v1/meetings/online",
                data=json.dumps({"title": title}).encode(),
                headers={"Content-Type": "application/json", "X-User-Id": user_id},
                method="POST",
            )
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read()).get("meetingId")
        except Exception as e:
            last = e
            if i < attempts - 1:
                time.sleep(2 ** i * 3)  # 3s, 6s, 12s — DNS 캐시 복구 여유
    raise last


def load_pcm(path: Path):
    """WAV → raw PCM. 16kHz mono 16bit가 아니면 측정이 어긋나므로 거부한다."""
    with wave.open(str(path), "rb") as w:
        if (w.getnchannels(), w.getframerate(), w.getsampwidth()) != (1, 16000, 2):
            raise SystemExit(
                f"16kHz mono 16bit WAV가 아닙니다: "
                f"ch={w.getnchannels()} rate={w.getframerate()} width={w.getsampwidth()*8}bit"
            )
        return w.readframes(w.getnframes())


async def run_once(base: str, user_id: str, pcm: bytes, idx: int, label: str) -> dict:
    title = f"AB-{label}-{idx:02d}"
    meeting_id = create_meeting(base, user_id, title)
    if not meeting_id:
        return {"idx": idx, "ok": False, "error": "meetingId 없음"}

    ws_url = (f"{base.replace('https://', 'wss://').replace('http://', 'ws://')}"
              f"/api/v1/online/ws/{meeting_id}?profileId={user_id}")
    chunk_bytes = 16000 * 2 * CHUNK_MS // 1000
    received = 0
    started = time.time()

    async with websockets.connect(ws_url, max_size=None, open_timeout=30) as ws:
        async def drain():
            """수신 메시지를 계속 비워 소켓이 막히지 않게 한다"""
            nonlocal received
            try:
                async for raw in ws:
                    try:
                        if json.loads(raw).get("type") == "transcript":
                            received += 1
                    except Exception:
                        pass
            except Exception:
                pass

        reader = asyncio.create_task(drain())
        await ws.send(json.dumps({"type": "start_meeting"}))
        await asyncio.sleep(0.3)  # 회의 시작 처리 여유

        # 실시간 속도 유지 — 벽시계 기준으로 다음 전송 시각을 잡아 드리프트를 막는다
        t0 = time.monotonic()
        for i in range(0, len(pcm), chunk_bytes):
            await ws.send(pcm[i:i + chunk_bytes])
            target = t0 + (i // chunk_bytes + 1) * CHUNK_MS / 1000
            delay = target - time.monotonic()
            if delay > 0:
                await asyncio.sleep(delay)

        await asyncio.sleep(2.0)  # 잔여 final 도착 대기
        await ws.send(json.dumps({"type": "end_meeting"}))
        await asyncio.sleep(1.5)  # endSession → [AB요약] 출력 여유
        reader.cancel()

    return {
        "idx": idx, "ok": True, "meetingId": meeting_id, "title": title,
        "elapsedSec": round(time.time() - started, 1), "transcripts": received,
    }


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--wav", required=True)
    ap.add_argument("--runs", type=int, default=1)
    ap.add_argument("--label", default="run", help="회의 제목 접두사 (gpt / deepgram 등)")
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--user", default=DEFAULT_USER)
    ap.add_argument("--out", default="ab_runs.json")
    args = ap.parse_args()

    pcm = load_pcm(Path(args.wav))
    audio_sec = len(pcm) / 32000
    print(f"오디오 {audio_sec:.1f}초 x {args.runs}회 = 최소 {audio_sec * args.runs / 60:.1f}분 소요")
    print(f"라벨={args.label} | 대상={args.base}\n")

    results = []
    for i in range(1, args.runs + 1):
        print(f"[{i}/{args.runs}] 실행 중...", end=" ", flush=True)
        try:
            r = await run_once(args.base, args.user, pcm, i, args.label)
        except Exception as e:
            r = {"idx": i, "ok": False, "error": f"{type(e).__name__}: {e}"}
        results.append(r)
        print("OK  meetingId=%s  자막 %s건  %.1fs" % (r["meetingId"], r["transcripts"], r["elapsedSec"])
              if r.get("ok") else f"실패 — {r.get('error')}")
        if i < args.runs:
            await asyncio.sleep(2)  # 회차 간 여유 — 연속 세션 생성으로 인한 순단 완화

    out = Path(args.out)
    prev = json.loads(out.read_text(encoding="utf-8")) if out.exists() else []
    prev.append({"label": args.label, "audioSec": round(audio_sec, 1), "results": results})
    out.write_text(json.dumps(prev, ensure_ascii=False, indent=2), encoding="utf-8")

    ok = sum(1 for r in results if r.get("ok"))
    print(f"\n완료: {ok}/{len(results)} 성공 → {out}")
    if ok:
        print("meetingId 목록 (정리용):")
        for r in results:
            if r.get("ok"):
                print(" ", r["meetingId"])


if __name__ == "__main__":
    asyncio.run(main())
