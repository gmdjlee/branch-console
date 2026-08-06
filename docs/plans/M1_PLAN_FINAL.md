# M1_PLAN_FINAL — plan council 병합 확정본

- 작성: 2026-08-07, Advisor(메인 세션) · 절차: AAA_QUALITY_STANDARD §3-4(병합) · 상태: **사용자 승인 완료(2026-08-07, §3-5) — 착수**
- 승인 시 사용자 확정 3건: ① 병합본 일괄 승인·착수 ② **KIS 미보유 → MT1-04e는 M2 이연**(M-28 확정 — 프리뷰는 야후·KRX 경로로만 구성) ③ **스모크는 필수만**(확정 1회+시계 조건 상이 프리뷰 3회, ≈25분) + S9 멱등 승격(확장 4종 비채택 — C U-15)
- 판정 이력: `docs/reviews/REVIEW_M1.md` — 7라운드, 4안 전원 PASS(2026-08-07), 구조 재분류 2회(관점 D 신설 / M-43b 전수표) 모두 처방 작동
- 구성: 본 문서(병합 결정·정규 매핑·서브태스크 합집합) + 부속 계획 4안(정규 참조 — 아래 §2 매핑이 유효 절을 지정한다)
  - `M1_PLAN_A.md`(아키텍처) / `M1_PLAN_B.md`(데이터·정합성) / `M1_PLAN_C.md`(UX·운영) / `M1_PLAN_D.md`(런타임 데이터 경로)
- TASK_mobile_m1.md의 서브태스크는 **축소 없이** 본 문서가 세분화·보강한다(축소 불가 규율 준수).

---

## 1. 병합 결정 (Advisor 확정)

4안 수렴 완료 20항(REVIEW_M1 라운드 7)은 결정 대상이 아니며 그대로 채택한다. 아래는 갈렸던 항목의 확정이다.
근거가 비평가 권고와 동일한 경우 "권고 채택"으로 표기한다(전 항목 비평가 검증 완료).

### 1.1 필수 결정 11건

| # | 결정 | 확정 | 근거 |
|---|---|---|---|
| M-01 | 모듈 레이아웃 | **3모듈 `:engine` / `:krx` / `:app`** (A) | 권고 채택. 최소 구조, D가 접두사 교체만으로 사슬 명세 불변임을 명시 |
| M-03 | assets 물리 위치 | **`build/generated/ssot-assets/` + `verifyNoCheckedInAssets` 가드** (A) | 권고 채택. 편집 가능한 사본이 없으면 K-16 드리프트가 구조적으로 불가능 |
| M-09b | 프리뷰 억제 임계 키 | **`configs/indicators.yaml` `engine.preview_coverage_min: 0.80`** | 권고 채택(3:1) |
| M-17b | 캐치업 상한 키 | **`configs/statemachine.yaml` `profiles.mobile_daily.catchup_max_ticks: 20`** | 권고 채택(3:1) |
| M-34 | 캐치업 절단 표현 | **`composite=NULL` 동결 행 + `gap_reason`** (A) | 권고 채택. statemachine.py:120-124 `continue`로 카운터 귀결이 틱 부재와 완전 동치(비평가 추적 확인), 공백이 원장에 남아 감사 가능 |
| M-39 | 프리뷰 나이 산식 | **실경과 `now − visible_at`** (A·B·D) | 권고 채택(3:1). 결정 근거 = B 논거(경보 시스템 비대칭 비용 — 비확정 경로의 오류는 억제 쪽이 안전). C안(24h 스냅)은 내부 건전 판정이나 밤 시간대 최대 7h 관대 — 기각 사유로 기록 |
| M-42 | 웜업 창 | **조합: 틱당 조회는 지표별 `requiredRows` 파생(B, 리터럴 0·최대 272), 백필 범위는 `engine.warmup_padding_days: 550`** | 권고 채택. 550 달력일 ≈ 371~383 거래일 ≥ 272(충분성 비평가 검산). 행 기반은 휴장 밀집 면역·레지스트리 자동 추종, 550은 하니스 `padding_days`와 동수라 드리프트 검증 테스트 가능 |
| M-43 | 프리뷰 판별자 | **`observation.lane INTEGER` 0=확정/1=프리뷰** + B의 backfill 지위 문서 흡수(캐치업·초기 백필 = 종가 소스 → lane 0) | 권고 채택(3:1). 레인 3종 이상 필요 시 enum 승격(D ponytail 주석) |
| M-44 | SQLite 윈도 함수 | **미사용 확정**(상관 서브쿼리/정렬+fold). MT1-00f 실측은 인덱스 플랜·성능 확인용으로만 유지 | 권고 채택. 미검증 플랫폼 기능에 정합성 경로를 걸지 않는다 |
| M-45/46 | 부트스트랩 게이트·`WARMUP_INSUFFICIENT` | **D안 채택**: 웜업 미충족 시 확정 틱 미생성 + MISSING과 구분되는 3번째 상태 | 권고 채택. 미구분 시 설치 첫날 전 지표 결측이 D-25 §3 동결로 흡수돼 정상처럼 보임 |
| M-49 | `tick_input` 감사 컬럼 | **합집합**: fold 입력 4열(수렴) + `coverage`·`registry_version` + A(`gap_reason`·`frozen_at`) + B(`fired_axes`·`visible_at_by_indicator`·`is_catchup`) + C(`raw_coverage`→`coverage`와 통합·`pit_quality`) + D(`warmup_status_json`) + `severities_json`(M-43b-iii) | 권고 채택. 상호 배타 없음 |

### 1.2 이월(carry-forward) 하위 결정 3항 — M-43b 부속

| # | 확정 | 근거 |
|---|---|---|
| i | 이월 원천 조회에 **`WHERE composite IS NOT NULL`** (마지막 *평가된* 틱) | 권고 채택(A). M-34로 gap 행이 NULL로 존재하므로 필터 없으면 gap 직후 프리뷰에서 빈 맵 이월 |
| ii | **이월 깊이 1 명문화** (지표별 walk-back 금지 — D-23 §23.3-1 "직전 확정값" 단수. 상향은 D-23 개정 제안과 함께만) | 권고 채택 |
| iii | 컬럼명 **`severities_json TEXT NOT NULL`** + 결측 지표 `null` 명시 기록(A 규정). C의 `indicator_detail`은 감사 컬럼으로 병존 | 권고 채택(2:1:1) |

### 1.3 부속 결정 (라운드 1~5에서 수렴·권고 확정된 잔여 항목)

| 항목 | 확정 |
|---|---|
| M-05/06 확정 틱 시각 | **17:00 KST 가설 채택 + MT1-00g 실측(사전등록 판정 규칙)으로 확정**(AD-3b 이행). SSOT 위치: `statemachine.yaml` `profiles.mobile_daily.confirm_time_kst`(실측 확정 후 기입, A C-1) |
| M-11b BT-05 범위 | **9창 × mobile_daily + 합성 config 증인**(골든 2창은 extreme 20.0·D-26 무발화라 불충분 — B D-B8) + L0~L6 계층 판정(C §9-C) + BT-05 커버 경계는 사슬 ③~⑦, ①②는 MT1-05k e2e 보완(D §2.10) |
| M-14 확정 틱 저커버리지 | **의미론 무변경**(하한 도입 없음, 재시도·표기 강화 — B D-B3(a)). C1 이관 |
| M-16 계약 와이어 필드명 | **`by_alias=True`("schema") 고정**(B D-B6, contracts 무수정) |
| M-19 VKOSPI | **(c) 수집·저장하되 v1 판정 입력은 실현변동성 폴백 유지**(3안 수렴), C1에서 서버와 동시 전환 |
| M-20 G-4 kr_cds | **(b) 미수집 확정 방향 + MT1-00d 실측 후 최종 상신**(실측이 "정적 GET+정규식 1개·3일 연속 성공"이면 (a) 재검토 — A U-4). (b) 확정 시 UI "미수집" 배지 + GATE_GM1 기록 |
| M-21 M1 UI 위임 | **kotlin-implementer**(기능판 — AAA §2.4·§2.5는 M2 소관이라 ui-craftsman 과업 정의 미성립). M2에서 ui-craftsman |
| M-22 run_log purge | **180일 purge 허용하되 판정 관련 테이블(observation·tick_input) 제외** — append-only 물리 강제는 lake·tick_input, run_log는 수명 분리(C U-8 + A 멱등 근거 보존) |
| M-24 휴장일 무커밋 | **D-27 신설 제안**(C U-11) — SSOT 변경 제안에 포함 |
| M-27 KRX 자격증명 | 온보딩 입력 + EncryptedSharedPreferences/Keystore(K-17), 동시 로그인 CD011 고지(B D-B9) |
| M-28 KIS | 사용자 앱키 보유 시 최소 구현, 미보유 시 **M2 이연**(B D-B5) — 착수 전 사용자 확인 1건 |
| M-29 targetSdk | **최신(35+) + POST_NOTIFICATIONS 대응을 M1에서 처리**(C U-14) |
| M-30 Gradle 의존성 체크섬 | **M3 이월**(A U-8 — 카탈로그+동적 버전 금지로 §2.3 충족) |
| 스모크 범위 | **필수(게이트 정본, 확정 1회+프리뷰 3회 — 프리뷰 3회는 서로 다른 시계 조건 배치(D §11 하드 요구), 사용자 실작업 ≈25분) + 확장 4종은 선택**(C U-15 — 사용자 승인 안건, 미승인 시 S9 멱등만 승격) |
| 데이터 소스·경로 | KRX = `D:\android_2025\kotlin_krx` 벤더링(`mobile/third_party/krxkt/` + VENDOR/PROVENANCE 매니페스트, 적응 수정 허용·무단 수정 가드), 야후 글로벌 = Stooq 폴백(K-18), 야후 ^KS11 폴백 비채택(사용자 기결정) |

## 2. 정규 참조 매핑 (구현 브리프가 인용할 정본 절)

| 주제 | 정본 |
|---|---|
| 런타임 데이터 경로 사슬 7단계(정본 file:line 매핑·수학 증명) | **D §2 전체**(전치 정리 §2.4.1, cutoff 역산 §2.2.3, 3규칙 환원 §2.5.1, L 표 + W-V5) |
| 원계열 조회 계약·requiredRows 파생·계산 순서 | **B §5.4.1**(+ A §2.11 3단계 계약, 지뢰 6·7) |
| 읽기 지점 전수표(3지점 × lane × tie-break × 반환 계층 × 증인) | **A §2.12 (b-0)** = D §2.8 (B §5.4.3·C §9-B-2a 동형) |
| 가시성·스테일(visible_at 파생, worst-of, 등호, cadence 폴백) | A §2.8 + B §5.2·5.2.1 + D §2.5 |
| 상태 지속(전량 fold·tick_input 동결·캐치업·절단) | A §2.10 + B §5.6·5.6.2 + M-34 |
| carry-forward(원천·계층·깊이) | A·B·C·D의 ③ 계약(수렴) + §1.2 |
| 프리뷰 시각 규약 | B §5.4.2 + A §2.12(c) + M-39 |
| BT-05 실행 규격(산출물 4파일·L0~L6·창 범위) | **C §9-C** + B §8 + D §2.10 |
| 커버리지 게이트(Kover·모듈 임계·check 배선) | **B §3.2.1**(UI 제외 없음) |
| 실패 경로 카탈로그·억제 UX·접근성 | **C §4**(24종 + INV-1~3) |
| 노티 채널·홈 상태 열거형·M1/M2 경계 | **D §3.9.1** |
| 실기기 스모크 절차·진단 JSON·기계 판정 | **D §11**(+C 필수/확장 분리) |
| 계약 스냅샷(Python 생성기·asymmetric 케이스) | B §6 |
| kotlin_krx 벤더링·오류 정책(K-19) | A §2.3·MT1-01d/04c + B §17 A-2 |

## 3. 서브태스크 합집합 (TASK_mobile_m1 세분화·보강 — 축소 0)

병렬 표기: 같은 웨이브 내 항목은 한 메시지 다중 위임 가능. 완료 기준 상세·테스트 명령은 정규 참조 절 인용.

- **W0 실측 선행** (data-verifier, 전부 병렬): MT1-00a 야후 REST·Stooq(K-01/K-18) / 00b ECOS item_code(K-04) / 00c kotlin_krx 로그인·VKOSPI·필드·달력(K-02/K-03) / 00d CDS 접근성(G-4) / 00f SQLite 인덱스 플랜(성능 확인용) / 00g 확정 시각 실측(3거래일 폴링, 사전등록 판정 — 17:00 확정 조건)
- **W1 기반** : MT1-01a 스캐폴드(3모듈·카탈로그·ktlint/detekt) → 01b syncConfigs(M-03)+해시(계측 정본+JVM 보강) ‖ 01f Kover 게이트 ‖ 01g kotlin_krx 벤더링 ‖ MT1-02 계약 미러+스냅샷(python-implementer 병행, 02d 포함)
- **W2 원장·수집**: MT1-03 observation(lane·레인별 revision·DAO 봉인)+03c tick_input(M-49)+append-only 강제+CSV/SAF ‖ MT1-04a~f collectors(00 실측 결과 반영, 병렬)+04g 백필(웜업 550)+04h 픽스처 대조
- **W3 엔진**: MT1-05 visibility(W-K1 필수)→transforms→scoring/modifiers→statemachine(D-26 포함)→IndicatorRuntime(05b2)→**BT-05 패리티**(M-11b, backtest-analyst 검증 병행)+05k e2e
- **W4 실행 경로**: MT1-06 확정 틱(17:00)+캐치업(evaluatedAt=D 17:00·상한 20·NULL 동결)+멱등+06h 부트스트랩 게이트 ‖ MT1-07 프리뷰(D-17/D-23·0.80·carry-forward §1.2·07e 시계 증인)
- **W5 표면·마감**: MT1-08 노티 3채널+기능판 홈(상태 7종, kotlin-implementer)+08c 스모크 절차·판정기+08d 진단 JSON → 실기기 1일 스모크(사용자) → GATE_GM1
- 전 서브태스크: qa-verifier → aaa-critic 2단 PASS 의무, 완료 보고에 git status 원문(REVIEW_M0 규율), 커밋 `m1-xx:` 단위

## 4. SSOT 변경 제안 통합 (승인 후 해당 서브태스크에서 적용 — 직접 수정은 TASK 허가 범위만)

**착수 선행 2건(★)**: ① `engine.preview_coverage_min: 0.80`(MT1-07 블록) ② `engine.warmup_padding_days: 550`(MT1-03c·04g 블록) — 둘 다 `configs/indicators.yaml` `engine:` 블록, engine_ref 미소비라 골든·서버 영향 0(스키마 가드 테스트 동반).

기타: `profiles.mobile_daily.catchup_max_ticks: 20` / `profiles.mobile_daily.confirm_time_kst`(00g 실측 후) / `krx_credit_spread_delta.source.item_codes` VERIFY→실측값(00b 후) / `contracts/snapshots/` 신설 / `.gitattributes` / `golden_mobile.yaml` registry_version 스탬프 정정 / 문서 16:20→확정값 정정 / **K-19**(kotlin_krx 빈 응답⊕파싱 실패 동일 반환)·**K-20**(알림 권한 거부 시 3채널 무력) CLAUDE.md §3 편입 / **D-27**(휴장일 무커밋) / **D-25 부기**(간극 카운터 무조작 — 오차 상한: 강등 ≤2틱 조기·승격 0틱, 비평가 코드 추적 검증)

## 5. 리스크 요지

기존 R-01~06 + 4안 등록분 유지(각 계획 리스크 절 참조). 최상위 3건: ① 야후계 차단(K-01/K-18 — Stooq 폴백, 00a 실측) ② kotlin_krx 로그인 정책(K-03·CD011 — 00c 실측) ③ 웜업 550일 백필의 소스 rate limit(K-03 — 04g 예산 설계). 근사-PIT 한계는 M0과 동일하게 C1에서 실측 확정(G-5).
