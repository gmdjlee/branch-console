# REVIEW_M0 — M0 phase 비평 판정 로그 (AAA §5)

형식: 서브태스크 · 판정 주체 · 등급 · 사유 요지 · 해소 커밋. GM0 게이트 리포트가 본 로그를 인용한다.

## MT0-01 — CHANGES_V3 §0~§5 적용 + §3.5 모델 배정 (스모크 검증 제외 분)

### 라운드 1 (2026-08-02)
- **qa-verifier: PASS.** 각 절 검증 커맨드·ruff·pytest 전부 green, SSOT 값 복제 0건.
  특이사항 3건 기록(단일 루트커밋으로 diff 검증 불가 / CHANGES_V3 예시 커맨드 인코딩 미지정 / contracts datetime tz 검증자 부재는 기존 상태).
- **aaa-critic: FAIL.** 결함 4건:
  1. (주요) CLAUDE.md §0 구 별칭 배정 문구가 신규 D-20 §20.2 블록과 모순 — 배정 규율 실효 무력화.
  2. (중) `ruff format --check` 실패 3파일(contracts/snapshot.py, tests/test_configs_schema.py, TASK_analogue_library.md) — CLAUDE.md "ruff (lint+format)" 선언 위반. 부수: 룰셋 미고정.
  3. (경미) statemachine.yaml `upgrade:` 주석이 이동으로 사어가 된 `sustain_ticks` 참조.
  4. (중) "기존 값 그대로" 주장이 부모 없는 단일 커밋 탓에 검증 불가(특히 stale 창 4값은 저장소 문서에 기록 전무).
- 명세 밖 산출물 4종(스캐폴딩/contracts UP045 수정/D-22·23 포인터/git init) 정당성: 전부 인정(②는 "결과적 정당, 경로는 차선", ④는 "원칙 정당, 실행 결함 → 결함 4").

### 해소 (커밋 `5e190a9`)
1. CLAUDE.md 11행을 D-20 §20.2 고정 문구로 교체. 별칭 단독 배정 지시 잔존 0건.
2. `ruff format .` 적용(39 files formatted). contracts/snapshot.py 스키마 불변은 포맷 전후 전 BaseModel
   `model_json_schema()` diff 공백으로 증명(비평가가 `git show 348f1b9:` 기준 독립 재증명). 룰셋은 기본값
   유지 + uv.lock 핀(ruff 0.16.1)이 재현성 담당 — 비평가 수용.
3. 사어 주석 갱신(`promote_sustain_ticks(profiles.*)` 참조), 키·값 무변경.
4. `docs/journal/2026-08-02_MT0-01_baseline_attestation.md` 신설 — Advisor 편집 전 열람 원문 물질화.
   비평가 검증: 대조표 7행 중 6행 저장소 내 독립 교차검증 성립, 잔여 1행(stale 창 4값)은 가설 지위·BT-03
   보정 경로 명시로 수용("달성 가능한 최대 충실도").

### 라운드 2 (2026-08-02)
- **qa-verifier: PASS.** diff 범위가 결함 해소 4건+증빙 1건에 국한, format/lint/test 전부 green.
- **aaa-critic: PASS.** 결함 4/4 해소 확인(전 항목 직접 재실행). 잔여(결함 아님):
  - O-1: ruff format의 .md 코드펜스 재작성 — Advisor 결정 ⓑ 채택(`extend-exclude = ["*.md"]`, 본 로그와 같은 커밋).
  - O-2: 본 로그 기록으로 이행.
  - O-3: `registry_version: 0.1.0` vs 문서상 "현행 0.2.0" 불일치 — 기존 결함, MT0-05 착수 전 해소 필요
    (MT0-06 게이트의 "0.2.0→0.3.0 diff" 기준점 부재 문제).

### 라운드 3 — 스모크 (b)(c) 재검증 (2026-08-02, 크레딧 충전 후)
- **qa-verifier: PASS.** 변경 파일 집합 일치(V0), 게이트 green(V1·V2), 증빙 응답 원문↔저널 §6 표
  message id·토큰 3/3 일치(V7), 스키마 부합 파싱 3/3, 키 미노출(V8), configs 무수정(V9).
- **aaa-critic: CONDITIONAL(경미 1) → 해소 확인 후 PASS.** 증빙 원문에서 (a)(b)(c) 전부 독립
  재도출(3/3). 경미 F2-1: 저널 §3 "FAIL (부분)" 판정이 무조건 노출되고 대체 표시가 §3 말미 1행뿐 —
  최상단 배너(최종 판정 PASS·§1~§5는 1차 기록) 추가로 해소, 2라운드 diff 확인 완료.
  잔여 관찰 O-C: 증빙 원본이 세션 임시 디렉토리 경로 인용 — 결정적 (c) 증빙은 저널에 인라인돼 수용,
  이후 실측 스모크는 증빙을 docs/journal/ 하위에 커밋 권고.

### 상태 (MT0-01)
- **완료(PASS).** 적용분(라운드 1~2) + 스모크 (a) 목록 3/3 · (b) 실호출 3/3 HTTP 200 · (c) 구조화
  출력 파싱 3/3(라운드 3, 저널 §6) — D-20 §20.1 완료 기준 전량 충족.
  이월: O-3(registry_version 0.1.0 vs 문서 "0.2.0")는 MT0-05 착수 전 해소 조건 유지.

## D-24 — LLM 공급자 옵션화(Gemini 병기) (사용자 직접 지시, M0 부수 작업)

### 라운드 1 (2026-08-02)
- **qa-verifier: PASS.** 어서션 green, D-20 확정값 무변경(diff 1 hunk), format/lint/test green, SSOT 하드코딩 0건, 후보 ID 3곳 문자 동일.
- **aaa-critic: CONDITIONAL** (경미 2건). 외부 사실 표본 재실사(ai.google.dev 직접 조회) 결과 검토서 주장 전부 일치 — 사실관계 결함 0건.
  1. (경미) "기본 비활성" 불변식(provider=anthropic, gemini_api.enabled=false)에 값 가드 부재.
  2. (경미) preview ID(gemini-3.1-pro-preview)를 SSOT에 상주시키면서 재검증 주기(분기)가 폐기 창(2주)보다 긺 — 문서 내부 모순.
  - 관찰: O-1 cost_controls 캐싱 미기재 사유 없음 / O-2 구조화출력+도구 동시 조합 자체가 preview 기능 / O-4 PROGRESS·본 로그 미기록.

### 해소
1. 테스트에 값 가드 2건 추가(provider=='anthropic', enabled is False — 비평가가 모드 플래그로 하드코딩 예외 인정).
2. **ⓐ안 채택**: scenario_report의 gemini_model을 SSOT에서 제거, 후보 기록은 검토서 §1이 원본. D-24·검토서 동시 갱신.
- O-1: cost_controls 주석 1줄 / O-2: 검토서 §5-① 스모크 항목에 preview 의존 명시 / O-4: PROGRESS·본 로그 기록(이 절).

### 라운드 2 — 해소 확인 (2026-08-02, 커밋 179637a)
- **qa-verifier: PASS.** diff 범위 7파일 국한, 값 가드 낙제 재현 확인(provider 변형 시 pytest FAILED), D-20 확정값 무손상, 게이트 green, "preview 미상주" 서술 3곳 일치.
- **aaa-critic: PASS (D-24 종결).** 가드 작동 독립 재현(스크래치 변형), configs 내 preview ID 잔존 0건(grep — 히트는 문서 3곳뿐), 주기 모순 해소(§5-④ 즉시 재조회) 확인. 잔여 1건은 본 로그의 헤딩 계층(D-24 산출물 아님) — 즉시 수정, 재판정 불요.

### 라운드 3 — 철회 (2026-08-02, 사용자 지시)
- 사용자 지시("gemini 사용은 제외해주세요")로 **D-24 철회**. SSOT 원복: configs 2파일·테스트 가드가
  D-24 도입 직전 커밋(db83695)과 byte 동일(qa V4, 비평가 독립 재현). P0_DESIGN_DECISIONS D-24에
  철회 기록(원문 존치), 검토서 최상단 철회 배너. 검토서 본문은 기록 보존.
- **qa-verifier: PASS**(V0~V9). **aaa-critic: CONDITIONAL(경미 2) → 해소 확인 후 PASS(종결).**
  F1-1: PROGRESS.md:18이 철회 미표기 → 철회 추기로 해소. F1-2: D-24 표제 상태 표기 누락 +
  각주의 테스트 가드 제거 미언급(본문 "스키마 테스트가 가드한다" 사실 오류 잔존) → 표제 "→ 철회"
  부기·각주 확장으로 해소.

### 상태 (D-24)
- **철회 종결.** 옵션 자체가 SSOT에서 제거됨 — 활성화 경로 없음. Gemini 재도입은 신규 결정으로만
  가능하며, 후보·비용·전환 조건 기록은 검토서(docs/journal/2026-08-02_gemini_option_review.md)가 원본.
