# M1 Plan Council — 공통 브리프

- 작성일: 2026-08-06 · 작성: Advisor · 절차: AAA_QUALITY_STANDARD §3
- 목적: **M1 모바일 코어**(TASK_mobile_m1.md)의 실행 계획 수립. TASK의 서브태스크(MT1-01~08)는
  세분화·보강할 수 있으나 **축소 불가**. 산출 계획은 그 자체로 실행 가능해야 한다(브리프·위임 단위까지).
- 게이트: GM1 = `./gradlew check` + JVM/계측 테스트 전부 green / BT-05 패리티 green / 해시·스냅샷
  테스트 green / 실기기 1일 스모크(확정 틱 1회 + 프리뷰 3회) / aaa-critic 전 항목 PASS / GATE_GM1.md + 사용자 승인.

## 1. 읽기 허용 파일 (이 목록 밖 탐색 금지)

- 계획 정본: `TASK_mobile_m1.md`, `docs/MASTER_PLAN.md`, `docs/ARCHITECTURE_SPLIT.md`(D-15~D-23),
  `docs/AAA_QUALITY_STANDARD.md`, `docs/BACKTEST_PLAN.md`(§BT-05·§6), `docs/gates/GATE_GM0.md`,
  `docs/CHANGES_V3.md`, `docs/P0_DESIGN_DECISIONS.md`(특히 D-02·D-04·D-06·D-08·D-12·D-16·D-17·
  D-18·D-20·D-22·D-23·D-25·D-26), `CLAUDE.md`, `PROGRESS.md`, `docs/reviews/REVIEW_M0.md`(신설 규율)
- SSOT·엔진·하니스: `configs/*.yaml`, `contracts/*.py`, `prompts/*.md`, `engine_ref/*.py`,
  `backtest/golden_mobile.yaml`, `backtest/golden_server.yaml`, `backtest/replay.yaml`,
  `backtest/run_replay.py`, `backtest/windows.yaml`, `backtest/fixture_schema.py`, `backtest/fixtures/`(구조만),
  `tests/test_engine_ref.py`(패리티 대상 의미론 참고)
- 외부 기보유 자산(읽기만): `D:\android_2025\kotlin_krx\` — `CLAUDE.md`, `PROGRESS.md`, `TASK.md`,
  `build.gradle.kts`, `settings.gradle.kts`, `src/` 구조, `KrxKt_Implementation_Specification.md`,
  `MIGRATION_MAP.md`(필요 범위)

## 2. 확정 사실 (GM0 이후 상태 — 계획이 반드시 반영할 것)

1. **registry 0.3.1-rc**가 현행이다: `kospi_drawdown.thresholds.extreme: 20.0` +
   `upgrade.ORANGE.or_any_extreme: true` 채택(GATE_GM0 후속 결정 2026-08-04). 승격 경로는
   0.3.1-rc → C1 실측 재확정 → 0.4.0. 모바일 assets에는 **0.3.1-rc를 굽는다**.
2. **D-26 이스케이프-이탈 짝지음은 엔진 의미론**이다(configs 키 없음, 레벨-로컬·reset 규칙 포함,
   engine_ref/statemachine.py + D-25 §4가 실행 가능한 정의). **Kotlin 엔진은 D-26 짝지음과
   or_any_extreme를 포함해 포팅해야 하며, BT-05 패리티 범위도 이를 포함한다**
   (GATE_GM0 §6의 "기본 경로 한정" 서술은 안건 3·5 채택 이전 기준 — 이제 프로덕션 경로다).
3. **확정 틱 시각**: TASK_mobile_m1·ARCHITECTURE_SPLIT의 16:20은 가설이고, BT-03이 하니스
   `replay.confirm_time_kst: 17:00`을 선정하며 "M1 실제 확정 틱 설계와 동시 재확인"을 조건으로
   달았다(AD-3b). **계획은 확정 틱 시각을 명시적으로 재확인·확정하는 결정 항목을 포함해야 한다**
   (수집 스케줄 `daily_kr 16:50`·`daily_us 07:20`과의 정합 논증 포함).
4. **G-4 (kr_cds_5y_delta)**: 모바일 수집 경로 없음. MT1-04f에서 `data-verifier` 실측 후
   (a) 수집 구현 / (b) 미수집 확정(+UI "미수집" 배지, GATE_GM1 기록) 중 하나를 Advisor에 상신.
5. **KRX 수집(사용자 확정 2026-08-02)**: Kotlin은 기보유 `D:\android_2025\kotlin_krx` 사용.
   KRX 2026 로그인 정책 대응 여부를 계획 단계 검증 항목으로 포함할 것. **야후 ^KS11 폴백은
   비채택**(사용자 결정 — 재제안 금지). 야후계 *글로벌* 지표의 Stooq 폴백(K-01·K-18)은 별개로 유효.
6. **M1은 LLM을 호출하지 않는다**(evidence·LLM 계층은 M2). 프리뷰도 LLM 자동 호출 금지(D-17).
7. **뉴스 축 2지표 enabled:false 유지**(G-2), **kr_cds는 optional:true**(분모 제외로 composite 무왜곡).
8. **D-23 커버리지 규율**은 MT1-07의 완료 기준 4항(TASK 원문)이 그대로 구속한다.
   carry-forward는 프리뷰 전용 코드 경로 — 확정 틱에서 호출 불가를 아키텍처 테스트로 강제.
9. **contracts 스냅샷**: 공유 스냅샷 파일 기준 양측(왕복) 검증이 요구되나 **Python 측 스냅샷
   생성·테스트도 현재 없다** — 계획에 Python 측 작업(스냅샷 생성기+테스트)을 포함할 것.
   contracts는 SSOT — 변경이 필요하면 "변경 제안"으로만.
10. **신설 규율(REVIEW_M0, 전 phase 적용)**: ① 파생 수치의 퇴화 입력 증인 테스트 의무
    ② 결측 귀속 서술의 형제 계열 증거 의무 ③ Worker 완료 보고에 `git status` 원문 첨부
    ④ qa-verifier의 보고-저장소 일치 선행 확인.
11. **모델 배정(D-20 §20.2)**: 계획·비평=claude-opus-5, 구현·검증=claude-sonnet-5.
    Kotlin 구현=kotlin-implementer, UI=ui-craftsman, 실측=data-verifier, 패리티 실행·분석=backtest-analyst.
12. 개발 환경: Windows 11, 콘솔 cp949(비ASCII 출력 주의), Python은 `uv run`, Android SDK는
    kotlin_krx가 빌드되는 로컬 환경 실재. 계측(connected) 테스트는 실기기 필요 — CI 기본은
    JVM(단위+Robolectric)으로 설계하고 계측 의존 항목을 명시 분리할 것.

## 3. 함정 (K-xx — 계획의 해당 서브태스크에 매핑 필수)

K-01(야후 비공식), K-03(KRX rate limit·휴장 XKRX), K-04(ECOS item_code 실측 — M0에서 실측 완료
여부는 sources.yaml 확인), K-05(FRED T+1·naive datetime 금지), K-07(Double 고정),
K-11(look-ahead), K-14(WorkManager 비정시 — 지연 허용+캐치업 멱등), K-15(OEM 절전 — 온보딩 안내,
틱 누락 이력 노출), K-16(assets 드리프트 — syncConfigs+SHA-256), K-17(키는 Keystore/
EncryptedSharedPreferences, 로그·백업 유출 금지), K-18(야후 차단 상시 가정 — Stooq 폴백).

## 4. 산출 형식 (각 인스턴스 공통)

자기 관점 파일 하나에 **완전한 전체 계획**을 한국어로 작성:

- A(아키텍처·의존성) → `docs/plans/M1_PLAN_A.md`
- B(데이터·정합성·백테스트) → `docs/plans/M1_PLAN_B.md`
- C(UX·운영·실패경로) → `docs/plans/M1_PLAN_C.md`
- D(런타임 데이터 경로 — 2026-08-06 라운드 3 구조 재분류에 따른 사용자 결정으로 신설) → `docs/plans/M1_PLAN_D.md`

필수 절: ① 서브태스크 분해(MT1-01~08 유지+세분화, 의존성 그래프, 병렬 가능 표시, 위임 대상
에이전트) ② 항목별 완료 기준(**실행 가능한 테스트 명령** — `./gradlew :app:testDebugUnitTest` 류)
③ 실측 선행 과업(무엇이 무엇을 블록하는지) ④ 리스크×K-xx 매핑과 완화 ⑤ 미해결 결정 목록
(Advisor/사용자 상신용, 권고안 포함) ⑥ SSOT 변경 제안(있다면 — 직접 수정 금지) ⑦ 예상 커밋
단위(p… 형식은 `m1-xx:` 프리픽스).

## 5. 계획이 반드시 답해야 하는 질문 (전 인스턴스 공통)

1. mobile/ Gradle 구성: 모듈 구조, 버전 카탈로그, ktlint+detekt, minSdk 29, JVM 테스트 vs 계측
   테스트 소스셋 분리, `./gradlew check`가 무엇을 포함하는가.
2. kotlin_krx 통합 방식(복사/컴포지트 빌드/소스 의존 중 택1과 근거)과 로그인 정책 대응 확인 절차.
3. syncConfigs: 복사 대상(configs 5종+prompts 2종), 해시 검증 테스트의 소스셋(계측? JVM?), 드리프트 차단.
4. contracts 미러: 스냅샷 파일 위치·형식, Python 생성기, Kotlin 왕복 테스트, 동결 일치 판정.
5. Room append-only: 스키마(observed_at/as_of/revision), 물리 강제 방식, as-of 쿼리, CSV 내보내기·Drive 백업 훅 범위.
6. collectors 6건(a~f): 각 어댑터의 실측 선행 과업, 폴백, 결측 처리, 픽스처 테스트 전략, 병렬 위임 그래프.
7. Kotlin 엔진·상태기계: engine_ref 대비 모듈 대응표, D-26·or_any_extreme 포함 범위, Double·KST 규율.
8. BT-05 패리티: 실행 형태(JVM 권장 여부), 픽스처 주입 방법, |Δcomposite|≤0.05·타임라인 일치·golden_mobile 일치의 검증 코드 위치.
9. 확정 틱 시각(16:20 vs 17:00) 결정 논증과 스케줄 정합(§2-3).
10. 캐치업 멱등 설계: 놓친 N일 순차 커밋, 이중 실행 방지, 실행 이력 테이블.
11. 프리뷰(D-17·D-23): carry-forward 경로 분리 아키텍처, coverage 계산 위치, 억제 UX.
12. 노티 3채널·기능판 홈: M1 범위(기능 검증용)와 M2(디자인) 경계.
13. 실기기 1일 스모크 절차(사용자 수행 항목 포함)와 GM1 증빙 수집 방법.

## 6. 관점 전담 (배타 — 자기 관점을 깊게, 다른 관점도 비워두지 않기)

- **A 아키텍처·의존성**: 모듈 경계, Gradle·빌드 재현성, kotlin_krx 통합, 계층 분리(수집/원장/엔진/
  UI), 아키텍처 테스트(carry-forward 격리 등), 의존성 버전 핀.
- **B 데이터·정합성·백테스트**: PIT·as-of·멱등·캐치업, 스냅샷·해시·패리티(BT-05), 확정 틱 시각
  논증, 결측·스테일 의미론이 engine_ref와 비트 단위로 같은가, 골든 무회귀 연결.
- **C UX·운영·실패경로**: AAA §2.2 실패 경로 전수(네트워크 단절/부분 결측/키 미설정/쿼터/중단
  캐치업), 노티·홈 기능판, 온보딩 최소 범위(키 입력·절전 예외 안내), 실기기 스모크 운영 절차,
  K-14·K-15 대응.
- **D 런타임 데이터 경로**: 앱 실운영의 데이터 흐름 사슬 전담 —
  `원장 스키마 → 조회 계약(계열·필드·범위·웜업) → 시계열 구성 → transform(원계열 전체, causal)
  → 가시성/스테일(visible_at 파생·worst-of-inputs·등호 규약) → Tick 조립(severity·modifiers·
  composite·distinct_axes·any_crit·any_extreme) → fold(전량, tick_input 동결)`.
  각 단계를 정본 코드(file:line)와 1:1 매핑해 이식 대상 명세로 확정하고, 확정 틱·캐치업·프리뷰
  세 경로가 이 사슬을 어떻게 공유·분기하는지, 웜업/백필(windows.yaml padding_days 참조),
  단계별 증인 테스트를 규정한다. 정본: `backtest/run_replay.py`(특히 build_known_series·
  lookup_known·combined_visibility_utc·is_stale_check), `engine_ref/*.py`.
