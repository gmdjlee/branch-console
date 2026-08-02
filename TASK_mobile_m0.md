# TASK_mobile_m0 — 기반·보정 (M0)

- 선행: 없음(v3 첫 phase) · 게이트: **GM0** · 산출 버전: configs 0.3.0, golden_mobile.yaml v1
- 읽기: MASTER_PLAN §0·§2, ARCHITECTURE_SPLIT(D-15~D-21), BACKTEST_PLAN, CHANGES_V3, CLAUDE.md
- Plan council 면제: 본 TASK 자체가 v3 council 산출물이다. 단, BT-03 스윕 설계는 착수 전 `backtest-analyst`+`aaa-critic` 1라운드 검토를 거친다.

## 서브태스크

### MT0-01 SSOT v3 패치 + 모델 ID 적용·스모크 검증 (D-20)
- CHANGES_V3 §1~§2를 순서대로 적용: 결정 기록 append, statemachine `profiles:` 신설, 스테일 창 프로파일화, llm_tiering 정정.
- 모델 ID: D-20 §20.1 확정값(`claude-opus-5` / `claude-sonnet-5` / `claude-haiku-4-5-20251001`)을 statemachine.yaml에 기입한다. 그 뒤 **스모크 검증**: 모델 목록 API 1회 조회로 3개 ID 유효성 확인 + 각 ID로 최소 호출 1건(구조화 출력 파싱 성공·지연·토큰 기록). 실패 시에만 대안 검토. 위임: `data-verifier`(호출 ≤ 5회).
- 개발 시 모델 배정(D-20 §20.2) 적용: `.claude/agents/*` 8종의 `model` 필드를 전체 ID로 확정하고, `CLAUDE_CODE_SUBAGENT_MODEL`이 설정돼 있지 않은지 확인한다(설정돼 있으면 프론트매터가 무시됨).
- 완료: YAML 스키마 검증 테스트 green, D-15~D-21이 P0_DESIGN_DECISIONS.md에 append됨.

### MT0-02 engine_ref 구축 (BT-01 전반)
- `engine_ref/`에 transforms·severity·modifiers·composite(D-02)·statemachine(프로파일 주입형)을 순수 함수로 이식. 기존 아티팩트 시뮬 로직을 참조하되 configs에서만 파라미터 로드.
- 함정: K-05(naive datetime 금지), K-07(float64), 결측 분모 제외, D-12 abs 방향.
- 완료: `uv run pytest tests/test_engine_ref.py` — 경계·결측·modifier·양 프로파일 단위 테스트 green.

### MT0-03 픽스처 빌더 (BT-01 후반)
- `backtest/build_fixtures.py`: 9창 소급 수집(yfinance/FRED/pykrx), as-of 정렬, Parquet 저장, 창별 메타(앵커일·성격) 기록. 레이트리밋 K-03, 실호출 검증은 `data-verifier` 위임.
- 완료: 픽스처 스키마 테스트 green, 9창 파일 존재·결측률 리포트.

### MT0-04 골든 이관·확장 (BT-02)
- 완료: `pytest backtest/test_golden.py` 2케이스×2프로파일 green, `golden_mobile.yaml` 생성, D-14 상신 자료(2026-07 두 프로파일 결과) 작성.

### MT0-05 보정 스윕 (BT-03)
- sweep.yaml 설계 → `backtest-analyst` 실행 → 선정 규칙(BACKTEST_PLAN §BT-03)대로 0.3.0-rc 도출. F-04는 골든 무회귀 하드 제약 — 해가 없으면 "비활성 유지"를 결론으로 기록.
- 완료: BT_REPORT.md 스윕 절 + configs 0.3.0-rc 반영 + 골든 재실행 green.

### MT0-06 성능·해상도 리포트 (BT-04) + 게이트
- 9창 전량 재실행 → 수용 기준표(§6) 판정, F-06 대응안 3종 비교 시뮬 + 제안서.
- `docs/gates/GATE_GM0.md`: 판정표, 레지스트리 0.2.0→0.3.0 diff 요약, 사용자 결정 안건(① D-14 승격 ② F-04 활성/비활성 ③ F-06 대응안 채택 ④ 데모 픽스처 교체 여부).

## 완료 기준 (GM0)
`ruff`+`pytest` 전부 green / BT 수용 기준 전 항목 충족 / aaa-critic 전 서브태스크 PASS / GATE_GM0.md + **사용자 승인**.

## Advisor 시작 프롬프트 (Claude Code 메인 세션)
```
ultracode를 활성화하라. PROGRESS.md, docs/MASTER_PLAN.md, TASK_mobile_m0.md, docs/ARCHITECTURE_SPLIT.md,
docs/BACKTEST_PLAN.md, docs/CHANGES_V3.md, CLAUDE.md를 읽어라. 너는 Advisor다 — 직접 구현하지 말고
미완료 서브태스크를 의존성 순서(MT0-01 → 02·03 병렬 → 04 → 05 → 06)로 서브에이전트에 위임하라.
각 브리프에 대상 경로, D-xx 근거, K-xx 함정, 완료 테스트 명령을 포함하라. 각 완료 시
qa-verifier → aaa-critic 순서로 판정받고, PASS 전에는 PROGRESS를 체크하지 마라. FAIL은 사유와 함께 재위임하라.
configs/·contracts/·prompts/ 수정은 CHANGES_V3에 명시된 항목만 허용된다. 그 밖의 수정 필요가 보이면 멈추고 보고하라.
```
