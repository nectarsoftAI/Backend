#!/usr/bin/env python3
"""
STT 엔진 A/B 로그 분석기.

Railway 로그를 그대로 붙여넣으면 경로별 비교표를 만든다.
경로마다 후보 엔진과 지표가 다르므로 따로 집계한다.

  실시간 녹음(대면, 한 마이크)  Deepgram 단독 — 대안이 없어 비교 대상 아님
  온라인 회의(참가자별 스트림)   Deepgram vs OpenAI Realtime — 지연(ms)으로 비교
  파일 업로드(배치)             Deepgram vs AssemblyAI — RTF로 비교

지연은 평균이 아니라 분위수로 본다. 평균이 같아도 P95가 3배면 체감은 전혀 다르다.

사용법:
    python tools/ab_analyze.py railway.log
    railway logs | python tools/ab_analyze.py
"""
import math
import re
import sys
from collections import defaultdict

# [AB] engine=deepgram kind=final finalMs=760 engineMs=34 speaker=정용진 text="..."
RE_UTTER = re.compile(
    r"\[AB\]\s+engine=(?P<engine>\S+)\s+kind=(?P<kind>\S+)"
    r"(?:\s+finalMs=(?P<final>-?\d+))?"
    r"(?:\s+engineMs=(?P<engine_ms>-?\d+|n/a))?"
    r"(?:\s+firstMs=(?P<first>-?\d+))?"
)
# [AB배치] engine=deepgram totalMs=4200 audioSec=316.1 rtf=0.013 segments=30 speakers=2
RE_BATCH = re.compile(
    r"\[AB배치\]\s+engine=(?P<engine>\S+)\s+totalMs=(?P<total>\d+)\s+"
    r"audioSec=(?P<audio>[\d.]+)\s+rtf=(?P<rtf>[\d.]+|n/a)\s+"
    r"segments=(?P<segments>\d+)\s+speakers=(?P<speakers>\d+)"
)


def pct(values, p):
    """최근접 순위 분위수 — 표본이 적어도 왜곡이 적다"""
    if not values:
        return None
    s = sorted(values)
    i = math.ceil(p / 100 * len(s)) - 1
    return s[max(0, min(i, len(s) - 1))]


def fmt(v, unit="ms", nd=0):
    return "n/a" if v is None else f"{v:.{nd}f}{unit}"


def summarize(values, unit="ms", nd=0):
    if not values:
        return dict(n=0, p50="n/a", p90="n/a", p95="n/a", mx="n/a", avg="n/a")
    return dict(
        n=len(values),
        p50=fmt(pct(values, 50), unit, nd),
        p90=fmt(pct(values, 90), unit, nd),
        p95=fmt(pct(values, 95), unit, nd),
        mx=fmt(max(values), unit, nd),
        avg=fmt(sum(values) / len(values), unit, nd),
    )


def table(title, rows, cols):
    print(f"\n{title}")
    print("-" * 104)
    print(f"{'엔진/지표':28} | {'n':>4} | {'P50':>9} | {'P90':>9} | {'P95':>9} | {'max':>9} | {'평균':>9}")
    print("-" * 104)
    for label, s in rows:
        print(f"{label:28} | {s['n']:>4} | {s['p50']:>9} | {s['p90']:>9} | "
              f"{s['p95']:>9} | {s['mx']:>9} | {s['avg']:>9}")
    if cols:
        print("-" * 104)
        for c in cols:
            print(c)


def main():
    text = open(sys.argv[1], encoding="utf-8", errors="replace").read() if len(sys.argv) > 1 \
        else sys.stdin.read()

    online = defaultdict(lambda: defaultdict(list))  # engine -> metric -> [ms]
    batch = defaultdict(lambda: defaultdict(list))

    for line in text.splitlines():
        m = RE_UTTER.search(line)
        if m:
            e = m.group("engine")
            for key, grp in (("final", "final"), ("first", "first")):
                v = m.group(grp)
                if v is not None and int(v) >= 0:
                    online[e][key].append(int(v))
            ems = m.group("engine_ms")
            if ems and ems != "n/a" and int(ems) >= 0:
                online[e]["engine"].append(int(ems))
            continue

        m = RE_BATCH.search(line)
        if m:
            e = m.group("engine")
            batch[e]["total"].append(int(m.group("total")))
            if m.group("rtf") != "n/a":
                batch[e]["rtf"].append(float(m.group("rtf")))
            batch[e]["segments"].append(int(m.group("segments")))
            batch[e]["speakers"].append(int(m.group("speakers")))
            batch[e]["audio"].append(float(m.group("audio")))

    if not online and not batch:
        print("A/B 로그를 찾지 못했습니다.")
        print("  온라인: [AB] engine=... kind=final finalMs=... 형식")
        print("  배치  : [AB배치] engine=... totalMs=... rtf=... 형식")
        print("OPENAI_LATENCY_LOG=true 인지, 회의를 정상 종료했는지 확인하세요.")
        return

    if online:
        rows = []
        for e in sorted(online):
            rows.append((f"{e} / finalMs", summarize(online[e]["final"])))
            rows.append((f"{e} / firstMs", summarize(online[e]["first"])))
            rows.append((f"{e} / engineMs", summarize(online[e]["engine"])))
        notes = []
        engines = sorted(online)
        if len(engines) == 2:
            a, b = engines
            for metric, label in (("final", "finalMs"), ("first", "firstMs")):
                pa, pb = pct(online[a][metric], 50), pct(online[b][metric], 50)
                if pa and pb:
                    faster, ratio = (a, pb / pa) if pa < pb else (b, pa / pb)
                    notes.append(f"{label} P50: {faster} 가 {ratio:.1f}배 빠름  ({a}={pa}ms, {b}={pb}ms)")
        else:
            notes.append(f"엔진이 {len(engines)}개만 수집됨 — 비교하려면 양쪽 모두 측정 필요")
        table("■ 온라인 회의 — Deepgram vs OpenAI Realtime (지연 ms, 작을수록 빠름)", rows, notes)

    if batch:
        rows = []
        for e in sorted(batch):
            rows.append((f"{e} / RTF", summarize(batch[e]["rtf"], "", 3)))
            rows.append((f"{e} / totalMs", summarize(batch[e]["total"])))
        notes = []
        for e in sorted(batch):
            sp = batch[e]["speakers"]
            sg = batch[e]["segments"]
            au = batch[e]["audio"]
            notes.append(f"{e}: 파일 {len(sp)}건 | 화자 {min(sp)}~{max(sp)}명 | "
                         f"세그먼트 {min(sg)}~{max(sg)}개 | 오디오 {min(au):.0f}~{max(au):.0f}초")
        engines = sorted(batch)
        if len(engines) == 2:
            a, b = engines
            ra, rb = pct(batch[a]["rtf"], 50), pct(batch[b]["rtf"], 50)
            if ra and rb:
                faster, ratio = (a, rb / ra) if ra < rb else (b, ra / rb)
                notes.append(f"RTF P50: {faster} 가 {ratio:.1f}배 빠름  ({a}={ra:.3f}, {b}={rb:.3f})")
        notes.append("주의: 속도가 빨라도 화자 수/세그먼트가 크게 적으면 품질 저하를 의심할 것")
        table("■ 파일 업로드(배치) — Deepgram vs AssemblyAI (RTF, 작을수록 빠름)", rows, notes)


if __name__ == "__main__":
    main()
