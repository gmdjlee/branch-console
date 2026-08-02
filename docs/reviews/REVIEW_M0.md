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

## MT0-02 — engine_ref 구축 (BT-01 전반)

### 라운드 1 (2026-08-02)
- **qa-verifier: PASS.** ruff 0 경고, format clean, 테스트 41건 green + 전체 60건 무회귀, 커버리지
  100%(297/297), SSOT 리터럴 0건(임계·가중치·윈도우 전부 configs 파싱), K-05(naive 거부)·K-07(float64
  구현)·D-12 양방향·경계 등호·D-23 수치 예 확인. 관찰 3건(sustain 해석 모호점, severity 최대값 중복,
  "abs"→abs_ 디스패치는 후속 몫).
- **aaa-critic: FAIL.** 결함 9건(차단 2·중대 4·경미 3):
  - D-1 (차단) promote sustain을 "상위 아무 레벨 충족 연속"으로 구현 — statemachine.yaml 주석 문언
    ("조건 충족이 N틱 연속")과 불일치, [AMBER조건, RED조건] 2틱으로 RED 직행(RED 조건은 1틱만 성립).
    해당 동작 단정 테스트 0건.
  - D-2 (차단) min_dwell_ticks 실효 체류가 명목값+1 (오프바이원). 미문서·미단정, BT-03 스윕 오귀인 위험.
  - D-3 (중대) 전 지표 결측 → composite 0.0 → GREEN. 수집 전면 장애가 "이상 없음"과 구별 불가.
    coverage 미노출(D-23 §23.3 위반 소지).
  - D-4 (중대) parse_call_kwargs 인자 스코프 미구분(중첩 kwargs 누출, "zscore"가 "neg_zscore(" 내부에
    오매칭). 15지표 전수 파싱 테스트 부재(구현 보고와 달리 스위트에 없음).
  - D-5 (중대) float64 고정 단위 테스트 0건(§2.1 명시 요구). astype 전부 제거해도 green.
  - D-6 (중대) mobile_daily 강등·재승격(cooldown=0) 경로 테스트 0건 — "양 프로파일" 완료 기준 절반 충족.
  - D-7 (경미) @cache dict 별칭 오염(frozen dataclass가 캐시 참조 공유) / D-8 (경미) 괄호 불균형 시
    IndexError(타 파서는 ValueError 통일) / D-9 (경미) modifier 증분 "+= 1"이 룰 문자열 미파싱.
  - 관찰 O-1(빈 규칙=무조건 충족) O-2(prev_close 0 무가드) O-3(NaN 침묵 통과 미테스트)
    O-4(인과성 테스트 부재) O-5(.coverage 미추적·gitignore 누락).
- **Advisor 조치**: 의미론 3건(D-1·D-2·D-3)을 **D-25로 물질화**(P0_DESIGN_DECISIONS.md — 레벨별 연속
  충족 / 명목=실효 dwell / 분모 0은 score None+동결). 결함 전건+관찰 5건 수정 범위로 재위임.

### 라운드 2 (2026-08-02)
- **qa-verifier: PASS.** 게이트 재실행(57테스트 green, 커버리지 100%(339/339), 전체 무회귀), 결함
  D-1~D-9·O-1~O-5 전건을 기계 재현으로 해소 확인(라운드 1 재현 케이스 직접 실행 포함), SSOT 리터럴
  유입 0, diff 범위 국한(engine_ref/·tests/test_engine_ref.py·.gitignore).
- **aaa-critic: FAIL** (중대 4·경미 1). **구현은 D-25와 전건 일치**(독립 참조 모델 차등 테스트 5,600
  시퀀스 불일치 0 + 변이 테스트로 검증)하나, D-25 조항이 테스트로 고정되지 않음 — 위반 변이가
  스위트(57건)를 전부 통과:
  - F2-1 (중대) §1 "연속 충족" 무고정 — 스트릭 리셋 제거(누적화) 변이 생존. 비연속 단발 RED 조건
    3틱으로 RED 발화하는 오탐 유형(라운드 1 D-1과 동종)이 재도입 가능.
  - F2-2 (중대) §1 cooldown 스트릭 정지·리셋 무고정 / F2-3 (중대) §1 전이 후 스트릭 유지 무고정 /
    F2-4 (중대) §3 동결 틱의 dwell·demote·cooldown 카운터 불변 무고정(5개 중 2개만 단정) /
    F2-5 (경미) 강등 스트릭 연속 리셋 무고정.
  - 잔여 관찰 O2-1~O2-8 (or_any_crit 단독 룰 잠재 / 초기 국면 dwell 전제 / pct_change_5d·gated dtype
    미단정 / modifier 파서 2종 ValueError 규약 미적용 / 전수 파싱 단정 약함 / sqrt(252) 리터럴 /
    미커밋 트리 경계 문제 / skip_levels:false 분기 미실행).
  - 2회 연속 FAIL이나 전부 테스트 추가로 닫히는 기계적 범위 — 구조 문제 재분류 비해당(비평가 판단).
- **Advisor 조치**: F2-1~F2-5 변이 재현 절차를 첨부해 재위임. 완료 기준에 "D-25 조항별 위반 시
  실패하는 증인 테스트"를 명시. O2-3·O2-4·O2-5·O2-8 동일 라운드 처리 지시.

### 라운드 3 (2026-08-02)
- **qa-verifier: PASS.** 게이트 green(67테스트, 커버리지 100%(343/343), 전체 89건), 변이 표본 3종
  (F2-1·F2-4a·F2-5) 독립 적용→증인 실패→원복 md5 일치, O2-3/4/5/8 확인, 코드 수정 범위
  registry.py 파서 2건(+4문, 커버리지 통계 정합) 국한 확인.
- **aaa-critic: PASS (MT0-02 종결).** F2 변이 7종(F2-4는 a/b/c 분해) 독립 하네스로 전건 재현 —
  **7/7 KILLED, kill 특이도 1:1**(각 변이가 자기 증인 하나만 죽임). 증인 전부 공개 API 타임라인 단정
  + config 유도값(SSOT 리터럴 0). statemachine.py는 라운드 2와 바이트 동일, 루브릭 §2.1·§2.2·§2.3
  전항 충족. 3회 연속 FAIL 에스컬레이션 미도달(R1 FAIL→R2 FAIL→R3 PASS).
- **인계 관찰**(등급 비반영):
  - **O3-1 (중요, BT-03 인계)**: min_dwell_ticks가 현행 두 프로파일 값(min_dwell < demote_below)에서
    증명 가능하게 무효 — server 실효 최소 체류는 7틱. → Advisor가 D-25 §2에 부기 완료(BT-03 스윕
    규율 포함).
  - O2-1(or_any_crit 단독 룰 무조건 충족)·O2-2(초기 국면 dwell +1 계상, 비-GREEN 초기 국면 주입 시
    표면화 — MT1-06 캐치업 유의)·O3-2(usdkrw 파서 warn/crit 위치 배정, 대소 무검증)·O2-6(sqrt 252
    규약 상수) 잠재 존치.
  - O2-7: 미커밋 트리로 라운드 간 기계 diff 불가(스냅샷 대조로 대체) → 본 라운드 후 서브태스크
    단위 커밋으로 해소.
  - O3-3(docs/TASK_mobile_m0.md 부재 주장)은 **오판정** — 저장소 전 참조가 루트 경로
    `TASK_mobile_m0.md`이고 파일 실존(grep 확인). 조치 불요.

### 상태 (MT0-02)
- **완료(PASS).** engine_ref 6모듈 + 증인 포함 67테스트, D-25 의미론 물질화·변이 고정 완료.
  이월: O2-1·O2-2·O3-2는 잠재 관찰(현행 SSOT에서 무해) — 관련 phase(BT-03, MT1-06)에서 재확인.
