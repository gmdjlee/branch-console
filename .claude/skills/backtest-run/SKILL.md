---
name: backtest-run
description: 백테스트 하니스(BT)를 실행·해석·보고할 때 사용하는 절차. 픽스처 생성, 리플레이, 스윕, 골든 판정, 리포트 규격을 정의한다. backtest-analyst와 회귀 게이트 실행의 공통 기준.
---
# 백테스트 실행 절차

1) 픽스처: `uv run python backtest/build_fixtures.py --window <id|all>` — 기존 파일 있으면 재수집 금지(캐시 우선, 강제 시 --force).
2) 리플레이: `uv run python backtest/run_replay.py --profile server_intraday|mobile_daily|both --config <registry>` → `backtest/results/metrics.json`.
3) 골든: `uv run pytest backtest/test_golden.py -q` — 2케이스 × 2프로파일. 실패 시 원인 규명 전 어떤 보정도 반영 금지.
4) 스윕: `uv run python backtest/run_sweep.py`(그리드는 sweep.yaml만) — 홀드아웃(2015-08, 2023-11) 자동 제외 확인.
5) 리포트: `uv run python backtest/report.py` → BT_REPORT.md. 머리에 "근사-PIT, C1에서 실측 확정" 문구 필수.
   본문 순서: 수용 기준표 판정 → 창별 타임라인 → 스윕 표(선정 근거) → 해상도(F-06) → 미결.
판정 규칙: 수용 기준(BACKTEST_PLAN §6) 미달 항목이 하나라도 있으면 결과는 FAIL이며, 보정 제안과 함께 Advisor에 보고한다.
