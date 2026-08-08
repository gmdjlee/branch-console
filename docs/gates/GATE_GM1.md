# GATE_GM1 — M1 모바일 코어 게이트 리포트

- 작성일: 2026-08-07(초안) · 작성: Advisor(메인 세션) · 대상 phase: Track M / M1 (TASK_mobile_m1.md + docs/plans/M1_PLAN_FINAL.md)
- 상태: **초안 — 통과 선언 불가.** 게이트 조건 4건 중 **② 충족**(2026-08-08 — MT1-04d 인도·K-04 종결, §6).
  잔여 3건: ① confirm_time_kst 실측·기입(MT1-00g — 08-10·08-11 무인 폴링 등록됨) ③ 실기기 1일 스모크
  ④ 사용자 승인. 전건 충족 시 본 문서를 갱신해 상신한다.
- 정본 인용: `docs/reviews/REVIEW_M1.md`(plan council 7라운드 + 구현 판정 로그) / `docs/plans/M1_PLAN_FINAL.md`(병합 확정본)
  / `docs/runbooks/M1_SMOKE.md`(스모크 절차·판정) / `docs/journal/2026-08-07_MT1-00*.md`(실측 6종)

> **근사-PIT 승계**: BT-05 패리티·e2e의 기준 타임라인은 M0 근사-PIT 픽스처 파생이다. 실측 PIT 확정은 C1 몫(D-19).

---

## 1. 요약 판정

TASK_mobile_m1의 완료 기준 6항 중 **4항 충족 · 2항 미충족(외부 의존)**. ① `./gradlew check` + JVM 테스트 전부 green — 충족
(207태스크·:app 168+α·전 모듈, 계측은 컴파일 게이트까지 — 실행은 스모크 소관). ② BT-05 패리티 green — **충족·인용 적격**
(§5.1). ③ 해시·스냅샷 테스트 green — 충족(3층 해시·계약 왕복·형상 다이제스트). ④ aaa-critic 전 항목 PASS — 충족
(서브태스크 전건 2단 판정 종결, §4). ⑤ 실기기 1일 스모크 — **미실행**(선행: confirm_time 기입). ⑥ 본 리포트 + 사용자 승인 —
대기. 구현 잔여 **0** — MT1-04d 인도 완료(2026-08-08, 00b 재개로 K-04 종결·configs VERIFY 0 포함), 기본 8·신설 12
서브태스크 전건 인도·흡수 정산 완료(aaa 전수 대조, 구현 트랙 종결 선언 8839c15).

## 2. 완료 기준 대조표

| TASK 항목 | 증빙(테스트·결과·커밋) | 판정 |
|---|---|---|
| plan council | 4관점×7라운드 전원 PASS, 구조 재분류 2회 처방 작동(REVIEW_M1). 병합 M1_PLAN_FINAL, 사용자 승인 2026-08-07. 커밋 93efc91 | PASS |
| MT1-00 실측 선행(신설) | 00a 야후·Stooq·FRED(63d4cf0·87e059c·38a8ab7 — Stooq PoW 차단 확정·FRED 폴백 매핑·HY OAS 3년 롤링) / 00b 재개·종결(2026-08-08 — 721Y001 SSOT 오기 적발, 일별 정본 **817Y002**·item_codes 실측 확정, **K-04 종결** — c7f9fab) / 00c kotlin_krx(3012100·2a32f84 — TRDVAL 오정렬 적발·정정) / 00d CDS (b) 미수집(a7b0322) / 00e 툴체인(cdebe47+3) / 00f SQLite 플랜(4039bb6) / 00g 계기+1일차 폴링(523065d·d569cab·d075daa — **3거래일 미완**, 08-10·08-11 무인 폴링 등록 fff0b71) | 부분(00g) |
| MT1-01 스캐폴드+syncConfigs | 01a(eb480b0·83c0e9e) 3모듈·check 실가동 / 01b(2103750) 생성 assets+3층 해시(변조 증인 3종) / 01f(2299613·336861c) Kover 게이트+생존 증인 3모듈 / 01g(d47105b) 벤더링+PROVENANCE 양방향 증인+.gitattributes(a781454 — 클린 체크아웃 재현) | PASS |
| MT1-02 계약 미러+스냅샷 | 02a Python 스냅샷 14파일+동결 22테스트(3b5ddf0+2, 뮤테이션 13종 사망) / 02b Kotlin 미러 12클래스·제약 12 등가(ddd32f4) / 02d 형상 다이제스트 교차(a148e66) | PASS |
| MT1-03 Room append-only | :lake(4573e0c) — lane·레인별 revision·tick_input(M-49)·DAO 봉인 3지점·이중 방어, 증인 6종 뮤테이션 전건 사망, 커버리지 100% | PASS |
| MT1-04 collectors | 04a·b(47fd335·c326428 — 00a 계약·오류 6분류·RetryPolicy SSOT 로더) / 04c(eeee805 — K-03 SSOT 스로틀·K-19·TRDVAL 계약 증인) / 04d ECOS 어댑터(0eb6b98·7c1f32a — 817Y002·item_codes SSOT 로드·K-17 편입·파이프라인 3경로, **지표 활성화는 C1 소관 별도 지위**) / 04e M2 이연(사용자) / 04f (b) 미수집(사용자) / 04g 웜업 백필(564d631) / 04h PIT 대조(2611123) | PASS |
| MT1-05 엔진+BT-05 | 05 포팅(666b0d9·9563a85·9caf396 — 차분 대조 전 계층 등가·pct_change 의미론 D-1 해소) / 05e export(d21fc6a) / **05j BT-05 §5.1** / 05k e2e(ec65281) | PASS |
| MT1-06 확정 틱+캐치업 | 3라운드(b1c8690~8124ee8) — Worker 앵커·실패 감사·부트스트랩 하한(F-4 계보 2FAIL 후 해소·5변형 실증)·캐치업 evaluatedAt=D 확정시각·상한 20 절단 NULL 동결·멱등 | PASS |
| MT1-07 프리뷰(D-17·D-23) | 4d19638 — TASK ①~④ 전건(67.7% 억제 assets 산출·66.7 vs 45.2·Konsist 3중 격리·tick_input 불변), 이월 깊이 1·자기참조 부재, M-39 실경과 | PASS |
| MT1-08 노티·홈·온보딩+스모크 도구 | 2f9915b~d8ebbbd — 3채널·홈 7상태·K-17 자격증명·dailyCollect 배선(WorkerFactory)·not_configured 표면화 / 08c·08d(8901717·28b2366) — M1_SMOKE.md·판정기(S-2 오탈락 해소·양방향 증인)·진단 JSON | PASS |

## 3. 공통 회귀 (최종 실측 — 2026-08-08 HEAD 59faf9a, 04d 코드 포함. confirm_time 기입 후 최종 HEAD에서 재실행 예정)

```
uv run ruff check .                          → All checks passed!
uv run pytest -q                             → 274 passed          (①)
uv run pytest backtest/test_golden.py -q     → 6 passed            (② D-08 2케이스 × 2프로파일 무회귀)
uv run pytest backtest/test_bt05_parity.py -q → 14 passed          (BT-05 판정 — 9창+합성)
cd mobile && ./gradlew check --rerun-tasks   → BUILD SUCCESSFUL (207/207 — ktlint·detekt·Kover·생존증인·해시·provenance)
:app:assembleDebug                           → BUILD SUCCESSFUL (스모크용 APK 패키징 검증 — 기입 후 재빌드 대상)
```

계측(connected) 테스트는 컴파일 게이트까지 — 실행은 실기기 스모크(§6)에서.

## 4. aaa-critic 판정 이력 요약 (REVIEW_M1.md 정본)

- **plan council**: 7라운드(R1 22결함 → R7 0), 전원 PASS. 구조 재분류 2회 — ①"런타임 데이터 경로"(3연속) → 사용자 결정으로
  관점 D 신설 ②"프리뷰 원장 접근 규율"(3연속) → 읽기 지점 전수표(M-43b) 공통 강제. 두 처방 모두 작동 실증.
- **구현 판정**: 전 서브태스크 qa→aaa 2단, FAIL→해소 반복 수렴. 주요 궤적 — W0 R1 FAIL(D-1 투자자 필드 오정렬 등 5)→R2 PASS /
  01a·02a·00e COND→해소 / W1 COND(생존 증인)→해소 / W2 COND(RetryPolicy 리터럴)→해소 / W3 **FAIL(D-1 pct_change pad
  발산 — 차분 하니스 적발)**→engine_ref fill_method=None 고정(골든 불변 실측 후)→PASS / 05j COND(§9-C 퇴화 증인)→합성 창→
  PASS·**BT-05 인용 적격 선언** / 06 3라운드(F-4 계보 2FAIL — 3회째 구조 재분류 직전 해소) / 08 FAIL(detekt 상한 자기 완화 등)→
  해소 / 08c FAIL(S-2 오탈락)→해소 / 하니스 3건 PASS.
- **절차 사건**: qa의 보고-저장소 대조가 서술 부정확 3건을 적발(12→10 시나리오 / TradingDayGridProvider 허위 완료 주장 /
  커버리지 수치) — 전건 은폐 없이 정정 커밋으로 종결. REVIEW_M0 신설 규율(완료 보고 git status 원문·실행 출력 복사 의무)이
  작동한 사례이자 지속 경계 대상.

## 5. 수치 증빙

### 5.1 BT-05 패리티 (GM1 핵심 조건 — aaa 인용 적격 확정)

| 항목 | 결과 | 기준 |
|---|---|---|
| 범위 | 실측 9창 **397틱** + 합성 퇴화 증인 창 5틱(§9-C 4종 고정), L0~L6 계층 전건 | 9창 × mobile_daily |
| max \|Δcomposite\| | **0.0** | ≤ 0.05 |
| max \|Δcoverage\| | 0.0 | ≤ 1e-9 |
| max \|Δvalue\| | 2.673e-13 (w2022/kospi_volume_distribution — 부동소수 누산 순서) | rel 1e-9 |
| 국면 타임라인 | 완전 일치(397틱) | 완전 일치 |
| golden_mobile (L6) | 2케이스 완전 일치 | 일치 |
| D-26·or_any_extreme | w2026 any_extreme **15회 실발화**, 첫 발화 2026-07-08 ORANGE(MT0-08 기록 일치) | 실데이터 검증 |

재현: `uv run python backtest/export_parity.py --window all` → `cd mobile && ./gradlew :engine:test --tests "*ParityRunnerTest*"` → `uv run pytest -q backtest/test_bt05_parity.py`(14 passed)

### 5.2 무회귀 주장의 전제 증명 계보

**04h**(수집기 파싱 출력 = Python 픽스처, 13/14쌍·1e-6) → **BT-05**(엔진 = engine_ref, §5.1) → **05k**(실 원장·실 조회·실
13지표 레지스트리 e2e, w2026 53틱 — 전이 5회·or_any_extreme e2e 발화) → **02d**(계약 형상 무드리프트 — shape.sha256 바이트
일치). 보조: 05 차분 하니스(상태기계 134,703틱·scoring 4,000케이스 무불일치), pct_change NaN 증인 양측.

### 5.3 품질 계측

커버리지(Kover LINE): :engine 100%(계약·사슬 포함) / :lake 100% / :krx 82.8%(벤더 제외 후 자기 코드) / :app 77.1% —
전 모듈 임계(90/70) 충족 + 측정 생존 증인 3모듈. Python 신규 2파일 99%. 뮤테이션 증인 누적: 계약 13종·lake 6종·
02d 형상·05 pct_change·판정기 양방향 등 — 전건 사망 확인.

## 6. 미결·리스크와 게이트 조건

| 미결 | 처리 경로 | 상태 |
|---|---|---|
| **① confirm_time_kst 실측·기입** | MT1-00g — 1일차(08-07) 6슬롯 완료(16:00에 3/4 확정 관측). 08-10·08-11 **무인 폴링 등록**(작업 스케줄러 BranchConsole-00g-probe 12트리거, 실발화 검증 — fff0b71). **값 드리프트 관측**(investor ~18:00·kospi 거래량/대금 18:00→19:00·KRW=X 익일 새벽 갱신, PROGRESS 기록)은 판정 입력. **폴링 → 사전등록 규칙 판정 → statemachine.yaml 기입**. 미기입 시 스케줄·스모크 S-2 실행 불가(명시 실패 설계). 기입이 assets를 바꾸므로 **스모크용 APK는 기입 후 재빌드**(K-16) | 진행 중(자동) |
| **② MT1-04d ECOS 어댑터** | **인도 완료(2026-08-08)**: 00b 재개 — 실측이 SSOT 오기(721Y001→일별 정본 **817Y002**)를 적발·정정, item_codes 확정, **K-04 종결**(c7f9fab). 04d 수집기 구현(0eb6b98 — 수집 계층, 파이프라인 3경로 편입). **지표(krx_credit_spread_delta) 활성화는 C1 소관 별도 지위**: Python 정본 빌더 부재 + 활성화 시 composite 분모 82.5→88.5로 전 틱 ×0.9322 — 골든 재산출 산술 확정(F-04 동형) | **완료**(7c1f32a) |
| **③ 실기기 1일 스모크** | `docs/runbooks/M1_SMOKE.md` — 사용자 실작업 ≈25분(확정 틱 1회 + 시계 조건 상이 프리뷰 3회 + S9 멱등), 진단 JSON 6종 → `scripts/check_smoke_evidence.py` 13체크 통과, 증빙은 docs/gates/evidence/GM1/ | ①② 후 |
| **④ 사용자 승인** | 본 리포트 최종본 상신 | ①~③ 후 |
| **A-1 정정(2026-08-08)** | W0 발원 수치(0.792/0.847)는 SSOT 도출 불가 오류로 aaa 반증. **실측: 현행 coverage 상한 27.5/31.0=0.8871 ≥ 0.80 — 프리뷰 상시 억제는 애초 불성립**(정상 동작). ECOS 지표 활성화 시 0.9516. 실제 억제 조건 = 런타임 결측(예: MOVE+VIX3M 결측 → 0.7581 — D-23 의도 동작, K-01/K-18 리스크로 기록). D-23 개정(b)·M1 내 활성화(c)는 실측 근거로 기각, (a)-정정안 채택 | 처분 확정 |
| M0 §6 FAIL 3건 | C1 이관 verbatim(GATE_GM0 결정 유지 — w2023_11 오탐·server 플래핑 잔존·D-14 보류) | C1 |
| run_log 180일 purge(M-22) | 테이블·규율 분리 완료, purge 메커니즘 미구현 — M2/M3 정비 | 이월 |
| 실패 경로 카탈로그 24종(C §4.1) | M1은 부분(핵심 경로 — KEY_MISSING·CALENDAR_FALLBACK·EmptyOnTradingDay 등), 전건은 M2 UI 완성과 함께 | M2 |
| KEY_MISSING 노티 "최초 1건" | 구현은 1일 1건(노티 예산 규칙 준용 — aaa 수용, M2 재검토) | M2 |
| NotificationSyncWorker 폐지 후 크래시 공백 잔여(dailyCollect 예외 전파) | 구조적 폐쇄 판정, collector 계약 위반 시에만 — MT1-06/08 공동 관찰 | 관찰 |

**GM1 기록 사항(판정 아님 — 승계 고지)**: 무폴백 4계열(KRW=X·DXY·MOVE·VIX3M — Stooq PoW 차단, FRED 미러는 VIX·SPX만) /
credit 축 이중 차단(ECOS+CDS — ② 해소 시 절반 회복) / 노티 채널명 정본 불일치(provisional_alert 채택) / VKOSPI 픽스처
전 창 0행(M-19(c) 수집 전용 지위) / D-1 투자자 필드 증인 분담(04h 슬롯 ↔ KrxInvestorTradingContractTest TRDVAL10+11) /
확정 틱 재구성 규약: tick_input에 evaluated_at 미저장 — 00g 확정값이 17:00과 다르면 기존 틱 재구성 규약 재확인(현재 커밋 0이라 무해).

**S-0 결함 트랙(2026-08-08)과 증인 설계 원칙(aaa 확정 — 정본 REVIEW_M1 §실기기 S-0)**: 실기기 S-0 선행이 구현 트랙 종결
선언(8839c15) 직후 결함 4건을 연속 적발 — ①인셋(탭 행 상태바 뒤) ②INTERNET 권한 누락(788f13f) ③자격증명 공백 미트림
(9cbe115) ④FRED realtime 시간대(UTC→ET 오답 경유→**America/Chicago 실측 확정**, a2c5e09). ①②는 실기기 전용 검출이나
**③④는 JVM 검출 가능 결함**이었다 — M1 증인 설계가 실환경 도달 경로를 과소 대표했다는 정량 근거(종결 선언 조기). 원칙:
⑴ 증인은 "수정한 코드"가 아니라 "결함이 도달하는 수렴점"에 건다(트림: 호출부 3곳 증인은 provider 4경로를 놓쳤고 load()
수렴점 증인은 뮤턴트 1개로 legacy 저장값까지 방어) ⑵ 고정 clock은 경계 밖 안전 시각에 두고 경계는 전용 증인으로 분리한다
(00:00Z 픽스처가 시간대 버그를 정답으로 박제했던 사례) ⑶ 외부 시스템의 시간대·달력은 추정 금지·실측 필수(K-13 확장)
⑷ 실측은 반증 가능해야 한다 — 가설을 좁히지 못하는 관측은 근거가 아니다(03:30Z 단일 재현이 ET/CT 무판별인 채로
America/New_York 오답이 기계 검증(qa PASS)을 통과해 1라운드 출하 — 기계 검증은 증인이 옳은 것을 겨냥했는지는 판정하지
못한다; 04:06Z·05:06Z 추가 실측으로 확정) ⑸ 경계 증인의 픽스처 시각도 같은 규칙 — "이 픽스처가 어떤 오답을 배제하는가"의
답이 하나뿐이면 시각을 옮긴다(03:30Z→04:30Z 이동으로 UTC·ET 회귀를 동시에 잡음). 후속 관찰: 겨울 CST 롤오버 1회 재실측 /
UX: KIS 필드 간 키보드 가림(imeAction=Next 부재) M2 이관.

**다음 phase(M2) 착수 조건**: 본 게이트 사용자 승인 + M2 plan council(AAA §3).

## 7. 사용자 결정 안건 (최종본에서 확정 상신)

1. **[게이트] §6 조건 ①~③ 충족 후, M0 이관 FAIL 3건과 §6 기록 사항이 잔존하는 상태로 M1을 종결하고 M2(plan council)에
   착수하는 것을 승인하는가?** — 초안 단계, 조건 충족 후 상신.
2. **[A-1] ECOS 키 발급이 장기화될 경우**: (a) 키 대기 유지 (b) D-23 §23.3-3 임계 0.80 개정 상신(프리뷰 억제 완화) —
   현재는 (a) 진행 중(사용자 결정: 키 발급 예정).
3. (해당 시) **confirm_time 실측이 17:00 초과 항목을 발견하면**: 사전등록 규칙대로 시각 상향 + 골든 재산출 판정을 별도 상신.

---

- Advisor 검토·서명: 2026-08-07 초안(조건 4건 미충족 상태 — 통과 상신 아님)
- aaa-critic 게이트 검토: 최종본에서 수행 예정
- 사용자 승인: 대기
