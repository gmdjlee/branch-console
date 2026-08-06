# M1 실행 계획 — 관점 C (UX·운영·실패경로)

- 작성일: 2026-08-06 · 작성: plan-architect(관점 C) · 절차: AAA_QUALITY_STANDARD §3 · 입력: `docs/plans/M1_COUNCIL_BRIEF.md`
- 지위: **관점 C를 대표하되 그 자체로 실행 가능한 전체 계획**. 관점 A(아키텍처)·B(데이터·정합성)의 소관 항목에도
  본 계획의 입장을 명시했다(§9) — 병합 시 A·B가 더 깊은 근거를 제시하면 그쪽이 이긴다.
- 규율: 본 문서는 계획만 담는다. `configs/`·`contracts/`·`prompts/`와 기존 코드는 **직접 수정하지 않았고**,
  필요한 변경은 전부 §8 "변경 제안"으로만 기록했다.

---

## 0. 이 계획의 한 줄 논지

M1의 실패 위험은 "엔진이 틀리는 것"이 아니라 **"엔진이 맞았는데 사용자가 그 사실을 알 수 없는 것"** 이다.
일 1틱 시스템은 하루에 한 번만 말한다 — 그 한 번이 조용히 실패하면 사용자는 **시스템이 정상이라고 오해**한다.
따라서 M1의 UX 요구는 화면의 아름다움(M2 소관)이 아니라 **"침묵의 금지"** 이며, 이것은 취향이 아니라
AAA §2.2의 하드 기준이다. 본 계획은 그 기준을 §4의 실패 경로 카탈로그(24종)와 §3의 세 불변식(INV-1~3)으로
번역하고, 각 항목을 실행 가능한 테스트 명령에 묶는다.

---

## 1. 착수 전 확정 사실 (본 계획이 근거로 삼는 것)

브리프 §2의 12개 확정 사실을 전제로 하고, 계획 수립 중 **읽기 허용 파일에서 새로 확인한 사실**을 아래에 더한다.
이것들은 추측이 아니라 파일 인용이며, 계획의 여러 항목이 여기에 직접 걸려 있다.

| # | 확인 사실 | 출처 | 계획에 미치는 영향 |
|---|---|---|---|
| F-1 | `configs/statemachine.yaml schedules.evaluation.kr_close` = **cron `0 17 * * 1-5`(17:00 KST)**, `collection.daily_kr` = 16:50, `daily_us` = 07:20 | statemachine.yaml L70-79 | 확정 틱 16:20 가설은 **수집(16:50)보다 30분 앞선다** — 자기모순. §5에서 17:00 확정 논증 |
| F-2 | `backtest/replay.yaml`의 `mobile_daily.confirm_time_kst: "17:00"`, 부기 "M1 실제 확정 틱 설계와 동시 재확인"(AD-3b) | replay.yaml | 17:00 채택 시 SSOT 3곳(statemachine·replay·M1 스케줄)이 하나의 숫자로 수렴 |
| F-3 | `engine_ref/scoring.py compute_composite`는 `coverage = 유효가중/전체가중`을 **이미 1급 산출물로 반환**하고, 전체 가중은 활성 지표 전체(=31.0)다 | scoring.py L99-132 | coverage는 새로 만들 개념이 아니라 **포팅 대상**. Kotlin 엔진이 이미 산출하므로 MT1-07의 계산은 프리뷰가 아니라 엔진 계층에 산다 |
| F-4 | `golden_mobile.yaml`의 틱 레코드에 `coverage: 0.887…`가 동결돼 있다(=27.5/31.0) | golden_mobile.yaml | BT-05 패리티는 composite뿐 아니라 **coverage도 비교 가능** — 프리뷰 억제 로직의 무회귀 방어선으로 재사용 |
| F-5 | `kr_cds_5y_delta` 가중 1.5. 미수집 확정 시 **coverage 상한이 29.5/31.0 (표시 95.2%)로 영구 고정** | indicators.yaml + F-3 | UI가 "커버리지 95%"를 상시 표시 → 사용자가 매일 데이터 장애로 오해한다. 문구 설계 필수(§4.5) |
| F-6 | D-23 §23.2의 67.7%는 **KR 4지표(8.5) + CDS(1.5) = 10.0 결손**일 때의 값(21.0/31.0). CDS를 수집하면 같은 시나리오가 72.6%가 된다 | 산술 + indicators.yaml | **MT1-07 완료 기준 ③의 "67.7%"는 G-4 (b) 채택을 암묵 전제한다.** (a) 채택 시 기대값 재산출 필요 → 결정 U-3 |
| F-7 | **`configs/indicators.yaml` L76** `krx_credit_spread_delta.source.item_codes: { corp_aa3y: VERIFY, ktb_3y: VERIFY }` — K-04 미해소. (`sources.yaml` L35는 "구현 시 검증" **주석**일 뿐 `item_codes` 키가 없다 — 실측으로 확인) | `grep -rn "VERIFY" configs/` → indicators.yaml L76 **단 1건** | `krx_credit_spread_delta`(가중 2.0)는 현재 **수집 불가**. M1에서 실측 없이는 credit 축 2/3 결손. 변경 대상 파일은 indicators.yaml이다(P-2) |
| F-13 | 스테일 판정의 기준 시각은 `as_of`(달력일)가 **아니라 `visible_at`(가시화 시각)** 이다. `backtest/run_replay.py:352-369 is_stale_check` docstring이 as_of 사용을 **실측으로 반증**: "달력일 자정을 as_of로 쓰면 kr_close(17:00)에 막 가시화된 당일 값이 자정 대비 8시간 지남으로 오판되어 intraday_30m(90분) 임계를 즉시 넘긴다". 등호 미포함(초과만 stale), tz-aware 강제(K-05) | run_replay.py L352-369, L222-274 | **BT-05 패리티와 프로덕션 Room 조회의 정본 규칙**. 계획 전반(§9-B·§9-C·§10)이 이 규칙 위에 세워진다 |
| F-8 | kotlin_krx는 **JVM 라이브러리**(`kotlin("jvm")`, java-library, JVM17), okhttp4+gson+coroutines. Android 모듈 아님 | build.gradle.kts | Android 앱에서 직접 소비 가능하나 **버전 정렬·vendoring 정책**이 필요(§9-A) |
| F-9 | kotlin_krx에 **`KrxClient.login(id, pw)` 구현이 존재**하고, `KrxError.AuthenticationError`·LOGOUT 감지 경로가 있다. 세션은 `InMemoryCookieJar`(프로세스 생명주기) | KrxClient.kt, KrxError.kt | KRX 2026 로그인 정책은 **이미 대응돼 있을 가능성이 높다**(실측 V-2로 확정). 단 프로세스가 매 틱 새로 뜨므로 **틱마다 login 1회**가 기본 비용 |
| F-10 | kotlin_krx 주석: "파생상품(MDCSTAT01201=VKOSPI, MDCSTAT13102=옵션)은 **mdiLoader Referer → 세션 필요**", 그리고 `krxIndex.getVkospi(start,end)`가 **구현돼 있다** | KrxClient.kt L27-29, CLAUDE.md 매핑표 | **모바일은 VKOSPI 실측이 가능할 수 있다** — 서버(K-02 realized_vol 폴백)와 입력이 갈린다. 패리티·기준선 문제 → 결정 U-4 |
| F-11 | kotlin_krx 오류 정책: "Parse Errors → 빈 리스트 반환", "Empty Response → 빈 리스트(휴장 가능)" — **파싱 실패와 휴장이 같은 반환값** | CLAUDE.md Error Handling | 휴장 판정을 빈 응답으로 하면 **파싱 실패가 휴장으로 위장**한다. 조용한 실패의 교과서적 사례 → 신규 함정 제안 K-19 |
| F-12 | AAA §2.4(디자인)는 **M2~ 적용**이고, §2.5(상용 체감)는 **M2·M3 게이트 전용** | AAA §2.4·§2.5 머리 | M1 홈은 "기능판"이 정당하다. 단 §2.2(실패 경로)·§2.3(유지보수성)은 **M1에 전면 적용** |

---

## 2. 서브태스크 분해 — 의존성 그래프

TASK_mobile_m1의 MT1-01~08을 유지하고 하위 항목으로만 세분화했다(축소 0건).
`‖` 표시는 동일 라운드 병렬 위임 가능, `→`는 선행 필수.

```
                     [V-1..V-6 실측 라운드 0]  ← data-verifier ×3 병렬, 최우선
                              │ (V-2·V-3·V-4가 04c/04d/04f/06a를 블록)
                              ▼
  MT1-01 스캐폴드 ────────────────────────────────────────────────┐
   01a 모듈·카탈로그·lint·kover                                    │
   01b syncConfigs + 해시 검증(Gradle+Robolectric)                 │
   01c kotlin_krx vendoring                                       │
   01d 앱 골격(DI·네비·테마·UiState)                               │
   01e 실패 불변식 하네스(INV-1~3, detekt/Konsist 룰)              │
          │                                                       │
          ├──────────┬──────────────┬─────────────┐               │
          ▼          ▼              ▼             ▼               │
     MT1-02 계약   MT1-03 Room   MT1-04 수집   (08b/08c UI 골격)   │
      02a Py 스냅샷  03a append-only  04a 야후     ← 가짜 저장소로  │
      02b Kt 미러 ‖  03b visible_at 04b FRED        선행 착수 가능 │
      02c 동결 배선   03c run_log    04c KRX(login) │               │
                     03d CSV·백업   04d ECOS       │               │
                        │           04e KIS(옵션)  │               │
                        │           04f CDS 판정   │               │
                        │           04g 오케스트레이터 ◄─ 관점 C 핵심
                        │              │           │               │
          ┌─────────────┴─────┬────────┘           │               │
          ▼                   ▼                    │               │
     MT1-05 엔진          (03+04 완료)             │               │
      05a 계산 포팅            │                   │               │
      05b 상태기계(D-26)       │                   │               │
      05c BT-05 패리티         │                   │               │
          └──────────┬─────────┘                   │               │
                     ▼                             │               │
              MT1-06 확정 틱                       │               │
               06a WM 배선·휴장 스킵                │               │
               06b 파이프라인                       │               │
               06c 멱등                            │               │
               06d 캐치업                          │               │
               06e 실패 코드·노티 상한              │               │
                     │                             │               │
          ┌──────────┴──────────┐                  │               │
          ▼                     ▼                  │               │
     MT1-07 프리뷰          MT1-08 노티·홈·설정 ◄───┴───────────────┘
      07a 파이프라인          08a 노티 3채널
      07b carry-forward 격리  08b 기능판 홈
      07c coverage·억제       08c 실행 이력 화면
      07d 억제 UX             08d 설정·온보딩 최소
          └──────────┬────────►08e 스모크 절차·스크립트
                     └────────►08f 실패 경로 UX 회귀 스위트 (전 카탈로그 파라미터화)
                                        │
                                        ▼
                              실기기 1일 스모크 → GATE_GM1
```

### 2.1 라운드 편성과 공수 근거

| 라운드 | 병렬 위임 | 위임 에이전트 | 공수(위임 단위) | 이 순서인 이유 |
|---|---|---|---|---|
| R0 | V-1 ‖ V-2 ‖ V-3·V-4 | data-verifier ×3 | S×3 | 실측 결과가 04a/04c/04d/04f **와 06a(확정 틱 시각)** 를 동시에 블록한다. 스캐폴드와 병행 가능하므로 **R0을 R1과 겹쳐 시작**한다 |
| R1 | 01a→01b→01c→01d→01e (순차, 단일 Worker) | kotlin-implementer | L | 빌드 골격은 분할 위임 시 머지 충돌 비용이 이득을 넘는다. 단 01a 완료 즉시 R2 착수 가능 |
| R2 | 02 ‖ 03 ‖ 04a·04b ‖ 08b/08c 골격 | kotlin-implementer ×3 + python-implementer(02a) | M×4 | 서로 독립. 02a는 Python 측이라 완전 분리. UI 골격은 가짜 저장소로 선행해 R5의 병목을 줄인다 |
| R3 | 04c ‖ 04d ‖ 04e ‖ 04f ‖ 04g | kotlin-implementer ×2(04c+04d, 04e+04f) → 04g 단독 | M×2 + M | 04g(오케스트레이터)는 a~f의 실패 계약을 모두 소비하므로 마지막 |
| R4 | 05a→05b→05c | kotlin-implementer(05a·05b) + backtest-analyst(05c) | L + M | 패리티는 엔진 완성 후에만 의미. 05c는 실행·해석이라 backtest-analyst |
| R5 | 06a~06e | kotlin-implementer | L | 캐치업·멱등은 한 사람이 한 상태 모델로 잡아야 구멍이 안 생긴다 |
| R6 | 07a~07d ‖ 08a·08d | kotlin-implementer ‖ ui-craftsman(07d·08b 마감) | M ‖ M | 프리뷰와 노티/설정은 독립. 07d 억제 UX는 접근성 요건이 있어 ui-craftsman |
| R7 | 08e ‖ 08f | kotlin-implementer(08f) + Advisor 문서(08e) | S ‖ M | 08f는 카탈로그 전건 회귀라 06·07 확정 후 |
| R8 | 실기기 스모크 | 사용자 + Advisor | S(필수 절차 실작업 약 25분, U-15 승인 시 +25분 — 하루에 분산 배치) | 게이트 직전 1회 |

**공수 표기**: S=위임 1회로 끝나는 규모, M=2~3라운드(반려 예상 1회 포함), L=3라운드 이상.
M0 실적(aaa-critic 평균 3.4라운드/서브태스크, MT0-06은 9라운드)을 근거로 **반려 1회를 기본 계상**했다.
"한 번에 통과"를 전제한 일정은 M0 실적에 반증된다.

---

## 3. 세 불변식 (INV) — "빈 화면·무한 스피너·조용한 실패 금지"의 실행 가능한 정의

AAA §2.2의 금지 조항은 그대로는 판정 불가능한 문장이다. 아래 3개로 번역하고, **각각을 테스트가 강제**한다.
이 세 항목은 MT1-01e에서 하네스로 먼저 만들고, MT1-08f에서 전 실패 경로에 대해 파라미터화 실행한다.

### INV-1 (No Empty) — 어떤 상태에서도 홈은 최소 1개의 행동 가능한 블록을 렌더한다

- 규칙: `HomeUiState`는 `sealed interface`로 `Ready | NeverRun | Degraded(reason) | IntegrityBlocked`만 갖는다.
  **`Loading`은 최상위 상태가 아니다** — 로딩은 항상 "직전 확정 스냅샷 + 진행 표시"의 오버레이다(첫 실행 제외).
- 첫 실행(NeverRun): "아직 첫 확정 틱이 실행되지 않았습니다 / 다음 예정 17:00 / [지금 실행]" — 빈 화면 금지.
- 테스트: `HomeStateExhaustiveTest` — `HomeUiState`의 모든 sealed 하위 타입에 대해 Compose 렌더 후
  `onNodeWithTag("home_primary_block")`과 `home_primary_action` 존재 assert. **새 상태를 추가하면
  when 분기 누락이 컴파일 에러**가 되도록 sealed + exhaustive when 강제(detekt `MissingWhenCase` 아님 — 언어 차원).
- 명령: `./gradlew :app:testDebugUnitTest --tests "*HomeStateExhaustiveTest*"`

### INV-2 (No Infinite Spinner) — 모든 대기에는 예산과 종료 상태가 있다

- 규칙: 수집 호출은 3중 예산을 갖는다 — provider별 `timeout_s`(권고 15s), 전체 확정 틱 예산 120s,
  전체 프리뷰 예산 20s. 예산 초과는 **예외가 아니라 결과**다: 해당 지표 `MISSING(TIMEOUT)`으로 확정되고 파이프라인은 계속된다.
- 프리뷰는 예산 만료 시 **그때까지 도착한 것만으로 계산**하고 나머지는 carry-forward한다 —
  "다 올 때까지 기다리는" 경로를 코드에 두지 않는다.
- 테스트: 가짜 시계(`TestScope` + `advanceTimeBy`)와 절대 완료하지 않는 collector 스텁을 주입하고,
  예산 시각에 상태가 `Ready(partial)` 또는 `Degraded`로 **전이했음**을 assert. 완료 대기(`awaitItem()` 무한)를 쓰지 않는다.
- 명령: `./gradlew :app:testDebugUnitTest --tests "*BudgetTimeoutTest*"`

### INV-3 (No Silent Failure) — 모든 실패는 기록되고, 도달 가능하고, (해당 시) 발신된다

- 규칙 3조: 실패는 반드시 (a) `run_log`에 사유 코드로 append, (b) 실행 이력 화면에서 3탭 이내 도달,
  (c) 확정 틱의 치명 실패(§4.1 FATAL 등급)는 `tick_failure` 채널로 발신.
- **정적 강제**: detekt에서 `SwallowedException`·`TooGenericExceptionCaught`·`EmptyCatchBlock`를
  **경고가 아닌 실패**로 설정(AAA §2.3 "린트 0 경고", 억제 주석은 사유 필수).
  추가로 Konsist 아키텍처 테스트 — `data`·`work` 패키지의 모든 `catch` 블록은 `FailureRecorder`를 호출한다.
- **동적 강제**: `FailureCatalogTest`가 §4.1 카탈로그의 전 사유 코드를 파라미터로 받아 (a)(b)(c)를 검증.
  카탈로그에 코드를 추가하고 테스트를 안 쓰면 **enum 전수 검사에서 실패**한다(`ReasonCode.entries` 전건 커버 assert).
- 명령: `./gradlew :app:testDebugUnitTest --tests "*FailureCatalogTest*"` · `./gradlew :app:detekt`

> 이 3개는 MT1-08f의 완료 기준이자 **aaa-critic이 AAA §2.2를 판정할 때 인용할 증빙**이다.
> "실패 경로를 정의했다"는 서술은 증거가 아니다 — 위 세 명령의 출력이 증거다.

---

## 4. 관점 C 심화

### 4.1 실패 경로 전수 카탈로그 (24종)

등급: **FATAL**(국면 미커밋 + 노티 발신) / **DEGRADED**(부분 진행, 배지·이력) / **INFO**(정상 변형, 이력만).
`reason_code`는 `run_log.reason_code`와 UI 문구 키의 단일 소스다(enum 1곳).

| 코드 | 상황 | 감지 지점 | 확정 틱 동작 | 프리뷰 동작 | UI 표현 | 노티 | 사용자 조치 | K-xx |
|---|---|---|---|---|---|---|---|---|
| `NET_OFFLINE` | 전면 오프라인 | ConnectivityManager + 첫 요청 실패 | FATAL — 미커밋, WM `Result.retry()` 3회 백오프 | 즉시 "오프라인 · 마지막 확정 n시간 전" | 홈 상단 배너 + 마지막 as_of 강조 | 1일 1건 상한 | 재시도 버튼 | K-01 |
| `NET_TIMEOUT` | provider 개별 타임아웃 | INV-2 예산 | DEGRADED — 해당 지표 MISSING(분모 제외) | 해당 지표 CARRIED | 지표 카드 "시간 초과" 배지 | 없음 | 없음 | K-01 |
| `NET_HTTP_5XX` | provider 장애 | HTTP 코드 | DEGRADED — 백오프 재시도 후 MISSING | 동일 | 배지 | 없음 | 없음 | K-18 |
| `YAHOO_SHAPE` | 야후 응답 스키마 변경 | 파서 필수 필드 부재 | DEGRADED — **Stooq 폴백 시도** → 실패 시 MISSING | 동일 | 카드 "폴백 사용" 배지 | 3일 연속 시 1건 | 없음 | K-01·K-18 |
| `STOOQ_FALLBACK_USED` | 폴백 성공 | 어댑터 | INFO — 값 사용 + `source=stooq` 기록 | 동일 | 카드 하단 출처 표기 | 없음 | 없음 | K-18 |
| `KEY_MISSING` | FRED/ECOS/KRX 키 미설정 | 온보딩 상태 조회 | DEGRADED — provider 전체 스킵 | 동일 | 홈 "설정 필요(n개)" 카드 + 설정 딥링크 | 최초 1건 | 키 입력 | K-17 |
| `KEY_INVALID` | 401/403 | HTTP 코드 | DEGRADED — MISSING + 사유 | 동일 | 카드 "키 오류" + 설정 딥링크 | 1일 1건 | 키 재입력 | K-17 |
| `KRX_SESSION_EXPIRED` | LOGOUT / `AuthenticationError` | kotlin_krx 예외 | DEGRADED — **login 재시도 1회** → 실패 시 KR 4지표 전부 MISSING | 동일 | "KRX 재로그인 필요" 카드 | 1일 1건 | 계정 확인 | K-03 |
| `KRX_RATE_LIMITED` | 과호출 | 429/빈 응답 반복 | DEGRADED — 최소 간격 1s 큐로 회피, 초과 시 MISSING | 프리뷰 쿨다운 강제 | 카운트다운 | 없음 | 대기 | K-03 |
| `QUOTA_EXCEEDED` | 제공자 쿼터(429 + Retry-After) | 헤더 | DEGRADED — 예산 내 백오프 → MISSING | 동일 | "잠시 후" + 잔여 시간 | 없음 | 대기 | K-10류 |
| `PREVIEW_COOLDOWN` | 사용자 연타 | 앱 내 카운터 | 해당 없음 | 버튼 비활성 + 남은 초 표시 | 카운트다운 | 없음 | 대기 | — |
| `MISSING_PARTIAL` | 일부 지표 결측 | 오케스트레이터 집계 | INFO — D-02 분모 제외 + coverage 기록 | carry-forward | coverage 칩 + 카드 배지 | 없음 | 없음 | — |
| `STALE_VALUE` | as_of가 `stale_profiles` 초과 | 엔진 registry.is_stale | INFO — **무효 처리(D-23 §23.3-4)** = MISSING과 동일 취급 | CARRIED + 스테일 배지 | 카드 "오래됨 · as_of" | 없음 | 없음 | K-05 |
| `EVAL_IMPOSSIBLE` | 전 지표 결측(composite None) | 엔진 D-25 §3 | FATAL — 국면·스트릭 **동결, 틱 미소비** | "판정 불가" | 명시 문구(흐림 아님) | 발신 | 재시도 | — |
| `LOW_COVERAGE_SUPPRESSED` | 프리뷰 coverage < 임계 | 엔진 coverage | 해당 없음(확정 틱은 억제 개념 없음) | composite 판정 억제 + **잠정 경보 미발신** | "국면 판정 불가 · 커버리지 67.7%" + **억제 사실 표기** | 억제(그리고 억제했다는 사실을 화면에 표시) | 없음 | — |
| `HOLIDAY_SKIP` | KR 휴장일 | 영업일 캐시 + 빈 응답 교차 | INFO — **스킵, 국면 미커밋**(§4.6 논거) | 프리뷰는 허용 + "휴장" 표기 | 이력에만 | 없음 | 없음 | K-03 |
| `CALENDAR_FALLBACK` | 영업일 조회 실패 | kotlin_krx 오류 | DEGRADED — 보수 규칙(주말만 스킵) + 플래그 | 동일 | 이력 배지 | 없음 | 없음 | K-03·K-19(제안) |
| `DUPLICATE_RUN` | 같은 일자 재실행 | `run_log` UNIQUE + Mutex | INFO — no-op, `DEDUPED` 기록 | 해당 없음 | 이력에만 | 없음 | 없음 | K-14 |
| `CATCHUP_IN_PROGRESS` | 놓친 영업일 소급 | 앱 실행 시 탐지 | INFO — 순차 커밋 + `pit_quality=BACKFILL` | 차단(캐치업 중 프리뷰 금지) | "n일 따라잡는 중 (1/n)" | 완료 시 요약 1건 | 없음 | K-14·K-15 |
| `CATCHUP_GAP_TRUNCATED` | 소급 상한·벽시계 예산 초과 | SSOT `catchup_max_ticks`(P-11) | INFO — 최근 N일만 처리, 누락분은 행 미생성 + `gap_before` 기록(§9-B-4c) | — | 이력 "간극 n영업일" + 국면 배지 "간극 이후" | 없음 | 없음 | K-11 |
| `WARMUP_INSUFFICIENT` | 롤링 창 웜업 부족(최초 설치 직후·백필 미완) | transform이 `min_periods` 미달로 null 반환 | DEGRADED — 해당 지표 MISSING(분모 제외) → `raw_coverage` 하락 | 동일 | 홈 배너 "이력 수집 중 (n/550일) · 판정 정확도 제한" + 지표 카드 "웜업" 배지 | 백필 완료 시 1건(정보) | 백필 계속 진행 | — |
| `STORE_WRITE_FAILED` | Room 쓰기 실패(용량·손상) | 예외 | FATAL — 트랜잭션 롤백, 미커밋 | 동일 | 홈 경고 배너 | 발신 | 저장공간 확보 | — |
| `ASSET_INTEGRITY_FAILED` | assets 해시 불일치 | 앱 시작 검증 | FATAL — **확정 틱·프리뷰 전면 차단**(마지막 스냅샷 열람은 허용) | 차단 | 전면 오류 화면 + 버전 정보 | 발신 | 재설치 | K-16 |
| `CLOCK_ANOMALY` | 기기 시계 역전/미래 | as_of > now+ε | FATAL — 미래 일자 커밋 금지 | 경고 후 진행 | 경고 배너 | 발신 | 시각 자동설정 | K-05 |
| `NOTIF_PERMISSION_DENIED` | 알림 권한 거부 | 권한 조회 | INFO(동작은 정상) — **그러나 노티 3채널 전부 무력** | 동일 | 홈 상시 배너 + 이력 화면 유도 | 불가(그래서 배너가 유일 경로) | 권한 허용 | K-20(제안) |

**설계 규칙 4개** (카탈로그를 관통하는 원칙 — 개별 항목보다 이 규칙이 상위다):

1. **부분 결측은 실패가 아니다.** K-01·K-18 하에서 야후계 결측은 상시 발생한다. 이를 FATAL로 두면
   매일 실패 노티가 오고 사용자는 3일 안에 노티를 끈다 — 그 순간 시스템의 유일한 출력 채널이 죽는다.
   FATAL은 `EVAL_IMPOSSIBLE` / `STORE_WRITE_FAILED` / `ASSET_INTEGRITY_FAILED` / `CLOCK_ANOMALY` /
   `NET_OFFLINE`(재시도 소진 후) **5종뿐**이다.
2. **노티 예산.** `tick_failure` 채널은 동일 사유 코드에 대해 **1일 1건**. 3영업일 연속 동일 실패면
   문구를 격상한다("3일째 확정 틱 실패 — 설정을 확인하세요"). 테스트로 강제(연속 실패 시나리오에서 발신 수 assert).
3. **억제는 표시한다.** 경보를 보내지 않기로 한 결정(`LOW_COVERAGE_SUPPRESSED`, 노티 예산 소진)은
   **반드시 화면·이력에 남는다**. "알림이 안 왔다"와 "억제했다"를 구분할 수 없으면 그것이 조용한 실패다.
4. **확정 수집 실패는 프리뷰 값으로 메우지 않는다.** 확정 틱은 `lane='CONFIRM'` 행만 읽는다(§9-B-2a).
   당일 장중 프리뷰가 남긴 부분값을 종가 자리에 채우면 원장이 불가역적으로 오염되고 국면이 그 위에서 커밋된다.
   실패한 지표는 **결측**으로 남기고 `raw_coverage`를 떨어뜨리는 것이 정답이다(D-02·D-17 §3).
5. **degraded는 배지가 아니라 값이다.** 지표별 상태는 UI 장식이 아니라 도메인 타입
   (`IndicatorStatus = OK | STALE | MISSING(reason) | CARRIED | NOT_COLLECTED | DISABLED`)이며,
   Room에 기록되고 엔진 입력에 그대로 반영된다. UI는 이 값을 렌더할 뿐 판단하지 않는다.

### 4.2 노티 3채널 설계와 M1/M2 경계

| 채널 ID | 이름 | importance | 트리거 | M1 범위 | M2로 미룸 |
|---|---|---|---|---|---|
| `phase_transition` | 국면 전이 | HIGH | 확정 틱에서 phase 변경 커밋 시에만(D-17 §1 — 프리뷰는 절대 발신 불가) | 텍스트 노티: `GREEN→AMBER · composite 24.3 · as_of 2026-08-06` + 홈 딥링크 | 문안 카피, BigTextStyle, 인라인 액션, 아이콘·색 토큰, 위젯 연동 |
| `provisional_alert` | 잠정 경보 | DEFAULT | 프리뷰 composite가 crit 레벨 초과 **AND** coverage ≥ 임계(D-23 §23.3-3) | 텍스트 + `PREVIEW` 접두 + as_of 필수 | 동일 |
| `tick_failure` | 틱 실패·데이터 문제 | DEFAULT(사용자가 끌 수 있게 **별도 채널**로 분리하는 것이 목적) | §4.1 FATAL 5종 + 노티 예산 규칙 | 사유 코드 + [실행 이력] 딥링크 | 동일 |

**M1이 반드시 지켜야 할 노티 규율 3개**(문구 다듬기는 M2지만, 이건 로직이라 M1):

- **캐치업 폭주 억제**: 3일 캐치업으로 GREEN→AMBER→ORANGE→AMBER가 순차 커밋되면 개별 전이 노티는 4건이다.
  → 캐치업 중에는 개별 전이 노티를 억제하고 **완료 후 요약 1건**만 발신한다
  ("3일 따라잡음 · 최종 AMBER · 경유 AMBER→ORANGE→AMBER"). 테스트: 3일 캐치업에서 발신 수 == 1.
- **중복 억제**: 동일 `(target_date, from, to)`에 대해 1회. 멱등 재실행이 노티를 재발신하면 안 된다.
- **권한 거부 대체 경로**: `NOTIF_PERMISSION_DENIED` 상태에서 노티로 알릴 내용은 **홈 배너 큐**에 쌓이고,
  배너를 탭하면 이력 화면으로 간다. 노티가 유일 경로인 설계는 금지.

**M1에서 만들지 않는 것**: 위젯(Glance), 다이제스트·리포트 노티(LLM 산출 — M2), 알림 그룹핑·요약 스타일,
소리·진동 커스터마이즈, 노티 스누즈.

### 4.3 기능판 홈 — M1 범위와 M2 경계

M1 홈은 **단일 스크롤 화면 1개 + 이력 화면 1개 + 설정 화면 1개**, 총 3화면이다.

**홈 구성(위→아래)**:
1. 배너 영역(0~n개, 우선순위 정렬): 무결성 오류 > 시계 이상 > 오프라인 > 키 미설정 > 알림 권한 > 캐치업 진행.
2. 상태 헤더: 국면 배지(**색 + 텍스트 라벨 + 아이콘 형태** 이중 부호화 — §2.4는 M2지만 이건 정보 손실 방지라 M1 적용),
   composite(소수 1자리, tabular figures), 확정 `as_of`, 프로파일(`mobile_daily`), `registry_version`(assets에서 읽음 — 0.3.1-rc).
3. **coverage 칩**: 항상 **`raw_coverage`(이월 전 실측값)** 를 표시한다(§4.5-0의 두 값 정의 참조).
   carry-forward 반영 커버리지는 정의상 항상 100%에 수렴하므로 화면에 표시할 정보가 없다 — 표시·판정 모두 `raw_coverage`가 유일 기준이다.
4. 상위 지표 5개: id·축·severity·값·`as_of`·`IndicatorStatus` 배지.
5. 마지막 틱 요약: 시각·상태·소요·사유 코드 + [실행 이력] 진입.
6. [프리뷰 갱신] 버튼(쿨다운 상태 반영) + 마지막 프리뷰 as_of.

**M1 최소 품질선**(§2.4 전면 적용은 M2, 그러나 아래는 회귀 방지 목적으로 M1에 둔다):
터치 타깃 ≥48dp / 색+형태 이중 부호화 / 다크·라이트 모두 깨짐 없음(Material3 기본 테마로 충족) /
주요 상태 TalkBack 라벨(국면·coverage·억제 사유) / 글꼴 스케일 1.3× 레이아웃 무파손.
근거: 이 5개는 M2에서 "고치기"가 아니라 "다시 만들기"가 되는 항목이다 — 지금 넣는 비용이 가장 싸다.

**M2로 명시 이관**: 디자인 토큰 전면(Tokens.kt), 국면 팔레트, 차트(Vico), 스켈레톤·모션, 앱 아이콘·스플래시·
빈 상태 일러스트, 위젯, 리포트·다이제스트 화면, 온보딩 완성판, §2.5 상용 체감 검사.

### 4.4 온보딩·설정 최소 범위 (MT1-08d)

TASK 원문의 온보딩은 MT2-06 소속이다. 그러나 **키가 없으면 수집이 0건**이므로 M1에 최소판이 필수다.
"세분화·보강 가능, 축소 불가" 원칙에 따라 MT1-08의 하위 항목으로 추가한다(범위 확대이지 축소 아님).

**입력 항목**: `FRED_API_KEY` / `ECOS_API_KEY` / `KRX_ID`+`KRX_PW` / (옵션) KIS `appkey`+`appsecret`.
`ANTHROPIC_API_KEY`는 **M1에 없다**(브리프 §2-6: M1은 LLM 미호출) — 입력란도 만들지 않는다.

**보안(K-17) — 테스트로 강제하는 3항목**:
- 저장: `EncryptedSharedPreferences`(MasterKey/AES256-GCM, Keystore 백킹).
- Manifest: `android:allowBackup="false"` + `dataExtractionRules`로 제외 — **Robolectric에서 manifest 속성 assert**.
- 로그: `FailureRecorder`·HTTP 로깅 인터셉터가 키 문자열을 마스킹. **테스트**: 키를 주입하고 전체 로그 캡처 후
  키 부분 문자열 0건 assert. 스모크에서는 실기기 `logcat` grep으로 재확인(§4.7 증빙).

**각 키에 [검증] 버튼**: 1회 소량 실호출로 유효성 즉시 표시(성공/실패 + 사유). 이것이 `KEY_INVALID`를
"내일 알게 되는 실패"에서 "지금 아는 실패"로 바꾸는 가장 큰 레버다. 자동 테스트는 MockWebServer로,
실호출은 스모크(사용자 수행)로 검증.

**OEM 절전 예외(K-15)**: `PowerManager.isIgnoringBatteryOptimizations` 조회 → 미허용이면 안내 카드 +
`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` 딥링크. `Build.MANUFACTURER` 분기로 문구 2종(삼성 / 기타)만
M1 범위(제조사별 상세 가이드는 M2). 사이드로드 개인 단말이므로 정책 제약 없음.

**알림 권한**: targetSdk 33+ 이므로 `POST_NOTIFICATIONS` 런타임 요청. 거부해도 앱은 정상 동작하되
`NOTIF_PERMISSION_DENIED` 배너 상시(§4.1).

**초기 웜업 백필(신규 — §9-B-2에서 도출된 필수 단계)**: transforms가 전부 `min_periods=window`이므로
최초 설치 직후에는 z-score 계열 6종·drawdown 2종 등이 **전부 null**이고 시스템은 사실상 판정 불가다.
온보딩에서 **550 달력일치 원계열을 1회 백필**한다.
- 호출 수는 적다 — 대부분의 provider가 기간 조회를 지원한다(kotlin_krx `getOhlcv(start, end, ticker)`,
  FRED `observation_start/end`, 야후 `range`). 계열당 1~2회 × 14쌍 수준이며 K-03 간격만 지키면 수 분이다.
  백필 행은 전부 `lane='CONFIRM'`으로 적재한다(§9-B-2a) — 프리뷰가 아니라 이력 복원이기 때문이다.
- 중단 재개 가능해야 한다(계열별 완료 체크포인트). 실패한 계열은 `WARMUP_INSUFFICIENT`로 남고 재시도 가능.
- 진행 표시 필수(INV-2): "이력 수집 중 3/13 계열 · 550일". 완료 전에도 앱은 사용 가능하되
  홈 배너로 "판정 정확도 제한"을 상시 고지한다 — 이것이 없으면 **첫 며칠간 시스템이 조용히 틀린다**.
- 백필 데이터도 원장 append 규율을 그대로 따른다(`observed_at`=수집 시각, `as_of`=행 날짜,
  `pit_quality`는 틱 단위 개념이므로 여기서는 무관).

**온보딩 순서**(각 단계 건너뛰기 허용, 미완료는 홈 배너 상시 노출):
알림 권한 → 키 입력·검증 → 절전 예외 → **초기 웜업 백필(550일)** → 첫 확정 틱 수동 실행 → 홈.

**설정 화면 기타**: CSV 내보내기 실행 / Drive 백업 on-off(MT1-03d) / 확정 틱 시각 **읽기 전용 표시** /
캐치업 상한 표시 / 앱·레지스트리 버전 / 실행 이력 진입.

### 4.5 프리뷰 D-23 억제 UX (MT1-07d)

D-23 §23.3의 5개 규율을 화면 상태로 번역한다.

**§4.5-0. 커버리지 두 값의 분리 (내부 모순 해소 — 이 정의가 §4.3-3·§10 07c를 동시에 성립시킨다)**

D-23 §23.3-1(carry-forward로 **분모를 유지**)과 §23.3-3(coverage < 80%면 **판정 억제**)을 한 값으로 구현하면
모순이다 — 이월이 분모를 채우면 커버리지는 100%가 되어 억제가 영원히 발동하지 않는다. 두 값을 분리한다:

| 값 | 정의 | 용도 | 값의 범위 |
|---|---|---|---|
| **`raw_coverage`** | **이월 전** 실측 유효가중 / 전체가중 | **억제 판정의 유일 기준 · 화면 칩 표시 · `run_log` 기록** | 결측만큼 하락(예 21.0/31.0) |
| `filled_coverage` | 이월 후 유효가중 / 전체가중 | composite 계산 분모가 유지됐는지 확인하는 **내부 진단값**(화면·판정 미사용) | 대개 1.0 |

- composite는 **이월값을 포함해** 계산한다(D-23 §23.3-1의 목적 = 국내 침묵이 점수에서 사라지는 방향성 오류 제거).
- 억제 판정은 `raw_coverage < engine.preview_coverage_min`(P-1). 따라서 "이월로 왜곡은 줄이되, 실측이 부족하면
  판정은 하지 않는다"가 된다 — D-23 §23.3-1과 §23.3-3이 각자의 목적을 온전히 달성한다.
- `run_log.coverage`에는 `raw_coverage`를 기록한다(사후에 "그날 실측이 얼마였나"가 답 가능해야 한다).

**표시 반올림 규칙(K-07 — 반올림은 표시 계층에서만)**: 내부는 전부 `Double` 원값.
화면·문서·노티 문구의 커버리지는 **백분율 소수 1자리 반올림(HALF_UP)** 으로 단일화한다
→ 21.0/31.0 = **67.7%**, 29.5/31.0 = **95.2%**. 테스트는 표시 문자열이 아니라 **분수식**(`21.0/31.0`)과
비교한다 — 리터럴 `0.6774…`를 코드·테스트에 쓰지 않는다.

| 조건 | composite 표시 | 국면 | 잠정 경보 | 지표 카드 | 접근성 |
|---|---|---|---|---|---|
| `raw_coverage` ≥ 임계 | 정상 수치 + `PREVIEW` 배지 + as_of | "잠정" 표기(커밋 아님) | crit 초과 시 발신 허용 | CARRIED는 "이월 · as_of" 배지 | 표준 |
| `raw_coverage` < 임계 | **수치를 지우지 않는다** — 저대비 + "참고용" 라벨 + 경고 아이콘 | **"국면 판정 불가"** | **억제** + 억제 사실 표시 | 동일 | TalkBack: "국면 판정 불가, 실측 커버리지 67.7 퍼센트, 참고용 수치 45.2" |

**설계 판단 3건과 근거**:

1. **"흐림 처리"를 시각 효과만으로 구현하지 않는다.** D-23 §23.3-3의 "흐리게 처리"를 blur/alpha로만 만들면
   스크린리더 사용자에게는 **아무 변화가 없다** — 억제 사실이 전달되지 않는다. 따라서 저대비 처리와
   **텍스트 라벨·contentDescription을 동반 필수**로 규정한다. AAA §2.4의 이중 부호화 원칙을 M1에 선적용하는 것이다.
2. **수치를 감추지 않는다.** 감추면 사용자는 "계산이 안 됐다"로 읽는다. 실제로는 "계산은 됐으나 입력이 부족해
   판정에 쓸 수 없다"이며, 이 둘은 다른 사실이다. D-23 §23.2의 핵심(66.7 vs 45.2 = 방향성 오류)을
   사용자에게 이해시키려면 **수치와 커버리지를 나란히** 보여야 한다.
3. **CDS 상시 미수집(G-4 (b) 채택 시)의 문구**: coverage 상한이 95.2%로 고정된다(F-5). 칩에
   `95.2% (설계상 미수집 1건 제외)`처럼 **설계상 결손과 장애성 결손을 구분**해 표기한다. 이 구분이 없으면
   사용자는 매일 데이터 장애를 본다.

**carry-forward 격리**(MT1-07b, TASK 완료 기준 ②):
- `preview` 모듈에만 `CarryForwardResolver`가 존재하고, `work`(확정 틱) 모듈은 이 모듈에 **의존 자체가 없다**.
- 강제 수단 2중: (i) Gradle 모듈 경계 — `:feature:preview`가 `:core:engine`에 의존하고 `:app:work`는
  `:feature:preview`에 의존하지 않음 (ii) Konsist 아키텍처 테스트 — `work` 패키지에서 `CarryForward*`
  심볼 참조 0건 assert. 모듈 분리만으로도 컴파일 차단이 되지만, 같은 모듈로 합쳐질 미래를 대비해 테스트도 둔다.
- 쓰기 격리: `CarryForwardResolver`는 Room 쓰기 인터페이스(`LakeWriter`)를 **생성자로 받지 않는다** —
  타입 수준에서 원장 오염이 불가능(D-23 §23.3-1 후단 "이월값은 Room에 새 레코드로 쓰지 않는다").
- **읽기 격리(개정): 이월 원천은 `observation`이 아니라 `tick_input`이고, 반환 타입은 `severity`다.**
  초안은 `loadSeries(lanes={CONFIRM})`로 **원계열 관측값**을 읽게 했으나, 소비자가 필요로 하는 것은
  **severity 맵**이라 계층이 단절된다. 그 단절을 메우는 두 경로가 모두 막혀 있다:
  ① 원값을 임계에 직접 넣는 분류 = **오류**(severity는 transform 출력 기준 — 원 VIX≈15를 z 임계
  `watch 1.5/warn 2.0/crit 3.0`에 넣으면 z 계열 6지표가 상시 severity 3이 된다)
  ② 원계열을 재주입해 재변환 = **§9-B-2 4단계 순서 위반**(가시성 필터가 transform보다 앞서게 된다).

  ```kotlin
  data class Carried(val severity: Int, val asOf: Long)   // asOf는 "이월 · as_of" 배지용(§4.5)

  class CarryForwardResolver(private val tickInputDao: TickInputDao) {  // LakeDao·lanes 참조 없음
      // 반환이 곧 severity — 원값→severity 도출 경로가 아예 존재하지 않는다
      fun lastConfirmed(): Map<String, Carried> =
          tickInputDao.latestCommitted()?.indicatorDetail?.carriedById ?: emptyMap()
  }
  ```
  - **원천**: `tick_input`의 **가장 최근 커밋 행**(§9-B-4b)의 `indicator_detail.severity`.
    `tick_input`은 확정 틱만 쓰므로 "직전 **확정**값"(D-23 §23.3-1 전단)이 **구조적으로 보장**된다 —
    lane 필터를 지킬 필요조차 없어진다(지킬 대상이 애초에 없다).
  - **타입 수준 강제(C-19에서 확립한 패턴을 그대로 적용)**: 이 클래스는 `lanes`도, `LakeDao`도 받지 않는다.
    잘못된 원천에 접근할 **참조 자체가 없다**.
  - **`tick_input` 0행(설치 직후·첫 확정 틱 전)**: 이월하지 않고 **결측 유지**한다(빈 맵 반환).
    없는 확정값을 지어내지 않는 것이 D-23 §23.3-1의 직독이며, 그 결과 `raw_coverage`가 낮아
    억제가 걸리는 것이 정상 동작이다(§4.1 `WARMUP_INSUFFICIENT`와 같은 상태를 공유). 증인 M-50.
  - 증인은 아래 M-43b 표 ③행.

**완료 기준 재현 테스트**(TASK ③④):
- ③ KR 4지표(+G-4 결정에 따른 CDS) 결측 시나리오 → `raw_coverage == 21.0/31.0`(분수식 비교, 리터럴 금지),
  `raw_coverage < engine.preview_coverage_min` → 판정 억제·잠정 경보 미발신·억제 사실 표시 assert.
- ④ D-23 §23.2 수치 예 재현 — **3값을 같은 테스트에서 동시 산출**한다(하나만 재현하면 "방향성 오류"가 증명되지 않는다):
  (i) **이월 없음** 프리뷰 = 100×(21.0×2)/(21.0×3) = 66.666…(D-23이 문제로 지목한 값)
  (ii) 서버/확정 동시각 = 100×(21.0×2 + 10.0×0)/(31.0×3) = 45.161…
  (iii) **본 계획이 구현하는 프리뷰**(이월 후 composite + `raw_coverage` 억제) = (ii)와 동일 산식에 수렴,
  그리고 `raw_coverage` 21.0/31.0 < 임계이므로 **여전히 억제**.
  → (i)과 (iii)의 차이가 D-23 §23.3-1이 존재하는 이유이고, (iii)이 여전히 억제된다는 사실이 §23.3-3이
  §23.3-1에 흡수되지 않았음의 증거다.

### 4.6 캐치업 UX와 실행 이력 화면 (K-14)

**실행 이력 화면의 존재 이유를 한 문장으로 고정한다: "왜 오늘 알림이 없었는가"에 답하는 화면.**
이 문장을 만족하지 못하는 이력 화면은 반려 대상이다.

**`run_log` 스키마(제안)**:
`id, run_type(CONFIRM|PREVIEW|CATCHUP), target_date(KST 영업일), started_at(UTC), finished_at(UTC),
status(SUCCESS|PARTIAL|SUPPRESSED|SKIPPED_HOLIDAY|FAILED|DEDUPED), reason_code, coverage, composite,
phase_from, phase_to, missing_indicators(json), trigger_source(WORKMANAGER|APP_LAUNCH|MANUAL),
attempt, pit_quality(LIVE|BACKFILL), duration_ms`

- **`pit_quality`가 핵심이다.** 캐치업은 과거 일자를 **오늘 조회**하므로 개정치가 섞일 수 있다(K-11·D-06).
  계약상으론 `observed_at≠as_of`로 표현되지만, 사용자·게이트에게는 "이 국면은 소급 산출이다"가 보여야 한다.
  이력 항목에 `소급` 태그를 달고, 홈의 국면 배지에도 마지막 커밋이 BACKFILL이면 작은 표기를 남긴다.
- `run_log`는 **원장(lake)이 아니라 운영 로그**다 → append-only 물리 강제(update/delete DAO 미구현)의 대상은
  lake이고, `run_log`는 보존 정리(권고 180일)를 허용한다. 이 경계를 코드·테스트에서 명시 분리하지 않으면
  MT1-03의 아키텍처 테스트와 모순된다(결정 U-8).
- **`run_log`는 상태기계 재구성용이 아니다** — `distinct_axes`·`any_crit`·`any_extreme`가 없어 fold를 복원할 수
  없고, 애초에 purge 대상이라 복원 근거로 쓸 수 없다. 엔진 입력의 동결 보존은 **별도 테이블 `tick_input`**
  (§9-B-4b, append-only·purge 금지)이 담당한다. 두 테이블은 목적·수명·강제 규율이 전부 다르다.

**캐치업 동작 규칙**:
1. 트리거: 앱 콜드 스타트 + WorkManager 실행 시. 두 경로 모두 동일 함수 진입(중복은 Mutex+UNIQUE로 흡수).
2. 대상 열거: 마지막 성공 `target_date` 이후의 **KR 영업일만**. 상한은 **SSOT 값**
   `profiles.mobile_daily.catchup_max_ticks`(**권고 20** — 도출은 §4.6-B, P-11) — 앱 상수가 아니다(§4.6-A 논거).
   상한과 별개로 **벽시계 예산 10분**(INV-2)을 두고, 예산 소진 시 남은 일자는 절단과 같은 경로로 처리한다.
3. 순차 처리: 오래된 날부터 1일씩 커밋. **한 일자의 실패가 전체를 중단시키지 않는다** —
   실패 일자는 `FAILED`로 기록하고 다음 일자로 진행(중단하면 영구 정체된다).
4. 진행 표시: "3일 따라잡는 중 (1/3 · 2026-08-04)". 무한 스피너 금지(INV-2). 캐치업 중 프리뷰 버튼 비활성.
5. 완료: 요약 노티 1건(§4.2), 이력에 n건 append.
6. 멱등: `(run_type=CONFIRM, target_date)` UNIQUE + phase 커밋 테이블 `(target_date)` UNIQUE.
   두 번째 시도는 `DEDUPED` no-op. WorkManager는 `enqueueUniquePeriodicWork(KEEP)`.
7. **간극 절단은 카운터를 건드리지 않고 공시한다**(§4.6-A·§9-B-4c): 상한·예산 초과로 누락된 일자는
   `tick_input`에 행이 생기지 않고, fold는 남은 행만 이어서 재생한다(엔진 변경 없음).
   누락 영업일 수를 `tick_input.gap_before`에 기록하고 `run_log`(`CATCHUP_GAP_TRUNCATED`)·이력 화면·
   국면 배지("간극 이후")에 노출한다. 이월로 생기는 오차의 상한(**강등 최대 2틱 조기, 승격 오차 0**)은
   §9-B-4(c) 실측표로 고정되며 GATE_GM1에 기록한다.

**§4.6-A. 캐치업 상한은 판정 영향값이다 (자기 원칙 §8 말미와의 정합)**

초안에서 이 값을 "판정 무관 앱 상수"로 분류했던 것을 **철회한다**. 근거:
`mobile_daily` 히스테리시스는 전부 **틱 단위 카운트**다(`promote_sustain 1 / demote_below 3 / min_dwell 5 /
reentry_cooldown 2` — statemachine.yaml L39-44). 상한 밖 거래일이 영구 누락되면 그 틱들이 카운터에서
사라지므로 **이후 전이 판정 자체가 달라진다** — 예: 누락 구간을 사이에 두고 강등 스트릭이 이어 붙으면
실제로는 연속이 아닌 3틱이 `demote_below_ticks: 3`을 충족해 조기 강등이 커밋된다. 이는 D-25 §1·§2가
정의한 "연속 충족"의 위반이다. 따라서 두 조치를 함께 취한다:

1. 상한값을 **SSOT로 승격**한다(P-11 — 같은 블록의 다른 틱 카운트 파라미터와 동거). 상한이 몇이냐에 따라
   fold에 들어가는 틱 시퀀스가 달라지므로 이 값은 판정에 직접 영향한다.
2. 그 영향의 **크기를 실측으로 유계화하고 공시한다**(§9-B-4c: 강등 최대 2틱 조기·승격 오차 0).
   초안은 여기서 "카운터 리셋"을 요구했으나, 리셋은 엔진 변경을 유발하면서 dwell 차원에서는 오히려
   **덜 정확**하다(실제로 흐른 체류 시간을 지운다) — §9-B-4(c)에서 철회했다.

**비교 — 프리뷰 쿨다운(U-9)은 왜 SSOT가 아닌가**: 프리뷰는 국면을 커밋하지 않으므로(D-17 §1)
그 실행 빈도는 틱 카운터에 **어떤 값도 입력하지 않는다**. 상태기계의 입력 시퀀스에 영향을 주는지가
"판정 영향값"의 판별식이며, 캐치업 상한은 영향을 주고 프리뷰 쿨다운은 주지 않는다.

**§4.6-B. 상한값 20의 도출 (초안의 "10 = 2주 부재 실용선"을 철회)**

초안의 10은 `mobile_daily` 히스테리시스 상수 합
(`demote_below 3 + min_dwell 5 + reentry_cooldown 2 = 10`)과 **우연히 같고 여유가 0**이었다.
"실용선"은 도출이 아니라 어림이었으므로 철회하고 아래로 대체한다.

- **재생해야 하는 최소 길이 = 완결 사이클 1회**: 승격 커밋 → `min_dwell_ticks(5)`를 채우고 →
  `demote_below_ticks(3)` 스트릭으로 강등 → `reentry_cooldown_ticks(2)` 소진 = **10틱**.
  이보다 짧게 재생하면 진행 중이던 사이클이 항상 절단된다.
- **상한 = 2 × 완결 사이클 = 20틱**: 절단이 발생하는 경우에도 **직전 완결 사이클 하나는 온전히 재생**되어,
  리셋 이후의 국면이 실제 이력에 근거한다. 여유 0(10)은 "절단되면 사이클 정보가 전부 사라지는" 지점이다.
- **안전성 논거의 방향 정정**: 간극 이월의 오차가 §9-B-4(c)에서 **강등 2틱 조기·승격 0으로 유계**임이
  실측됐으므로 상한 초과는 파국이 아니라 **정보 손실량 + 유계 오차**의 문제다. 그렇다면 상한을 빠듯하게
  잡을 이유가 없고, 오히려 넉넉히 잡아 간극 자체를 줄이는 편이 오차를 줄인다. 이것이 10 → 20 상향의 실질 근거다.
- **비용 검증(20이 실행 가능한가)**: KRX는 호출 간 최소 1초(K-03)이고 KR 지표는 4종 →
  일자당 KRX 약 4초, 나머지 provider는 병렬. 20일 ≈ **KRX 80초 + 병렬분** — 벽시계 예산 10분 안에 들어온다.
  예산을 넘기면 §4.6 규칙 2대로 절단 경로로 합류하므로 **상한을 올려도 최악 소요는 예산이 상한**이다
  (INV-2 불변). 값 20은 `catchup_max_ticks`(P-11)로 SSOT에 두어 C1에서 재보정 가능하다.
- 부수: 20영업일 ≈ 4주로, 휴가·기기 교체 같은 현실적 부재를 덮는다(이것은 결과이지 근거가 아니다).

**휴장일 규율(중요 — 단순 절약이 아니다)**:
KR 휴장일에 확정 틱을 돌리면 KR 4지표(가중 8.5)가 결측이고 D-23 §23.3-4에 따라 **확정 틱은 carry-forward
금지·분모 제외**이므로, composite가 글로벌 지표만으로 산출된다 — 이는 D-23 §23.2가 규정한 **방향성 오류를
확정 국면에 직접 주입**하는 것이다. 따라서 "휴장일 스킵"은 PIT·정합성 요건이며 반드시 테스트로 강제한다.
백테스트 하니스도 KR 거래일에만 틱을 도므로 이 규칙이 하니스-앱 정합의 조건이기도 하다.
→ 결정 U-11(D-27 신설 제안)로 상신.

**영업일 판정 경로**: kotlin_krx `getBusinessDays`/`getNearestBusinessDay`(KRX 원천, `exchange_calendars`
XKRX의 2026 휴장 미반영 문제 회피) → 결과를 Room에 캐시(연 1회 + 월 1회 갱신) → 조회 실패 시 캐시 →
캐시도 없으면 **보수 규칙(주말만 스킵) + `CALENDAR_FALLBACK` 플래그**. 빈 응답 교차 확인은 F-11(파싱 실패와
휴장이 동일 반환) 때문에 **단독 근거로 쓰지 않는다**.

### 4.7 실기기 1일 스모크 절차와 GM1 증빙

산출물: `docs/runbooks/M1_SMOKE.md`(신규) + `scripts/smoke_collect.ps1`(신규, Windows PowerShell).

**절차는 2층으로 분리한다** — 게이트 명시 조건(TASK_mobile_m1 완료 기준: "확정 틱 1회 + 프리뷰 3회 정상")을
충족하는 **필수 절차**와, 실기기 실패 경로 증거를 얻는 **확장 절차**다. 확장은 사용자 시간을 추가로 쓰므로
**결정 U-15로 상신**하며, 미승인 시 필수 절차만으로 GM1을 진행한다(그 경우의 한계는 §14-4에 기록).

**필수 절차 (게이트 정본 — 사용자 실작업 약 25분, 대기 제외)**

| 단계 | 시각(KST) | 수행 | 주체 | 증빙 |
|---|---|---|---|---|
| S0 | D-1 저녁 | 디버그 APK 설치, 온보딩 완주(키 [검증] PASS), 절전 예외 허용, 알림 권한 허용, 시각 자동설정, 배터리 ≥50% | **사용자(U1~U3)** | 설정·키 검증 스크린샷 2 |
| S3 | D-day 09:35 | **프리뷰 1회**(장중 — KRX 장중 제약으로 `raw_coverage` 저하 예상) → 억제 UX 확인 | 사용자(U4) | 스크린샷 + coverage 기록 |
| S4 | D-day 12:30 | **프리뷰 2회** | 사용자(U4) | 스크린샷 |
| S7 | D-day 17:00~18:30 | **확정 틱 자동 실행 대기**(K-14 지연 허용 창). 노티 수신·홈 갱신 확인. 18:30 미실행이면 절전/OEM 이슈로 기록(K-15) | 사용자(U5) | 노티·홈 스크린샷, 실행 시각 |
| S8 | D-day 17:30+ | **프리뷰 3회**(확정 후 — coverage 정상 예상) | 사용자(U4) | 스크린샷 |
| S10 | D-day 19:00 | 증빙 수집 스크립트 실행 | 사용자(U8, 스크립트 제공) | 아래 파일 일괄 |

**확장 절차 (U-15 승인 시 — 추가 약 25분)**

| 단계 | 시각 | 수행 | 얻는 증거 |
|---|---|---|---|
| S1·S2 | D-1 저녁 / D-day 09:30 | 전날 앱 미실행으로 **누락일 생성** → 앱 실행 시 캐치업 1건 + 요약 노티 1건 관찰 | K-14·RC-4·RC-5 실기기 증거(JVM 테스트로는 WorkManager 실지연을 못 만든다) |
| S5 | D-day 13:00 | 비행기 모드 ON → 프리뷰 → `NET_OFFLINE` UX → OFF | RC-1 |
| S6 | D-day 13:10 | FRED 키 1글자 변조 → [검증] → `KEY_INVALID` → 원복·재검증 | RC-8·§4.4 검증 버튼의 실효 |
| S9 | D-day 18:40 | 앱 강제 종료 → 재실행 → 캐치업 미발생·`DEDUPED` 확인 | 멱등(TASK MT1-06 요구)의 실기기 확인 |

> S9(멱등)는 TASK MT1-06 완료 기준의 핵심이므로, U-15 미승인 시에도 **S9만은 필수로 승격**할 것을 권고한다
> (소요 2분). 나머지 3건은 JVM 테스트에 대응 케이스가 있어 실기기 확인이 이중 확인의 성격이다.

**증빙 수집(`scripts/smoke_collect.ps1`)** — 산출 위치 `docs/gates/evidence/GM1/`:
1. `logcat_YYYYMMDD.txt` — 전량 덤프. **동시에 K-17 검증**: 키 문자열 grep 0건을 스크립트가 자동 판정해
   PASS/FAIL 출력(사람이 눈으로 찾게 하지 않는다).
2. `run_log_YYYYMMDD.csv` — 앱의 CSV 내보내기(MT1-03d) 결과. **스모크가 이 기능의 첫 실사용자**다.
3. `screenshots/` — 필수 절차 5종(설정·키검증·프리뷰3·확정 틱·노티), U-15 승인 시 확장 4종 추가.
4. `lake_YYYYMMDD.db` — `adb exec-out run-as <pkg> cat databases/…`(디버그 빌드 한정).
   as-of 쿼리·append-only를 오프라인에서 재현 검증 가능하게 한다.
5. `device_info.txt` — `adb shell getprop`(모델·OS·제조사), 절전 예외 상태, 알림 권한 상태.
6. `checklist_signed.md` — 필수 절차(S0·S3·S4·S7·S8·S10) 각 항목 O/X + 실제 시각 + 특이사항(사용자 기입).
   U-15 승인 시 확장(S1·S2·S5·S6·S9) 행 추가.

**GM1 판정에 쓰는 방식**: GATE_GM1.md의 §수치 증빙에 `run_log_*.csv`의 행을 그대로 인용한다
(필수: 확정 틱 1행 + 프리뷰 3행 / U-15 승인 시: 캐치업 1행·`DEDUPED` 1행·실패 경로 2행 추가). 스크린샷은 보조 증거이고,
**1차 증거는 `run_log`와 `logcat`**이다 — 사람의 서술이 아니라 기계 기록이 게이트를 통과시킨다
(REVIEW_M0 신설 규율 ③④의 정신을 실기기 검증으로 확장).

---

## 5. 확정 틱 시각 결정 — 17:00 KST 채택 논증

**결론: 17:00 KST 채택 권고. 16:20은 기각.** 근거 5개(강한 순):

1. **자기모순 제거(F-1)**: `schedules.collection.daily_kr = 16:50`인데 확정 틱을 16:20에 돌면
   **그날의 KR 수집이 끝나기 30분 전에 확정을 선언**하는 것이다. SSOT 내부에서 이미 모순이다.
2. **PIT 오염 위험**: KRX 투자자별 거래대금·파생 지수는 장 마감(15:30) 후 지연 확정된다. 16:20 시점 값이
   잠정치라면, 그것을 확정 틱으로 원장에 append한 뒤 정정치가 오면 revision 처리가 필요해진다
   — append-only 원장에 불필요한 개정 흐름을 매일 만든다.
3. **SSOT 수렴**: 17:00은 `statemachine.yaml`의 서버 `kr_close` 평가 틱과 동일 시각이고
   `replay.yaml confirm_time_kst`와도 같다. **세 곳의 숫자가 하나로 수렴**한다 — D-23 §23.5의
   "차이의 설명 가능성"에서 설명해야 할 차이가 하나 줄어든다.
4. **하니스 무감 + 물리 논증(F-2·AD-3)**: BT-03은 하니스가 이 값에 완전 무감함을 실측했고, 선정 근거를
   물리·스케줄 논증에 뒀다. M1의 재확인은 그 물리 논증을 **실기기 실측으로 검증**하는 것이다(V-2).
5. **UX**: 사용자가 퇴근길·저녁에 앱을 열 때 국면이 이미 확정돼 있다. 16:20은 시간외 거래 중이라
   "확정"이라는 라벨의 신뢰가 떨어진다.

**실측 조건(V-2, 블로킹)**: kotlin_krx로 **17:00 시점에 당일 데이터가 확정 조회되는가**를 3영업일 연속 확인한다
— (a) `getIndexOhlcv`(1001) 당일 종가 (b) `getMarketTradingByInvestor` 당일 외국인 순매수 (c) `getVkospi` 당일값.
셋 중 하나라도 17:00에 미확정이면 후보를 17:30 → 18:00 순으로 올린다.

**지연 허용 창(K-14)**: WorkManager는 정시를 보장하지 않는다. 설계는
`PeriodicWorkRequest(repeatInterval=1d, flexInterval=…)` 대신 **"매일 17:00 이후 첫 실행 가능 시점"**으로 잡고,
17:00~23:59 사이 실행은 전부 정상으로 간주한다(그날 데이터가 이미 확정이므로 지연이 무해). 24:00을 넘기면
다음날 캐치업 경로가 처리한다. **정확 알람(`setExactAndAllowWhileIdle`)으로 우회하지 않는다**(K-14 명시).

**정합 확인 표**:

| 항목 | 값 | 17:00과의 관계 |
|---|---|---|
| KRX 정규장 마감 | 15:30 | +90분 여유 |
| `collection.daily_kr` | 16:50 | +10분 — 수집 후 확정 |
| `collection.daily_us`(전일 미국 종가) | 07:20 | 당일 09:40 전 확보 |
| `stale_profiles.mobile_daily.daily_us` | 48h | 전일 미국 종가(약 11시간 전) 유효 |
| `stale_profiles.mobile_daily.daily_kr` | 30h | 당일 확정치 유효 |
| 서버 `kr_close` 평가 틱 | 17:00 | **동일** |

**SSOT 문언 정정 필요**(§8 P-7): `TASK_mobile_m1.md` MT1-06 "16:20 KST", `ARCHITECTURE_SPLIT.md` D-15 표·
§1 "16:20 KST 가설" 2곳.

---

## 6. 리스크 × K-xx 매핑과 완화

| ID | 리스크 | K-xx | 발현 지점 | 완화 | 검증 명령 |
|---|---|---|---|---|---|
| RC-1 | 야후 엔드포인트 차단·스키마 변경으로 글로벌 8지표 동시 결손 | K-01·K-18 | MT1-04a | Stooq 폴백 사전 실측(V-1) + 지표별 결측 격리 + `YAHOO_SHAPE`/`STOOQ_FALLBACK_USED` 사유 코드 + 출처 표기 | `--tests "*YahooFallback*"` |
| RC-2 | KRX 세션 만료/로그인 정책 변화로 KR 4지표(8.5) 동시 결손 → 확정 틱 composite 왜곡 | K-03 | MT1-04c·06b | login 재시도 1회 + 실패 시 **틱을 PARTIAL로 진행하되 coverage 하락을 이력·UI에 노출**. coverage가 확정 틱에서도 급락하면 `tick_failure` 발신(임계는 결정 U-2와 별개 — 권고: 확정 틱 coverage < 임계 시 경고 노티, 커밋은 D-23 §23.3-4대로 수행) | `--tests "*KrxSession*"` |
| RC-3 | 노티 피로로 사용자가 채널을 끔 → 유일 출력 채널 상실 | K-15 | MT1-06e·08a | FATAL 5종 한정 + 1일 1건 예산 + 3일 격상 + 채널 분리(실패 채널만 끌 수 있게) | `--tests "*NotificationBudget*"` |
| RC-4 | OEM 절전이 WorkManager를 죽여 며칠 침묵 | K-14·K-15 | MT1-06a·08d | 온보딩 절전 예외 안내 + 앱 실행 시 캐치업 + **틱 누락을 이력·홈에 노출**(마지막 확정 경과 시간 상시 표시) | `--tests "*Catchup*"` + 스모크 S7 |
| RC-5 | 캐치업 노티 폭주(3~10건) → 사용자 불신 | K-14 | MT1-08a | 캐치업 중 개별 전이 노티 억제 + 완료 요약 1건 | `--tests "*CatchupNotification*"` |
| RC-6 | 캐치업 소급 수집이 개정치를 섞어 국면이 "그날 알 수 없던 값"으로 결정 | K-11·K-05 | MT1-06d | `pit_quality=BACKFILL` 기록 + 이력 "소급" 태그 + 소급 상한 + as-of 정렬 유지 | `--tests "*BackfillPitQuality*"` |
| RC-7 | assets 드리프트로 앱이 다른 임계로 판정 | K-16 | MT1-01b | Gradle `verifyConfigHashes`가 `check`에 포함(빌드 시 차단) + Robolectric 런타임 검증 + `ASSET_INTEGRITY_FAILED`로 틱 차단 | `./gradlew verifyConfigHashes` |
| RC-8 | API 키가 logcat·백업으로 유출 | K-17 | MT1-08d | EncryptedSharedPreferences + `allowBackup=false` + 로그 마스킹 + **스모크 logcat grep 자동 판정** | `--tests "*KeyRedaction*"` + `smoke_collect.ps1` |
| RC-9 | 알림 권한 거부가 조용한 실패로 귀결 | K-20(제안) | MT1-08a·08d | 배너 상시 + 이력 대체 경로 + 온보딩 요청 | `--tests "*NotifPermissionDenied*"` |
| RC-10 | 파싱 실패가 휴장으로 위장(F-11) | K-19(제안) | MT1-04c·06a | 영업일 캐시를 1차 근거로, 빈 응답은 보조 근거로만. 어댑터가 `EMPTY_OK`/`ERROR`를 구분 반환 | `--tests "*HolidayVsParseError*"` |
| RC-11 | 확정 틱이 휴장일에 글로벌만으로 국면 커밋 → 방향성 오류 주입 | K-03·D-23 | MT1-06a | 휴장일 커밋 금지 규율(결정 U-11) + 테스트 | `--tests "*HolidaySkip*"` |
| RC-12 | ECOS `item_code` 미해소로 credit 축 2/3 결손(F-7) | K-04 | MT1-04d(V-3) | 실측 선행 필수. 실패 시 `krx_credit_spread_delta` 결손을 **G-4와 함께 GATE_GM1에 명시 기록**(credit 축 발화 표면 축소 2건 누적) | V-3 실측 보고 |
| RC-13 | VKOSPI 소스가 서버와 갈려 국면 타임라인이 설명 불가하게 어긋남 | K-02 | MT1-04c | M1은 폴백 강제 + 실측 VKOSPI 병행 수집·미사용(결정 U-4) | BT-05 패리티 |
| RC-14 | 프리뷰 남용으로 제공자 쿼터 소진 → 확정 틱까지 실패 | K-10류 | MT1-07a | 프리뷰 쿨다운·일 상한 + **확정 틱용 호출 예산을 프리뷰와 분리 계상** | `--tests "*PreviewCooldown*"` |
| RC-15 | 기기 시계 왜곡으로 미래 일자 커밋 | K-05 | MT1-06b | `CLOCK_ANOMALY` 가드(as_of > now+ε 차단) + UTC 저장·KST 표시 규율 | `--tests "*ClockAnomaly*"` |
| RC-16 | 스테일 기준을 `as_of`(달력일)로 구현 → 결측·지연이 낀 전 틱에서 패리티 파손, 당일 값이 즉시 스테일로 오판 | K-05·F-13 | MT1-03b·05a | `visible_at` **파생** 규칙 포팅(§9-B-1) + kind별 as_of cutoff 역산 조회(§9-B-2) + cadence 폴백 이식 + L1 계층 게이트(§9-C) | `--tests "*VisibleAtRuleTest*"` |
| RC-17 | 캐치업 간극이 히스테리시스 스트릭을 이어 붙여 조기 강등 | K-14·K-11 | MT1-06d | 상한 SSOT화(P-11, 값 20)로 간극 자체를 줄임 + 오차 상한 실측 유계화(강등 2틱·승격 0, §9-B-4c) + `gap_before` 공시 + D-25 부기(P-12). 엔진은 변경하지 않는다 | `--tests "*CatchupTest*"` (상한 증인 포함) |
| RC-20 | 최초 설치 후 웜업 부족으로 z 계열 6종이 상시 결측 → 판정 불가인데 사용자는 정상으로 오해 | — | MT1-04·08d | 온보딩 550일 백필(§4.4) + `WARMUP_INSUFFICIENT` 배너·배지 + 진행 표시 + 중단 재개 | `--tests "*WarmupBackfillTest*"` |
| RC-21 | 원계열을 먼저 가시성 필터로 자른 뒤 transform → 롤링 통계가 달라져 BT-05 L1·L2 확정 실패 | F-13 | MT1-05a | §9-B-2의 4단계 순서 고정 + Konsist 강제(transform 인자에 lookup/cutoff 결과 금지) + L2 계층 게이트 | `--tests "*TransformOrderTest*"` |
| RC-22 | 프리뷰가 append한 장중 부분값이 확정 틱에 잡혀 **종가로 동결**(불가역, append-only라 회수 불가) | D-17 §3·D-06 | MT1-03a·06b | `lane` 판별자 + **M-43b 읽기 지점 전수표**(3지점 × lanes × 증인, §9-B-2a) | `--tests "*PreviewLaneIsolationTest*"` |
| RC-24 | carry-forward가 **자기 프리뷰 값을 "직전 확정값"으로 이월** → D-23 §23.3-1 무력화(장중 부분값이 분모를 채우고 스테일 배지도 없음) | D-23 §23.3-1 | MT1-07b | 원천을 `tick_input`(확정 틱 전용) 마지막 커밋 행으로 고정 — `CarryForwardResolver`가 `LakeDao`·`lanes` 참조를 아예 갖지 않아 프리뷰 데이터에 **닿을 수 없다**(M-43b ③행) | `--tests "*CarryForwardLaneTest*"` |
| RC-25 | 이월값을 **원계열 관측값**으로 반환해 소비자가 severity로 잘못 변환(원값을 z 임계에 직접 분류 → z 계열 6지표 상시 crit) | K-07·D-02 | MT1-07b | 반환 타입을 `Map<String, Int>`(severity)로 **고정** — 도출 경로 자체를 두지 않는다(§9-B-2a 반환 계층 열) | `--tests "*CarryForwardLaneTest*"` |
| RC-23 | 웜업 550이 코드 리터럴로 굳어 SSOT 스캔 위반 + 하니스와 값이 갈려 패리티 파손 | K-16 | MT1-04·05a | P-14로 configs 신설 + 패리티가 `grid.json.padding_days` ↔ assets 값 일치를 assert | `./gradlew :core:engine:test --tests "*ParityTest*"` |
| RC-18 | 영업일 grid 정정(휴장 정보 갱신)이 **이미 커밋된 과거 국면을 사후 변경** | K-03·D-06 | MT1-03b·06b | `visible_at` 미저장(§9-B-2) + 커밋 시 `tick_input` 동결(§9-B-4b) — 새 grid는 앞으로의 틱에만 적용 | `--tests "*TickInputFreezeTest*"` |
| RC-19 | fold 전량 재생이 이력 증가로 느려짐 | — | MT1-06b | 1일 1틱 = 5년 1,250틱, 틱당 상수 시간 산술. 10년 초과 시 스냅샷 도입 재검토(그전엔 YAGNI) | fold 2,500틱 벤치 1건(진단) |

---

## 7. 실측 선행 과업 (무엇이 무엇을 블록하는가)

전부 `data-verifier` 위임. **R0에서 3병렬**로 착수하고, 결과는 저널(`docs/journal/2026-08-XX_M1_V*.md`)에 기록한다.
실측 없이 구현에 들어가면 M0의 K-04·K-02 교훈이 반복된다("추측 코드값 금지").

| ID | 실측 대상 | 블록 대상 | 실패 시 대체 경로 |
|---|---|---|---|
| **V-1** | 야후 REST(`^VIX`·`^VIX3M`·`^MOVE`·`^GSPC`·`DX-Y.NYB`·`KRW=X`) 모바일 UA/헤더로 응답 여부, 스키마, Stooq 폴백 심볼 매핑·응답 형식 | MT1-04a | Stooq 단독. 둘 다 실패 시 해당 지표 상시 `NOT_COLLECTED` + GM1 기록 |
| **V-2** | kotlin_krx 실동작 3종: ① `login(KRX_ID, KRX_PW)` 성공 여부(2026 정책) ② `getVkospi`(mdiLoader 세션) 실데이터 ③ **17:00 시점 당일 확정성 3영업일 연속**(지수·투자자·VKOSPI) ④ `getBusinessDays` 2026 휴장 정확도 | MT1-04c, **MT1-06a(확정 틱 시각)**, 결정 U-1·U-4 | ③ 실패 시 17:30/18:00. ① 실패 시 KR 전 지표 미수집 → **GM1 중대 리스크로 즉시 상신** |
| **V-3** | ECOS `721Y001` 하위 `item_code`(국고3y·회사채AA-3y) — API 메타 조회로 실코드 확정(K-04, F-7) | MT1-04d, §8 P-2 | 미확정 시 `krx_credit_spread_delta` 상시 결측 + GM1 기록(credit 축 축소 2건) |
| **V-4** | kr_cds_5y_delta 모바일 접근 가능성(worldgovernmentbonds 등) — 응답 안정성·차단 여부 | MT1-04f, **MT1-07 완료 기준 ③의 기대 coverage 값**(F-6) | (b) 미수집 확정 → coverage 상한 29.5/31.0(표시 95.2%) 문구 확정 |
| **V-5** | KIS 옵션 인증·시세 엔드포인트(TinyOscillator 자산 재사용 가능성) | MT1-04e(옵션) | 미사용(기본 비활성 유지). 프리뷰 KR 실시간 없음 → carry-forward 의존 |
| **V-6** | FRED REST 모바일 직접 호출(키·레이트·응답) | MT1-04b | 위험 낮음. 실패 시 credit·rates 축 각 1지표 결손 |

**실측 결과의 물질화 규율**: V-1~V-6의 결론은 반드시 (i) 저널 파일 (ii) `sources.yaml` 변경 제안(§8)
(iii) 해당 서브태스크 브리프에 복사 — 세 곳 모두에 남긴다. "파일에 없는 결정은 존재하지 않는 결정이다"(CLAUDE.md §0).

---

## 8. SSOT 변경 제안 (직접 수정 금지 — Advisor 승인 후 별도 커밋)

| ID | 대상 | 제안 | 근거 | 우선순위 |
|---|---|---|---|---|
| **P-1** | `configs/indicators.yaml` `engine:` 블록 | `preview_coverage_min: 0.80` 신설 (아래 상세) | **판정 조건인데 SSOT에 없다** — 상세는 P-1 보강란 | **최상** — MT1-07 착수 전 필수 |
| **P-2** | **`configs/indicators.yaml` L76** `krx_credit_spread_delta.source.item_codes` | `{ corp_aa3y: VERIFY, ktb_3y: VERIFY }` → V-3 실측값. 부수적으로 `sources.yaml` L35의 note 문구도 "검증 완료(YYYY-MM-DD)"로 갱신 | K-04 미해소(F-7). **`grep -rn "VERIFY" configs/`가 indicators.yaml L76 1건만 반환**한다 — 초안이 sources.yaml을 대상으로 적은 것은 오기였고 여기서 정정한다. 서버 P1이 아니라 **M1이 첫 소비자**다 | **최상** — MT1-04d 블록 |
| **P-3** | `configs/sources.yaml` | `krx_native` provider 신설 또는 `pykrx` notes에 병기: kotlin_krx 경로·`login` 필요·mdiLoader 세션 필요 엔드포인트(VKOSPI/옵션)·rate limit 재사용 | 모바일 KR 수집 경로가 SSOT에 없다. K-03 준수 근거를 파일에 물질화 | 상 |
| **P-4** | `configs/sources.yaml` | provider별 `timeout_s`·`retry` 모바일 값 명시(현재 yfinance에만 retry 존재) | CLAUDE.md §2 "재시도 정책은 sources.yaml 준수" — INV-2의 예산이 코드 리터럴이 되지 않게 | 중 |
| **P-5** | `configs/sources.yaml` stooq | V-1 실측 결과(엔드포인트·심볼 매핑·형식) 기록 | 현재 "MT1-04a에서 실측" 상태 | 중 |
| **P-6** | `configs/statemachine.yaml` | `schedules.evaluation.kr_close`에 주석 1줄: "모바일 확정 틱은 이 시각을 파생한다(D-16 mobile_daily)" | 새 키 없이 17:00을 단일 출처로 고정. 값 변경 0 | 중 |
| **P-7** | `TASK_mobile_m1.md` MT1-06 / `docs/ARCHITECTURE_SPLIT.md` D-15표·§1 | "16:20 KST" → "17:00 KST" 정정 + 근거 역참조 | §5 논증. 결정 U-1 승인 시 동시 반영 | 상 |
| **P-8** | `CLAUDE.md` §3 함정 | **K-19** 신설: "kotlin_krx는 파싱 실패와 빈 응답(휴장)을 모두 빈 리스트로 반환한다 — 휴장 판정을 빈 응답 단독으로 하지 말 것. 파생상품(VKOSPI·옵션)은 mdiLoader Referer로 세션 필수." **K-20** 신설: "targetSdk 33+ 알림 권한 거부 시 노티 3채널 전부 무력 — 배너·이력 대체 경로 필수." | F-9~F-11 실측 근거. 하류 Worker 브리프에 복사될 항목 | 상 |
| **P-9** | `docs/P0_DESIGN_DECISIONS.md` | **D-27 신설**(또는 D-23 부기): "모바일 확정 틱은 KR 영업일에만 국면을 커밋한다. 휴장일 실행은 스킵·미커밋·이력 기록." | §4.6 논거 — 휴장일 커밋은 D-23 §23.2의 방향성 오류를 확정 국면에 주입한다 | 상 |
| **P-10** | `contracts/` | `contracts/snapshots/{scenario-snapshot-1.schema.json, evidence-pack-1.schema.json, *.sample.json}` 신규 디렉토리 + 생성기 `scripts/gen_contract_snapshots.py` | 브리프 §2-9: Python 측 스냅샷 생성·테스트가 현재 없다. contracts는 SSOT이므로 제안으로만 | 상 — MT1-02 블록 |
| **P-11** | `configs/statemachine.yaml` `profiles.mobile_daily` | **`catchup_max_ticks: 20`** 신설 | **판정 영향값**(§4.6-A): 상한 밖 거래일 영구 누락이 히스테리시스 틱 카운트를 바꾼다. 같은 블록의 `promote_sustain_ticks`·`demote_below_ticks`·`min_dwell_ticks`·`reentry_cooldown_ticks`와 동종 파라미터이므로 동거가 자연. 값 20 = 2 × 완결 사이클(dwell 5 + demote 3 + cooldown 2) — 도출 §4.6-B | 상 — MT1-06d 블록 |
| **P-12** | `docs/P0_DESIGN_DECISIONS.md` D-25 부기 | "틱 시퀀스에 구멍이 생기면(캐치업 상한 절단) 카운터를 조작하지 않고 남은 틱을 이어서 재생한다. 이월 오차의 상한은 **강등 최대 2틱 조기·승격 오차 0**(promote_sustain=1이므로 승격 스트릭은 이월 불가)이며, 간극은 `gap_before`로 공시한다" | §9-B-4(c) 실측표. 관례로 두면 나중에 누군가 '리셋이 옳다'며 엔진을 건드린다 — 유계 오차와 그 근거를 문서에 고정한다 | 상 |
| **P-14** | `configs/indicators.yaml` `engine:` 블록 | 웜업 길이 키 신설, 값 **550**(달력일). 키명은 **M-42 병합 결정**(A안 `warmup_calendar_days` / D안 `warmup_padding_days`) | §9-B-2: 값의 출처인 `backtest/windows.yaml`은 **하니스 전용·syncConfigs 미대상**이라 앱이 읽을 수 없다. 그대로 두면 550이 코드 리터럴이 되어 CLAUDE.md §1 위반. 웜업 길이는 z 기준선 → severity → 상태기계 입력을 바꾸므로 §4.6-A 판별식상 **판정 영향값**. 하니스 값과 동일해야 BT-05가 성립(불일치 시 패리티 실패) | **최상** — MT1-04·05a 착수 전 필수 |
| ~~P-13~~ | ~~`engine_ref/statemachine.py` `run()` 시그니처 확장~~ | **철회(라운드 3)** | §9-B-4(c): 리셋의 편익(≤2틱)이 비용(서버 공유 실행 명세 변경 + BT-05 미검증 분기 신설 + 무회귀 증인)보다 작고, dwell 차원에서는 리셋이 오히려 덜 정확하다. `composite=NULL` 동결과 행 부재가 관측 동치라는 점도 확인 | — |

**P-1 보강 — 억제 임계 0.80의 값·위치 근거** (A·B 안과 대조 가능하도록 명시):

- **값의 출처**: D-23 §23.3-3 원문("coverage < 80%면 … '국면 판정 불가'"). 본 계획이 만든 값이 아니라 **문서에 이미
  확정된 값을 SSOT 파일로 물질화**하는 것이다. 값 자체는 D-04("모든 임계값은 가설")의 적용 대상이며 C1 보정 경로에 들어간다.
- **왜 SSOT여야 하는가**: 억제 여부는 **잠정 경보 발신 여부**를 결정한다 = 판정 조건. CLAUDE.md §1의
  "임계값·전이 조건은 오직 configs/*.yaml"에 정면으로 해당한다. 코드 상수로 두면 SSOT 스캔에서 위반이다.
- **위치 후보 3안 비교**:

  | 후보 | 장점 | 단점 | 판정 |
  |---|---|---|---|
  | **(a) `indicators.yaml` `engine.preview_coverage_min`** | coverage를 **산출하는 주체가 engine**이고(F-3 `compute_composite`), 같은 블록에 `missing_data_policy`(결측 분모 규칙)·`stale_profiles`가 있어 "결측·커버리지 규율"이 한곳에 모인다 | 프리뷰라는 UI 개념이 지표 레지스트리에 들어옴 | **권고** |
  | (b) `statemachine.yaml` `preview.coverage_min` | "판정 억제"는 상태기계 어휘 | 상태기계는 프리뷰를 **모른다**(D-17 §1 — 프리뷰는 커밋하지 않음). 상태기계가 소비하지 않는 키를 두면 오해를 부른다 | 차선 |
  | (c) 앱 상수 | 간단 | **SSOT 위반**. C1 보정 시 앱 재빌드 필요 | 기각 |
- **소비 경로**: `:core:engine`이 assets `indicators.yaml`에서 읽어 `raw_coverage`와 비교(§4.5-0). 임계 자체는
  엔진 계산에 들어가지 않고 **억제 판정에만** 쓰이므로 BT-05 패리티에는 영향이 없다(하니스는 프리뷰를 돌지 않는다).

> 제안 원칙: **상태기계의 입력 시퀀스에 영향을 주는 값만 SSOT로 올린다**(§4.6-A의 판별식).
> 캐치업 상한은 영향을 주므로 SSOT(P-11), 프리뷰 쿨다운(60s)·노티 예산(1일 1건)·전체 타임아웃 예산은
> 국면을 커밋하지 않는 경로의 값이라 앱 내 `AppDefaults` 1곳에 모으고 "판정 무관" 주석을 단다.
> (초안이 캐치업 상한을 후자로 분류했던 것은 오류이며 §4.6-A에서 철회했다.)

---

## 9. 다른 관점 소관에 대한 본 계획의 입장 (A·B를 비워두지 않기)

### 9-A. 아키텍처·의존성 (브리프 §5-1·2·3·4·7)

- **모듈 7개**(§10의 완료 명령과 1:1 대응): `:app`(UI·DI·WorkManager) / `:core:engine`(**Android 무의존 순수
  Kotlin** — BT-05를 JVM 테스트로 돌리기 위한 전제) / `:core:contracts`(kotlinx.serialization) /
  `:core:data`(Room·collectors) / **`:core:krx`**(vendored krxkt 래퍼 — 아래 vendoring 항목) /
  `:core:common` / `:feature:preview`(carry-forward 격리 전용, §4.5). 모듈 경계 자체가 아키텍처 테스트다.
- **kotlin_krx 통합**: **vendoring(소스 복사 + `mobile/vendor/krxkt/` + `UPSTREAM.md`에 상류 커밋 해시·
  "수정 금지" 규율) 권고**. 근거: (i) `includeBuild`에 절대경로(`D:\android_2025\…`)를 쓰면 빌드 재현성이
  단일 머신에 묶인다 (ii) kotlin_krx는 PROGRESS/TASK 모두 백로그 0의 완료 상태라 상류 변화가 없다
  (iii) 오프라인·CI 재현이 필요한 게이트 시스템에 원격 경로 의존은 위험. 대안(composite/maven-local)은
  A가 더 강한 근거를 제시하면 양보.
- **로그인 정책 대응 확인 절차**: F-9로 `login()` 구현 존재는 확인됐다. 남은 것은 **실동작**이며 V-2가 그 과업이다.
- **`./gradlew check` 구성**: `ktlintCheck` + `detekt`(SwallowedException 등 실패로) + `lintDebug` +
  `testDebugUnitTest`(단위+Robolectric) + `koverVerify` + `verifyConfigHashes` + `verifyContractSnapshots`.
  계측(`connectedDebugAndroidTest`)은 실기기·에뮬레이터가 필요하므로 `check`에는 넣지 않되,
  **MT1-01 완료 기준과 GM1 게이트에서 반드시 실행**한다(아래 syncConfigs 항목·§10 참조).
- **`koverVerify` 모듈별 임계**(AAA §2.3 — 코어 ≥90%, 나머지 ≥70%). 각 모듈 `build.gradle.kts`에
  `kover { reports { verify { rule { bound { minValue = N } } } } }`를 두고 루트에서 집계한다:

  | 모듈 | 라인 커버리지 하한 | 근거 |
  |---|---|---|
  | `:core:engine` (engine·statemachine) | **90%** | AAA §2.3 "코어 모듈" 명시 |
  | `:core:contracts` | **90%** | 동 (contracts) |
  | `:core:data` — `lake` 패키지 | **90%** | 동 (lake) |
  | `:core:data` — 그 외(collectors) | 70% | 코어 아님 |
  | `:core:krx` 래퍼 | 70% | vendored 원본(`vendor/krxkt`)은 **커버리지 집계에서 제외**(외부 자산, 수정 금지) |
  | `:app` · `:feature:preview` · `:core:common` | 70% | 나머지 |

  실행: `./gradlew koverVerify`(검증, `check`가 의존) / `./gradlew koverHtmlReport`(진단).
  패키지 단위 하한이 필요한 `:core:data`는 `includedPackages`로 lake 패키지만 뽑은 별도 rule을 추가한다.
- **syncConfigs**: 대상 = `configs/*.yaml` 5종(analogue_seed·indicators·news_topics·sources·statemachine) +
  `prompts/*.md` 2종(daily_digest·scenario_report) → `app/src/main/assets/`. `preBuild.dependsOn(syncConfigs)`로
  누락 불가. 검증은 **3중이며, TASK 원문(MT1-01 "계측 테스트가 SHA-256 일치 검증")이 정본**이다:

  | 층 | 수단 | 실행 시점 | 지위 |
  |---|---|---|---|
  | 1 | Gradle `verifyConfigHashes`(루트 `configs/`·`prompts/` 원본 ↔ assets 사본 SHA-256) | 매 빌드(`check` 포함) | **보강** — 가장 이른 차단선 |
  | 2 | Robolectric 단위 테스트(`AssetManager`로 읽은 사본의 SHA-256) | `check` | **보강** — 패키징 결과 확인 |
  | 3 | **계측 테스트 `AssetHashInstrumentedTest`**(실기기/에뮬레이터의 설치된 APK가 로드하는 assets) | `connectedDebugAndroidTest` | **정본 — MT1-01 완료 기준·GM1 조건** |

  초안이 U-7(b)로 계측을 격하했던 것을 **철회한다**: TASK 서브태스크 요구의 축소는 브리프 §1("축소 불가") 위반이고,
  1·2층은 "빌드 산출물이 맞다"만 증명할 뿐 **실제 단말에 설치된 APK가 로드하는 바이트**를 증명하지 않는다
  (K-16이 겨냥하는 드리프트의 마지막 구간). 3층은 유지하고 1·2층을 병행 추가한다(추가일 뿐 대체가 아니다).
- **contracts 미러**: P-10의 스냅샷 파일을 양측이 참조. Kotlin은 `*.sample.json` 역직렬화 → 재직렬화 →
  정규화(키 정렬) 비교의 왕복 테스트. 동결 일치 판정은 **스냅샷 파일 SHA-256**이 기준.

### 9-B. 데이터·정합성·백테스트 (브리프 §5-5·6·8·10·11)

#### 9-B-1. `visible_at` — 프로덕션 산출 규칙 (정본: `run_replay.py:222-274, 352-369`)

**스테일 판정의 기준 시각은 `as_of`가 아니라 `visible_at`이다**(F-13). 하니스는 이 오류를 실측으로
반증했고(달력일 자정을 기준으로 쓰면 kr_close에 막 가시화된 값이 즉시 스테일로 오판), 그 docstring이 정본이다.
프로덕션은 **같은 규칙을 그대로 포팅**한다 — 다른 규칙을 쓰면 결측이 낀 전 틱에서 패리티가 파손된다.

**규칙 (`:core:engine`의 순수 함수 `visibleAt(...)`, 3층)**

```
calendarKind(seriesId)          # fixture_schema.calendar_kind 그대로 이식
  "KRX:" 접두               -> krx
  "KRW=X"                   -> fx
  BAMLH0A0HYM2 | T10Y2Y     -> fred
  그 외                      -> us_market

visDay(seriesId, asOf, grid)    # grid = KR 영업일 목록(§4.6의 영업일 캐시)
  us_market -> asOf 보다 "엄격히 큰" 첫 grid 일        (미국 종가는 다음 KR 영업일에 반영)
  fred      -> (asOf + lag_days) "이상"인 첫 grid 일    (lag_days는 indicators.yaml, K-05 T+1)
  krx | fx  -> asOf "이상"인 첫 grid 일

visibleAt(indicator, asOf) = max over 입력계열 s of  kstToUtc(visDay(s, asOf, grid), confirmTime)
                             # worst-of-inputs — 2계열 지표(vix_term_structure·global_corr_break 등)는
                             # 둘 다 알려져야 결합값을 안다 (combined_visibility_utc)
                             # confirmTime = 확정 틱 시각(§5, 17:00 KST 권고)
```

**값 선택(lookup)**: `lookup_known`과 동일하게 **`visible_at ≤ evaluated_at`인 것 중 최신**을 고른다.
`as_of ≤ cutoff`로 고르면 안 된다(초안의 오류를 여기서 정정한다).

**스테일 판정**: `(evaluated_at − visible_at) > stale_windows[profile][cadence]`.
**등호 미포함**(초과만 stale — `engine_ref.registry.is_stale`과 동일 규약), 양쪽 모두 tz-aware 강제
(naive면 예외 — K-05, CLAUDE.md §2). `cadence`는 지표의 `source.cadence`, 창은 `engine.stale_profiles`.

**cadence 키 부재 폴백(필수 이식)**: `engine_ref/registry.py:305-314 stale_window`는
`windows.get(cadence, windows["daily_kr"])` — **프로파일 맵에 해당 cadence 키가 없으면 그 프로파일의
`daily_kr` 창을 적용**한다. `mobile_daily`에는 `intraday_30m` 키가 없으므로(indicators.yaml L240)
`usdkrw_z`·`vkospi_z`·`kospi_drawdown` 3종(가중 8.0)이 **전부 이 폴백을 타 30h 창을 쓴다**.
이식을 빠뜨리면 키 조회가 예외이거나 기본값이 되어 KR 축이 통째로 잘못 판정된다 — §9-C L3 게이트가 잡는다.

**프리뷰도 같은 `visible_at` 시계를 쓴다(초안의 `observed_at` 분기를 철회)**: 초안은 프리뷰에 한해
`observed_at`을 가시성 시계로 쓰자고 했으나 **바로 위 문단과 자기 모순이고 그 실패 모드가 프리뷰에 정확히
해당**한다 — ^MOVE·^VIX3M처럼 절단된 계열(2026-07-17 이후 미갱신, MT0-03 실측)을 매 프리뷰마다 재조회하면
`observed_at`이 갱신되어 **영원히 신선**해진다. 그러면 스테일 배지가 사라지고(D-23 §23.3-1 위배),
`raw_coverage` 분자가 부풀며, 확정 틱과 다른 composite가 나온다. 시계를 갈라서는 안 된다.

**대신 분기하는 것은 "평가 틱 시각" 하나뿐이다.** `visDay`는 값이 알려지는 *그리드 일*을 주고,
`visible_at`은 그 그리드 일의 **평가 주체의 틱 시각**이다:

```
visibleAt(indicator, asOf, evalTickTime) = max over s of kstToUtc(visDay(s, asOf, grid), evalTickTime)
  확정 틱: evalTickTime = confirmTime(17:00)  -> 하니스와 비트 동일(패리티 대상)
  프리뷰  : evalTickTime = now(KST 시각부)     -> "지금 시점에서 알 수 있는 것"
```

- 시계는 여전히 `as_of` 파생이므로 재수집이 신선도를 되살리지 못한다 — 절단 계열은 프리뷰에서도 **스테일 배지가 뜬다**.
- 프리뷰의 as_of 상한도 §9-B-2의 같은 역산표를 그대로 쓴다(양변이 같은 시각부를 쓰므로 비교가 `visDay ≤ 평가일`로
  동일하게 환원된다) — **프리뷰 전용 조회 경로가 없다**.
- 장중 프리뷰(예 13:00)에서 당일 KR 행은 `visDay = 오늘`, `visible_at = 오늘 13:00 = now` → **가시**다.
  즉 KIS 등으로 실제 장중값을 받았다면 프리뷰가 그것을 쓴다. 받지 못했으면 그 행이 없으므로 결측 →
  carry-forward. §4.5의 장중 커버리지 하락은 여기서 기계적으로 설명된다(시계 분기가 아니라 **데이터 부재**가 원인이다).
- §9-B-3의 "엔진에 프리뷰 전용 분기 없음"과 정합하고, D-23 §23.1 층위 A(설계상 차이)는 여전히
  "커밋 여부 + carry-forward + 평가 시각"으로 설명된다 — 가시성 규칙의 차이로 설명할 필요가 없다.

**프리뷰의 `evaluated_at` 명시**: `evaluated_at = now`(UTC, tz-aware). `evalTickTime`은 그 `now`의 **KST 시각부**다
(예 KST 22:10 → `evalTickTime = 22:10`). 확정 틱은 `evaluated_at = kstToUtc(target_date, confirmTime)`,
`evalTickTime = confirmTime`. 두 경로 모두 같은 함수·같은 두 인자만 다르다.

**나이 산식이 갈리는 지점과 본 계획의 선택 (병합 결정 M-39)**

`visible_at`을 `visDay @ evalTickTime`으로 두면 프리뷰 나이는 **정확히 24h의 배수**가 된다(평가 시각이
양변에 같이 들어가 상쇄). 관점 D는 "실경과"(`now − 그 값이 실제로 가시화된 시각`)를 제안하며, 저녁 프리뷰에서
결과가 갈린다 — 예: 2영업일 전 `daily_us` 값(창 48h), 22:00 프리뷰. 본 방식 = 48h → **등호 미포함이므로 stale 아님**,
D 방식 = 48h + 5h = 53h → **stale**. `raw_coverage`가 달라져 억제 여부까지 갈린다.

| | 본 계획(`visDay @ evalTickTime`) | 관점 D(실경과) |
|---|---|---|
| 같은 데이터, 16:00 vs 22:00 프리뷰 | **판정 동일** | 판정이 뒤바뀔 수 있음 |
| 확정 틱과의 관계 | `evalTickTime=confirmTime`이면 **하니스와 비트 동일**(같은 식) | 프리뷰용 두 번째 산식 필요 |
| 물리적 정직성 | 최대 `now − confirmTime`(≈7h)만큼 **관대**할 수 있음 | 물리적 경과를 그대로 반영 |

**선택: 본 계획 방식.** 근거는 시각 무관 안정성이다 — 데이터가 그대로인데 사용자가 새로고침한 *시각* 때문에
"정상 → 판정 불가"로 뒤집히면, 그것은 D-23 §23.5가 보장 대상으로 삼은 "차이의 설명 가능성"을 사용자에게
설명할 수 없는 형태로 깨뜨린다(사용자는 무엇이 바뀌었는지 알 수 없다). 또한 산식이 하나로 유지되므로
확정 경로의 하니스 동일성이 구성적으로 보장된다.
**대가는 숨기지 않는다**: 최대 약 7시간 관대해질 수 있고, 이는 스테일 배지가 하루 늦게 켜질 수 있다는 뜻이다.
완화는 표시로 한다 — 지표 카드에 **항상 실제 `as_of` 날짜를 병기**하므로 사용자는 배지와 무관하게 데이터가
며칠 자인지 볼 수 있다. 이 대가와 완화를 GATE_GM1에 기록한다. 최종 채택은 M-39.

**왜 `observed_at`(실제 수집 시각)을 스테일 시계로 쓰지 않는가** — 두 가지가 동시에 깨진다:
(i) 같은 오래된 행을 매일 재수집하면 `observed_at`이 갱신되어 **영원히 신선**해진다.
(ii) 캐치업(BACKFILL)은 과거 일자를 오늘 수집하므로 `evaluated_at − observed_at`이 **음수**가 되어 스테일 개념이 붕괴한다.
`visible_at`은 순수 달력 함수라 두 경우 모두에서 하니스와 동일한 값을 준다 — **패리티가 구성적으로 성립**한다.
`observed_at`은 원장에 계속 기록하되(감사·revision 추적·`pit_quality` 판정용) **평가 시계로는 쓰지 않는다**.

#### 9-B-2. Room append-only 스키마와 조회 — `visible_at`은 **파생값이며 저장하지 않는다**

초안이 `visible_at`을 컬럼으로 저장하려 했던 것을 **철회한다**. 저장은 §9-B-1(순수 함수·grid 인자)과
모순이고 세 지점에서 깨진다:
① 정본 이원화 — 쓰기 시점 grid로 굳은 저장값과 현재 grid의 계산값이 갈리면 §9-C **L1 "완전 일치" 게이트에서
패리티 러너(함수)와 프로덕션(컬럼)이 서로 다른 값을 낸다**.
② revision 의미론 파괴 — grid 정정 재산출이 **값 변경 없이** revision을 올리면, `ORDER BY … revision DESC`가
파생값 재계산을 "개정치"로 오인한다(D-06의 revision = 값 개정 횟수).
③ 과거 재현 불가 — 재산출 배치가 돌면 이미 커밋된 틱의 입력이 사후에 바뀐다.
근본 원인은 하나다: **grid는 시간이 지나면 정정될 수 있는데, 저장은 그 시점의 grid를 영구 진실로 굳힌다.**

**확정 설계 (파생 + 입력 동결)**

- **원장 granularity = `(series_id, field, as_of)`** — 초안의 `indicator_id` 키를 **철회**한다.
  지표 키로는 표현 불가능한 것이 3가지다: ① `vix_term_structure`·`global_corr_break`는 **같은 as_of의 두 계열**
  값이 동시에 필요해 `LIMIT 1`로 못 얻는다 ② `field` 컬럼이 없어 `usdkrw_intraday_force`(KRW=X high·low·전일 close)와
  `kospi_volume_distribution`(KRX:1001 trading_value + close 게이트)을 담지 못한다 ③ `^GSPC`를 두 지표가
  공유해 중복 저장·귀속 모호가 생긴다. 하니스 `run_replay.series_values(df, series_id, field)`의 롱포맷과
  **동일 granularity**로 맞추는 것이 패리티의 전제이기도 하다.

  ```
  observation(
    id INTEGER PK, series_id TEXT, field TEXT,
    as_of INTEGER,        -- UTC millis (원계열 행 날짜)
    observed_at INTEGER,  -- UTC millis (실제 수집 시각 — 감사용, 평가 시계 아님)
    lane TEXT,            -- 'CONFIRM' | 'PREVIEW'  (§9-B-2a — D-17 §3 오염 차단 판별자)
    source TEXT, revision INT, value REAL, raw_json TEXT )
  UNIQUE(series_id, field, as_of, lane, revision);  INDEX(series_id, field, lane, as_of)
  ```
  **`visible_at`·`indicator_id` 컬럼 없음** — 전자는 파생값(위), 후자는 지표↔계열이 다대다라 원장의 키가 될 수 없다.
  지표→(계열, 필드) 매핑은 `indicators.yaml`의 `source`·`transform`에서 파생되며 코드에 중복 기입하지 않는다.

- **원계열 조회 계약 (transform 입력)** — 활성 15지표 중 다수가 롤링 창을 요구하고
  `engine_ref/transforms.py`는 전부 `min_periods=window`다(zscore 252 6종, drawdown_from_high 60 2종,
  rolling_corr 20 → rolling_mean_corr 120, rolling_sum 5, zscore 60, delta_bp 5 4종, realized_vol 20).
  따라서 "틱 하나의 값"이 아니라 **범위 시계열**을 읽어야 한다:

  ```kotlin
  fun loadSeries(seriesId: String, field: String, fromAsOf: Long, toAsOf: Long,
                 lanes: Set<Lane>): SortedMap<Long, Double>
  // 확정 틱: lanes = {CONFIRM}       프리뷰: lanes = {CONFIRM, PREVIEW}   (§9-B-2a)
  // SQL: SELECT as_of, value FROM observation
  //       WHERE series_id=:s AND field=:f AND as_of BETWEEN :from AND :to
  //         AND lane IN (:lanes)
  //       ORDER BY as_of ASC,
  //                CASE lane WHEN 'PREVIEW' THEN 0 ELSE 1 END ASC,  -- CONFIRM이 나중 = 우선
  //                revision ASC, id ASC
  // → Kotlin에서 associate { as_of to value } (나중 행이 앞을 덮음 = as_of당 최종 1건)
  ```
  "as_of당 최신 1건" 선택은 **정렬 + fold**로 처리한다. minSdk 29 단말의 SQLite가 윈도 함수를 지원하는지는
  **본 계획이 실측하지 않았다**(관점 B·D의 단정이 서로 엇갈린다). 채택한 형태는 **SQLite 버전과 무관**하므로
  설계는 실측 결과와 독립이다 — 지원이 실측으로 확인되면 윈도 함수는 선택적 최적화일 뿐 설계 변경이 아니다.

  **필요한 (계열, 필드) 14쌍**(하니스 `run_replay.py`의 `series_values` 호출 전수와 1:1 — 이 목록이 수집기의 계약이다):
  `^VIX/close` · `^VIX3M/close` · `^MOVE/close` · `BAMLH0A0HYM2/value` · **`DX-Y.NYB/close`**(정본:
  run_replay.py:467) · `T10Y2Y/value` · `^GSPC/close` · `KRX:1001/close` · `KRX:1001/trading_value` ·
  `KRX:VKOSPI/close` · `KRX:investor_foreign_kospi/net_buy_value` · `KRW=X/close` · `KRW=X/high` · `KRW=X/low`.
  프로덕션은 V-3 이후 ECOS 2쌍(`corp_aa3y`·`ktb_3y`)이 추가된다 — **픽스처에 없으므로 패리티 대상이 아니고**,
  패리티 실행에서는 `krx_credit_spread_delta`가 결측으로 나오는 것이 정답이다(F-7).

- **§9-B-2a. 프리뷰 lane 격리 (D-17 §3 오염 차단)** — D-17 §3은 "프리뷰 수집치도 Room lake에 append한다
  (observed_at=now). **일일 확정 틱은 마감 기준 as-of로 읽는다** — PIT 규율(D-06) 유지"를 요구한다.
  판별자가 없으면 다음 불가역 경로가 열린다: 13:00 프리뷰가 당일 장중 부분값을 `as_of=오늘`로 append →
  17:00 확정 수집 실패 → 확정 fold가 **그 장중 부분값을 오늘의 종가로 동결**해 국면을 커밋한다.
  원장은 append-only라 되돌릴 수 없다.
  - **확정 틱 경로는 `lane='CONFIRM'`만 읽는다**(위 SQL의 `lane IN (:lanes)`가 그 물리적 표현이다).
    확정 수집이 실패하면 그 지표는 **결측**으로 남는다 — D-02 분모 제외가 정답이고, 장중 부분값 대체는 오답이다.
  - 프리뷰 경로는 두 lane을 모두 읽되 동일 `as_of`에서 **CONFIRM을 우선**한다(위 `CASE lane` 정렬).
  - 하니스 픽스처에는 PREVIEW 행이 없으므로 이 필터는 패리티에서 no-op다 — BT-05 무영향.
  **읽기 지점 전수표 (M-43b) — 원장을 읽는 곳은 3개뿐이고, 각각의 `lanes`는 아래로 고정된다.**
  이 표에 없는 원장 읽기 경로를 추가하는 것은 설계 변경이며, 새 행에는 증인 테스트가 함께 와야 한다.

  | # | 읽기 지점 | 호출 모듈 | 원천 테이블 | **반환 계층** | `lanes` | 동일 `as_of` 충돌 해소 | 근거 | 증인 테스트 |
  |---|---|---|---|---|---|---|---|---|
  | ① | **확정 틱 조회** (fold 입력 산출) | `:app`(work) | `observation` | 원계열 관측값 → transform → severity(자체 산출) | **`{CONFIRM}`** | 해당 없음(PREVIEW가 결과에 들어오지 않음) | D-17 §3 "확정 틱은 마감 기준 as-of로 읽는다" · D-06 | `*PreviewLaneIsolationTest*` — PREVIEW 행만 있는 as_of에서 지표가 **MISSING**(값이 잡히면 FAIL) |
  | ② | **프리뷰 신선분 조회** (지금 알 수 있는 값) | `:feature:preview` | `observation` | 원계열 관측값 → transform → severity(자체 산출) | **`{CONFIRM, PREVIEW}`** | **CONFIRM 우선** — `ORDER BY as_of ASC, CASE lane WHEN 'PREVIEW' THEN 0 ELSE 1 END ASC, revision ASC, id ASC` 후 fold(뒤가 앞을 덮음) | D-17 §3 "프리뷰 수집치도 append" + 확정치가 있으면 그것이 진실 | `*PreviewLaneIsolationTest*` — 같은 as_of에 두 lane 공존 시 **CONFIRM 값**이 선택됨 |
  | ③ | **carry-forward 원천** (직전 확정값 이월) | `:feature:preview`의 `CarryForwardResolver` | **`tick_input`**(마지막 커밋 행) | **`severity`**(`Map<String, Int>` — 도출 경로 없음) | 해당 없음 — `tick_input`은 **확정 틱만 쓴다**(구조적 보장) | 해당 없음(일자당 1행, `target_date` PK) | D-23 §23.3-1 전단 "직전 **확정**값을 이월" | `*CarryForwardLaneTest*` — (i) `tick_input` 0행이면 **이월 없이 결측 유지**(M-50) (ii) PREVIEW 수집이 아무리 쌓여도 이월값 불변 (iii) 원천을 `observation`으로 바꾸면 FAIL |

  **반환 계층 열을 둔 이유(C-20)**: ①②는 원계열을 읽어 스스로 transform·분류하므로 severity를 **산출**하지만,
  ③의 소비자(§9-B-3의 이월 후 `compute_composite` 호출)는 **severity 맵**을 받는다. ③이 원계열을 읽으면
  계층이 단절되고, 그 틈을 메우는 두 경로가 모두 오답이다 — 원값 직접 분류는 임계 기준이 달라 오류
  (원 VIX≈15를 z 임계에 넣으면 z 계열 6지표 상시 severity 3), 원계열 재변환은 §9-B-2 4단계 순서 위반.
  따라서 ③의 원천을 `tick_input`으로 옮겨 **반환이 곧 severity**가 되게 한다 — 도출 경로가 존재하지 않으므로
  틀릴 방법이 없다.

  **왜 ③이 별도 행인가**: ③은 ②와 같은 모듈 안에서 호출되므로 "프리뷰 경로니까 프리뷰 데이터"라는
  자연스러운 추론이 곧바로 규율 위반이 된다 — **자기 프리뷰 값을 직전 확정값으로 둔갑시켜 이월**하는 경로다.
  ①②와 ③이 같은 앱 안에서 서로 다른 테이블·다른 계층을 읽는다는 사실이 이 설계에서 가장 틀리기 쉬운 지점이고,
  그래서 `CarryForwardResolver`는 `lanes`도 `LakeDao`도 **참조 자체를 갖지 않는다**(잘못된 원천에 닿을 수 없다).
  이 규율이 3라운드 연속 결함원이었던 만큼, 위 표는 §10의 완료 기준과 aaa-critic 확인 대상으로 고정한다.

- **정본 계산 순서 (필터-후-transform 금지)** — `run_replay.py:289-317`이 정본이다:

  ```
  1) loadSeries(계열, 필드, 평가시작일 − padding_days, 평가일)   ← 원계열 전체(웜업 포함)
  2) transform 적용 → 지표 출력 시계열 (min_periods=window 그대로, 부족 구간은 null)
  3) 출력 시계열의 각 row_date에 visibleAt 부여 → KnownSeries
  4) 틱별 lookup: visible_at ≤ evaluated_at 중 최신 1건
  ```
  **먼저 잘라내고 transform하면 다른 값이 나온다**(롤링 창이 잘린 구간에서 평균·표준편차가 달라짐)
  → BT-05 L1·L2가 확정적으로 실패한다. 이 순서를 Konsist 아키텍처 테스트로 강제한다:
  transform 호출부가 `lookup*`/`cutoff*` 결과를 인자로 받지 않는다(= 가시성 필터가 transform보다 앞설 수 없다).

- **웜업 범위 550(달력일) — 앱이 읽을 SSOT 키를 신설해야 한다(P-14)**.
  값의 **출처**는 `backtest/windows.yaml padding_days: 550`이지만 그 파일은 **하니스 전용**이고
  syncConfigs 복사 대상(configs 5종 + prompts 2종)에 없어 **앱이 읽을 수 없다**. 이 상태로 착수하면
  구현자는 550을 코드에 넣을 수밖에 없고 그것은 CLAUDE.md §1 위반이다.
  라운드 2에서 확정 틱 시각을 `replay.yaml`(하니스 전용)에서 끌어오려던 것과 **동일 유형의 재발**이며,
  그때의 해법(프로덕션이 읽을 수 있는 곳에 값을 두거나 파생 규약을 명시)을 여기에도 적용한다.
  - **판별식 적용**: 웜업 길이가 다르면 `zscore`의 평균·표준편차 기준선이 달라지고 → severity가 달라지고 →
    상태기계 입력 시퀀스가 달라진다. §4.6-A의 판별식("상태기계 입력 시퀀스에 영향을 주는 값 = SSOT")에
    정확히 해당한다. 실제로 본 계획 스스로 "웜업이 다르면 z 기준선이 갈려 패리티가 깨진다"고 쓰고 있다.
  - **제안**: `configs/indicators.yaml`의 `engine:` 블록에 웜업 키를 신설(P-14). 키명은 관점 A(`engine.
    warmup_calendar_days`)·D(`engine.warmup_padding_days`)와 갈리므로 **병합 결정 M-42**에 위임한다 —
    본 계획은 **위치(indicators.yaml `engine:`)와 값(550, 달력일)과 하니스 동일성 요건**을 고정한다.
  - 값의 근거: 최장 체인은 `zscore(window=252)` = 252거래일 ≈ 366달력일이고 550은 그 위의 여유다.
    하니스와 **같은 값**이어야 한다 — 두 값이 갈리면 BT-05가 깨진다(패리티 테스트가 `grid.json.padding_days`와
    assets 값의 일치를 assert한다, §9-C).
  - **최초 설치 시 로컬 이력은 0이므로 550달력일 초기 백필이 필수**이고, 그전까지 z 계열은 전부 null이다
    → `WARMUP_INSUFFICIENT`(§4.1)과 온보딩 백필 단계(§4.4)가 이 사실의 UX 표현이다.

- **가시성 조회(스칼라 lookup)**: `visible_at`은 as_of에 대해 **단조 비감소**이므로(하니스 `build_known_series`의
  "visibility_ts is monotonic non-decreasing in row_date"), `visible_at ≤ evaluated_at` 조건은
  **as_of 상한으로 정확히 역산된다**. 평가일 D(grid day)의 상한:

  | calendar_kind | `visDay` 규칙 | 역산한 as_of 상한 |
  |---|---|---|
  | krx · fx | as_of **이상**인 첫 grid 일 | `as_of ≤ D` |
  | us_market | as_of보다 **엄격히 큰** 첫 grid 일 | `as_of < D` |
  | fred | `as_of + lag_days` **이상**인 첫 grid 일 | `as_of ≤ D − lag_days` |
  | 2계열 이상 | worst-of-inputs(max) | 위 상한들의 **최솟값** |

  이 역산표의 지위: **위 4단계 파이프라인의 step 4(in-memory lookup)와 등가인 서술**이며, 원계열을 SQL에서
  좁혀 읽고 싶을 때(또는 UI 카드처럼 최신 1건만 필요할 때) 그대로 쓴다. 판정 경로의 정본은 step 4다 —
  transform 출력 시계열 위에서 `visible_at ≤ evaluated_at`을 적용한다(원계열을 미리 자르지 않는다).

  ```sql
  -- 최신 1건만 필요한 경우(표시용). 판정 경로는 위 4단계를 따른다.
  SELECT as_of, value FROM observation
   WHERE series_id = :s AND field = :f AND as_of <= :cutoffAsOf   -- cutoff는 위 표로 계산
   ORDER BY as_of DESC, revision DESC, id DESC LIMIT 1;           -- 개정치 우선, id로 tie-break
  ```
  선택된 행의 `visible_at`은 **메모리에서 §9-B-1 함수로 산출**해 스테일 판정에 쓴다. 저장하지 않으므로
  정본은 언제나 함수 하나이고, grid가 정정되면 **과거 행이 거짓이 되는 대신 계산이 자동으로 따라온다**.
- **grid 정정 시 재산출 배치 없음** → revision은 값 개정에만 증가(② 해소), append-only 규율 무손상.
- **과거 커밋의 재현은 재계산이 아니라 동결로 보장한다**(③ 해소): 확정 틱이 국면을 커밋할 때 엔진에
  넣은 입력을 `tick_input`(§9-B-4)에 **그대로 동결 저장**한다. grid가 나중에 정정돼도 과거 틱은
  재계산되지 않고 동결본으로 재현된다. 새 grid는 **앞으로의 틱에만** 적용된다.
- 물리 강제: `@Update`·`@Delete` 미구현 + `LakeDao`에 insert만 노출 + Konsist로 `lake` 패키지 내
  update/delete 어노테이션 0건 assert. **naive datetime 금지**(K-05) — 저장은 epoch millis(UTC), 표시만 KST.
- 비용: as_of 인덱스 하나로 조회는 여전히 O(log n)이고, `visible_at` 산출은 정수 달력 연산 1회다
  (grid는 §4.6의 영업일 캐시가 메모리에 들고 있다). 저장으로 얻는 성능 이득이 0에 가깝다는 점도
  파생 채택의 부수 근거다.

#### 9-B-3. 엔진 포팅 규율

- **D-26·or_any_extreme**: 브리프 §2-2대로 **프로덕션 경로**다. Kotlin `statemachine`은
  `_escape_blocks_exit`(레벨-로컬·reset·RED 무영향, D-25 말미 부기)를 포함해 포팅하고, 상수 입력
  한계진동 소멸(server 15→1·mobile 24→0)을 **증인 테스트로 이식**한다 — 없으면 이 결정이 조용히 퇴행한다.
- **Double·KST**: 전 계산 `Double`(K-07), 반올림은 표시 계층만(§4.5-0). `java.time.ZoneId.of("Asia/Seoul")`
  명시, `LocalDateTime` 단독 사용 금지(Konsist 스캔).
- **평가 불능(D-25 §3)**: 유효 가중 0이면 `composite = null` → 국면·스트릭·dwell·cooldown 전부 동결, 틱 미소비.
  `EVAL_IMPOSSIBLE`(§4.1)이 이 상태의 UX 표현이다.
- **프리뷰 coverage 계산 위치**: F-3에 따라 **엔진 계층**(`compute_composite`가 이미 `(score, coverage)` 반환).
  엔진 입력은 **severity 맵**이다. 프리뷰 레이어는 (a) 이월 **전** severity 맵으로 1회 호출해 `raw_coverage`를
  얻고, (b) 그 맵의 결측 키를 `CarryForwardResolver.lastConfirmed()`의 severity로 채운 뒤 1회 더 호출해
  composite를 얻는다 — 이월이 severity 계층에서 일어나므로 계층 변환이 개입하지 않고, 엔진은 두 번 호출될 뿐
  프리뷰 전용 분기를 갖지 않는다. 이 배치가
  "프리뷰와 확정 틱이 같은 엔진을 쓴다"는 D-18 패리티 전제를 깨지 않는 유일한 형태다.
- **캐치업의 PIT 지위**: 소급 수집 값은 개정치를 포함할 수 있다(D-06·K-11). 따라서 `pit_quality=BACKFILL`을
  `run_log`에 남기고 UI에 "소급"으로 표기한다(§4.6). **패리티 판정에서는 BACKFILL 국면을 제외하지 않는다** —
  BT-05는 픽스처 주입 실행이라 BACKFILL 개념이 없고, 실기기 국면의 정합성은 GM1이 아니라 INT 게이트
  (D-23 §23.5 일치율 ≥90%)의 판정 대상이기 때문이다. 이 경계를 GATE_GM1에 명시 기록한다.

#### 9-B-4. 상태기계의 프로덕션 호출·상태 지속 모델 (§4.6 규칙 7의 구현 경로)

**정본 사실**: `engine_ref/statemachine.py:106-194 run(ticks, profile, config)`는 `phase = config.initial_phase`로
시작하고 `list[str]` 타임라인만 반환한다 — **시작 국면·카운터를 주입할 파라미터도, 종료 상태를 내보낼 경로도 없다.**
따라서 "어제 상태를 이어받는" 증분 호출은 현재 엔진으로 **불가능**하다. 세 항목을 명시한다.

**(a) 프로덕션은 국면을 날마다 어떻게 이어받는가 — 이어받지 않는다. 매 커밋 전량 fold.**

확정 틱은 `tick_input` 전량을 시간순으로 `run()`에 한 번 넣고, **반환 타임라인의 마지막 원소를 오늘의 국면**으로
커밋한다. 지속되는 상태 객체가 없으므로 상태 지속 버그가 원천적으로 존재하지 않는다.

- **엔진 변경 0** — 프로덕션 호출 형태가 BT-05 패리티(`run(ticks, profile, config)`)와 **완전히 동일**해서
  패리티가 프로덕션 경로를 그대로 덮는다. 증분 호출 방식은 패리티가 검증하지 않는 코드 경로를 새로 만든다.
  **이 진술이 무조건 성립하는 근거**: 아래 (c)에서 P-13(초기 상태 주입 kwargs)을 **철회**했으므로
  절단 시에도 프로덕션은 같은 `run(ticks, profile, config)` 한 형태만 호출한다 — 하니스가 한 번도 타지 않는
  분기가 존재하지 않는다. (P-13을 유지했다면 이 문장은 "절단이 없는 한"이라는 조건이 붙어야 했고,
  그 조건절이 곧 미검증 경로였다.)
- 비용: 1일 1틱이므로 5년 누적 ≈ 1,250틱. `run()`은 틱당 상수 시간 산술이며 하니스가 9창을 초 단위로 돌린다.
  단말에서 문제되지 않는다. 10년(2,500틱)을 넘으면 그때 스냅샷 도입을 고려한다(YAGNI).
- 재현성: fold 입력이 동결본이므로 **같은 날 몇 번을 돌려도 같은 타임라인**이다(멱등의 근거이기도 하다).

**(b) 근거 데이터 보존 — `tick_input` 테이블(동결·append-only·purge 금지)**

`run_log`(운영 로그, 180일 purge 허용 — U-8)로는 fold를 복원할 수 없다(`distinct_axes`·`any_crit`·`any_extreme`
부재). 엔진 입력 전용 테이블을 분리한다:

```
tick_input(
  target_date TEXT PRIMARY KEY,      -- KST 영업일. 일자당 1행 = 멱등의 물리 강제
  evaluated_at INTEGER,              -- UTC millis (확정 틱 시각)
  composite REAL NULL,               -- NULL = 평가 불능(D-25 §3) — 엔진이 동결 처리
  distinct_axes INTEGER,
  any_crit INTEGER, any_extreme INTEGER,   -- engine_ref Tick의 필수 4필드
  raw_coverage REAL,                 -- 억제·표시용(§4.5-0). 엔진 입력 아님
  gap_before INTEGER,                -- 직전 행과의 누락 영업일 수(0=연속). 공시·감사용, fold 입력 아님
  registry_version TEXT, profile TEXT, pit_quality TEXT,   -- 출처·재현 맥락
  indicator_detail TEXT              -- json: id -> {value, as_of, visible_at, severity, status}
                                     --   (severity·as_of는 carry-forward 원천이다 — M-43b ③행)
)
```

- `composite`·`distinct_axes`·`any_crit`·`any_extreme` 4필드가 `engine_ref.Tick`과 1:1이다 — fold는 이 4열만 읽는다.
- `indicator_detail`의 `visible_at`은 **동결 시점의 산출값을 기록만** 하는 감사 필드다(조회·판정에 쓰지 않는다).
  §9-B-2의 "저장 금지"는 원장 `observation`에 대한 규율이고, 여기는 이미 커밋된 틱의 **불변 스냅샷**이다.
- `run_log`는 그대로 운영 로그로 남고(사유 코드·소요·시도 횟수), `tick_input`은 **lake급 append-only·purge 금지**다.
  CSV 내보내기(MT1-03d)는 두 테이블을 모두 포함한다.

**(c) 절단 카운터 처리 — 엔진 변경 없이 처리한다. 초안의 P-13(엔진 시그니처 확장)을 철회한다.**

라운드 2에서 "리셋은 현행 엔진으로 표현 불가 → 엔진 변경 상신"이라고 결론지었으나, **리셋이 필요하다는
전제 자체가 과대평가**였다. 비용-편익을 `statemachine.py` 실측으로 계산한다.

**간극을 그냥 두면(= 그 일자에 `tick_input` 행이 없으면) 무엇이 틀리는가 — 실측 상한**

| 카운터 | 현행 코드 거동 | 간극 이월의 최대 효과 |
|---|---|---|
| `promote_streaks` | 충족 시 +1, 미충족 시 0(L143-145). `mobile_daily.promote_sustain_ticks = 1`이므로 **1이 되는 즉시 커밋** | **이월 불가능** — 커밋 전 값은 항상 0. 오승격 위험 0 |
| `demote_streak` | 이탈 충족 시 +1, 아니면 0(L176·L187). 커밋은 `≥3` **AND** `ticks_in_phase ≥ min_dwell+1(6)` | 커밋 전 최대 5까지 누적 가능(dwell 대기 중). 간극 후 **첫 이탈 틱에서 즉시 강등** → 신선한 카운트(3틱 연속 필요) 대비 **최대 2틱 조기** |
| `ticks_in_phase`(dwell) | 매 틱 +1, 전이 시 1(L131·L164·L182) | 이월이 오히려 **더 정확하다** — 20영업일이 실제로 흘렀고 그 국면을 실제로 유지했다. 리셋은 이 사실을 지운다 |
| `cooldown` | 틱마다 −1(L190) | 간극 후 최대 2틱 승격 지연 = **보수 방향**(안전) |

**결론: 최대 오차는 "강등 2틱 조기" 한 방향뿐이고, 승격 오차는 구조적으로 0이다.**
이를 없애려고 지불하는 비용은 (i) `engine_ref` 시그니처 변경 — **서버 S1이 공유하는 실행 명세** (ii) BT-05가
한 번도 타지 않는 프로덕션 분기 신설 (iii) 골든·패리티 무회귀 증인 추가. **편익 ≤2틱 < 비용**이므로 철회한다.
`composite=NULL` 합성 틱(D-25 §3 동결)과 행 부재는 관측 동치이므로 어느 쪽을 골라도 같다 — 더 단순한 **행 부재**를 쓴다.

**대신 관점 C의 수단으로 처리한다 — 공시(disclosure)**:
`tick_input.gap_before`에 누락 영업일 수를 기록하고, ① 실행 이력 항목에 "간극 n영업일" ② 그 간극 직후 커밋된
국면 배지에 "간극 이후" 표기 ③ `CATCHUP_GAP_TRUNCATED` 사유 코드(§4.1)로 노출한다.
**"최대 2틱 조기 강등 가능"이라는 한계는 GATE_GM1에 수치와 함께 기록**한다 — 숨기지 않는 것이 처리다.

**fold 실행 규칙(최종)**: `tick_input` **전량**을 `target_date` 오름차순으로 `run(ticks, profile, config)`에
넣고 마지막 원소를 커밋한다. 분기·초기 상태 주입·부분 시퀀스가 없다 — (a)의 무조건 진술이 여기서 성립한다.

**§10 06d 완료 기준의 실행 가능성**: `*CatchupTest*`는 (i) `tick_input` 3행 순차 삽입 후 fold 결과가
동일 입력의 `engine_ref` 산출과 일치 (ii) 상한·예산 초과 시 남은 일자에 행이 생기지 않고 `gap_before`가
기록됨 (iii) **간극 이월의 상한 증인** — 강등 스트릭 2를 남긴 채 간극을 만들고, 간극 후 첫 이탈 틱에서
강등이 커밋되는 것과 신선한 카운트(3틱)의 차이가 정확히 2틱임을 assert(위 표의 수치가 코드로 고정된다).

### 9-C. BT-05 패리티 실행 규격 (실행 가능 수준)

**실행 형태**: `:core:engine`의 **JVM 단위 테스트**(`./gradlew :core:engine:test --tests "*ParityTest*"`).
`:core:engine`은 Android 무의존이므로 계측이 불필요하고, CI에서 상시 회귀로 돈다.
계측 실행은 하지 않는다 — 엔진에 Android API 의존이 없으므로 계측이 추가로 증명하는 것이 없다.

**주입 산출물 규격** (Python 측 신규 스크립트 `scripts/export_parity_fixtures.py` — SSOT 아님, 하니스 부속):
`backtest/results/parity/<window>/` 아래 4개 파일. 전부 결정적(정렬 고정·부동소수 `repr` 왕복 무손실).

| 파일 | 형식 | 내용 | 소비 계층 |
|---|---|---|---|
| `raw.jsonl` | 1행=1레코드 | `{series_id, field, as_of(ISO date), value}` — 픽스처 parquet 롱포맷 그대로 | L0 |
| `grid.json` | JSON | `{trading_days: [ISO date…], eval_start, eval_end, padding_days, confirm_time_kst, profile, registry_version}` | 전 계층(틱 그리드·웜업 범위 고정) |
| `expected.jsonl` | 1행=1틱 | `{evaluated_at(ISO UTC), kst_date, indicators:{id:{value, as_of, visible_at, stale, severity}}, composite, coverage, distinct_axes, any_crit, any_extreme, phase}` | L1~L5 |
| `MANIFEST.sha256` | 텍스트 | 위 3파일의 SHA-256 | 무결성 |

- Kotlin 테스트는 **`raw.jsonl` + `grid.json`만 입력으로 받고** 나머지를 스스로 계산한 뒤 `expected.jsonl`과 대조한다
  (`expected`를 입력으로 쓰면 아무것도 검증하지 않는다).
- 양측이 `MANIFEST.sha256`을 검증한다 — Python이 export 후, Kotlin이 로드 전. K-16과 같은 드리프트 방어.
- configs는 **앱 assets의 것을 읽는다**(하니스 configs를 별도로 읽지 않는다) — assets 드리프트가 있으면
  패리티가 깨지도록 의도적으로 결합한다.
- **웜업 일치 assert**: assets의 웜업 키(P-14) 값 == `grid.json.padding_days`. 두 값이 갈리면 z 기준선이
  갈리므로 **패리티 실행 전에 즉시 실패**시킨다(L2에서 뒤늦게 발견하면 원인 추적 비용이 크다).
- 픽스처에는 `lane='PREVIEW'` 행이 없으므로 §9-B-2a의 lane 필터는 패리티에서 **no-op**다 — 프로덕션에만
  존재하는 방어가 패리티 결과를 바꾸지 않음을 명시 기록한다.

**계층별 판정 기준** (전 계층이 게이트다 — L4만 보면 임계 경계 버그가 숨는다)

| 계층 | 비교 대상 | 허용 오차 | 근거 |
|---|---|---|---|
| L0 raw 로드 | `(series_id, field, as_of, value)` 집합 **+ 웜업 포함 범위**(`eval_start − padding_days` … `eval_end`) | **완전 일치**(Double 비트 동일) | 파싱만 하는 계층. 범위가 짧으면 L2 롤링 통계가 조용히 달라지므로 **행 수까지 대조**한다 |
| L1 `visible_at` | 지표×틱의 `visible_at` | **완전 일치**(초 단위) | 순수 달력 함수(9-B-1). 오차를 허용할 이유가 없다 |
| L2 지표값 | 지표별 틱값 | 상대 1e-9 또는 절대 1e-12 중 **큰 쪽** | 252틱 롤링 누산 순서 차이만 허용(double 상대오차 ~1e-16의 안전 배수) |
| L3 severity·stale | int 0..3, bool | **완전 일치** | 이산값. 임계 규약(임계는 "이상", stale은 "초과")까지 동일해야 한다 |
| L4 composite·coverage | Double | composite \|Δ\| ≤ **0.05**(D-18·BT-05 규정) / coverage \|Δ\| ≤ 1e-9 | coverage는 가중 합 비율이라 오차 여지가 없다(F-4가 골든에 이미 동결) |
| L5 phase 타임라인 | phase 열 + 전이 시각 | **완전 일치**(전 틱) | BT-05 규정 |
| L6 골든 | `golden_mobile.yaml` 2케이스 | **완전 일치**(phase·composite·coverage·fired_axes) | D-08 × D-16 |

> **L2를 별도 게이트로 두는 이유**: severity는 계단 함수라 임계 근방의 1e-6 차이가 severity를 1단계 뒤집고,
> 그 결과가 `distinct_axes`·`or_any_crit`을 통해 국면을 바꾼다. L4의 0.05만으로는 "오늘은 우연히 안 걸린"
> 경계 버그가 통과한다. L2 위반은 L4가 통과해도 **FAIL**이다.

**판정 창 범위**

| 범위 | 창 | 게이트 여부 | 근거 |
|---|---|---|---|
| 필수 | **9창 전체 × `mobile_daily`**(`backtest/windows.yaml` 정의 범위, 틱 수는 `metrics.json` 산출 그대로) | **게이트** | 골든 2창만으로는 `or_any_extreme`·D-26 짝지음 경로가 **한 번도 발화하지 않는다**(MT0-08 실측: 골든 발화 0회). `w2026_structural`만이 ORANGE 승격·이스케이프 경로를 태운다 — 그 창을 빼면 M1이 포팅한 D-26이 검증되지 않는다 |
| 필수 | 골든 2케이스(`w2024_carry_unwind` 양성 · `w2024_05_calm` 음성) × `mobile_daily` → `golden_mobile.yaml` | **게이트** | D-08·BT-05 명시 조건 |
| 참고 | `w2015_cny_deval` × `server_intraday` 1창 | 비게이트(진단) | 프로파일 주입 배선 오류를 싸게 잡는다. 모바일 앱은 이 프로파일을 쓰지 않으므로 게이트로 두지 않는다 |

**퇴화 입력 증인**(REVIEW_M0 신설 규율 ① — 파생 수치는 퇴화 입력 증인 테스트를 갖는다). 아래 4종을
합성 픽스처로 만들어 패리티 스위트에 **고정 포함**한다:
(i) 전 지표 결측 틱 → 양측 `composite=null`·국면 동결·틱 미소비
(ii) 단일 지표만 유효 → coverage = 해당 가중/31.0, composite = 그 지표 단독 산식
(iii) **스테일 경계 등호**: `evaluated_at − visible_at == stale_window` → 양측 **stale 아님**(초과만 stale)
(iv) 임계 경계 등호: 지표값 == `crit` 임계 → 양측 severity 3(임계는 "이상")

**실패 시 진단 절차**: L0→L6 순으로 첫 불일치 계층을 보고한다. `expected.jsonl`이 중간 계층을 전부 담고 있으므로
composite 불일치를 눈으로 역추적할 필요가 없다 — 이 규격의 실질적 목적이 그것이다.

---

## 10. 서브태스크별 완료 기준 (실행 가능한 명령)

전 항목 공통 전제: `qa-verifier → aaa-critic` 2단 PASS, 완료 보고에 `git status --porcelain` 원문 첨부
(REVIEW_M0 신설 규율 ③), qa는 보고-저장소 일치를 첫 단계로 확인(규율 ④).
공통 회귀(전 phase): `uv run ruff check . && uv run pytest -q` + `uv run pytest -q backtest/test_golden.py`.

| 서브태스크 | 완료 기준(명령) | 추가 판정 |
|---|---|---|
| 01a | `./gradlew check` green(빈 프로젝트 상태) | minSdk 29·targetSdk 확정, 카탈로그 전 의존 버전 핀(AAA §2.3) |
| 01b | **정본**: `./gradlew :app:connectedDebugAndroidTest --tests "*AssetHashInstrumentedTest*"` green(TASK 원문 "계측 테스트가 SHA-256 일치 검증") · **보강**: `./gradlew verifyConfigHashes` + `:app:testDebugUnitTest --tests "*AssetIntegrityTest*"` | configs 1바이트 변조 시 3층 **전부 FAIL** 재현(뮤테이션 증인). 계측 실행 환경(실기기/에뮬레이터) 확보가 MT1-01 완료의 전제 |
| 01c | `./gradlew :core:krx:compileKotlin` green + `UPSTREAM.md` 해시 기록 | vendor 디렉토리 수정 0건(Konsist 또는 CI diff) |
| 01d | `./gradlew :app:testDebugUnitTest --tests "*NavigationSmokeTest*"` | 3화면 진입 가능 |
| 01e | `./gradlew :app:detekt` green + `--tests "*ArchitectureTest*"` | `SwallowedException` 위반 주입 시 FAIL 재현 |
| 02a | `uv run pytest -q tests/test_contract_snapshots.py` | pydantic 모델 변경 시 FAIL 재현 |
| 02b·02c | `./gradlew :core:contracts:test --tests "*SnapshotRoundTripTest*"` | 양측이 **같은 파일**을 읽음을 경로 assert |
| 03a | `--tests "*AppendOnlyTest*"` + `--tests "*ArchitectureTest*"` | `@Update`/`@Delete` 주입 시 컴파일 또는 테스트 FAIL. **스키마 granularity assert**: `(series_id, field, as_of, revision)` UNIQUE 존재·`indicator_id`/`visible_at` 컬럼 부재 |
| 03a' | `--tests "*LoadSeriesTest*"` | 범위 조회가 as_of당 최신 1건으로 접히는지(정렬+fold), **14쌍** 계약 전건 왕복, 빈 범위·단일 행 퇴화 입력 |
| 03a'' | `--tests "*PreviewLaneIsolationTest*"` | **M-43b 표 ①②행 증인**: ① PREVIEW 행만 있는 as_of → 확정 fold에서 **MISSING**(lane 필터 제거 시 FAIL) ② 두 lane 공존 시 프리뷰 조회가 **CONFIRM**을 택함(`CASE lane` 정렬 제거 시 FAIL) |
| 03b | `--tests "*VisibleAtQueryTest*"` | **`visible_at` 컬럼 부재** assert(스키마 검사) + 역산 cutoff 조회(9-B-2 표 4행) + 동일 as_of에서 개정치 우선·`id` tie-break + naive datetime 주입 시 예외(K-05). **cutoff 역산을 kind 무시로 바꾸면 FAIL**하는 증인 1건 |
| 03b' | `--tests "*VisibleAtRuleTest*"` | 9-B-1 규칙 4종(us_market strictly-after / fred +lag on-or-after / krx·fx on-or-after / 2계열 worst-of-max) + **cadence 키 부재 → `daily_kr` 폴백**(mobile×intraday_30m 3지표) + 스테일 등호 경계(초과만 stale) + **프리뷰가 같은 함수를 `evalTickTime=now`로만 다르게 호출함**(`observed_at` 미사용 assert — 절단 계열을 재수집해도 스테일 배지가 유지되는 증인 1건) |
| 03b'' | `--tests "*TickInputFreezeTest*"` | `tick_input` 동결(§9-B-4b): 커밋 후 grid를 정정해도 과거 틱 fold 결과 불변, `run_log` purge 후에도 fold 재현 가능 |
| 03c | `--tests "*RunLogTest*"` · `--tests "*TickInputTest*"` | lake DAO와 물리 분리 assert. `run_log`는 purge 가능·`tick_input`은 purge 경로 부재(§9-B-4b) 양쪽 assert |
| 03d | `--tests "*CsvExportTest*"` + 실패 경로(권한·용량) 테스트 | CSV에 키·비밀 미포함 assert |
| 04a~04f | 어댑터별 `--tests "*<Provider>AdapterTest*"`(MockWebServer, 네트워크 금지) | **오류 경로 필수**: 4xx/5xx/타임아웃/스키마 파손/빈 응답 각 1건 |
| 04g | `--tests "*CollectionOrchestratorTest*"` | 부분 실패 격리(1개 실패가 나머지를 막지 않음), INV-2 예산, 사유 코드 매핑 전건 |
| 05a·05b | `--tests "*EngineUnitTest*"` + `--tests "*TransformOrderTest*"` + 경계값·결측·타임존 | `min_periods=window` 전 transform 이식(웜업 부족 → null), **필터-후-transform 금지**(§9-B-2 4단계, Konsist), D-26 증인 3종 이식, 상수 입력 한계진동 0 |
| 05c | `uv run python scripts/export_parity_fixtures.py` → `./gradlew :core:engine:test --tests "*ParityTest*"` | **§9-C 전문**: L0~L6 계층별 기준 전건, 9창 × `mobile_daily` 전 틱, 골든 2케이스 일치, 퇴화 입력 증인 4종(스테일 등호·임계 등호 포함), `MANIFEST.sha256` 검증 |
| 06a | `--tests "*ScheduleTest*"` + `--tests "*HolidaySkipTest*"` | 휴장일 미커밋, 17:00 이후 실행 정상 판정 |
| 06b | `--tests "*ConfirmTickPipelineTest*"` | PARTIAL 진행, FATAL 5종만 중단 |
| 06c | `--tests "*IdempotencyTest*"` | 동시 2회·순차 2회 모두 `DEDUPED` |
| 06d | `--tests "*CatchupTest*"` | 3일 순차 커밋, 1일 실패해도 계속, `pit_quality=BACKFILL` 기록, 벽시계 예산 초과 시 절단 경로 합류. **§9-B-4(c)의 3항**: (i) `tick_input` fold 결과가 동일 입력의 `engine_ref` 산출과 일치 (ii) 상한(`catchup_max_ticks`, assets) 초과 시 누락분 행 미생성 + `gap_before` 기록 (iii) **간극 이월 상한 증인** — 강등 스트릭 2를 남기고 간극 후 첫 이탈 틱에서 강등, 신선 카운트 대비 차이 == 2틱 assert |
| 06e | `--tests "*NotificationBudgetTest*"` | 1일 1건, 3일 격상 |
| 07a·07b | `--tests "*PreviewTest*"` + `--tests "*CarryForwardIsolationTest*"` + `--tests "*CarryForwardLaneTest*"` | 프리뷰가 상태기계 상태 불변(TASK ①), 확정 경로에서 호출 불가(TASK ②), **M-43b ③행**: 이월 원천 = `tick_input` 마지막 커밋 행의 severity, 반환 타입 `Map<String,Int>` 고정. (i) `tick_input` 0행 → 이월 없이 결측 유지(**M-50**) (ii) PREVIEW 수집 누적에도 이월값 불변 (iii) 원천을 `observation`으로 바꾸면 FAIL |
| 07c | `--tests "*CoverageSuppressionTest*"` | `raw_coverage == 21.0/31.0`(분수식 비교, 리터럴 금지) 산출·억제(TASK ③) + §4.5 ④의 **3값 동시 재현**(이월 없음 66.67 / 서버 45.16 / 본 구현 = 45.16 산식 + 여전히 억제). 임계는 assets `engine.preview_coverage_min`에서 읽음을 assert(하드코딩 시 FAIL) |
| 07d | `--tests "*SuppressionUiTest*"` | 텍스트 라벨·contentDescription 존재, 수치 미삭제 |
| 08a | `--tests "*NotificationChannelTest*"`·`*CatchupNotification*`·`*NotifPermissionDenied*` | 채널 3종 생성, 캐치업 1건, 권한 거부 대체 경로 |
| 08b | `--tests "*HomeStateExhaustiveTest*"` | INV-1 전 상태 |
| 08c | `--tests "*RunHistoryScreenTest*"` | 실패 항목 3탭 이내 도달 |
| 08d | `--tests "*KeyRedactionTest*"`·`*BackupExclusionTest*`·`*BatteryOptimizationHintTest*`·`*WarmupBackfillTest*` | 로그 키 0건, `allowBackup=false`, **550일 백필의 중단 재개·진행 표시·`WARMUP_INSUFFICIENT` 배너**(§4.4) |
| 08e | `docs/runbooks/M1_SMOKE.md` + `scripts/smoke_collect.ps1` 존재·건식 실행 | 사용자 수행 항목 U1~U8 명시 |
| 08f | `--tests "*FailureCatalogTest*"` | `ReasonCode.entries` **전건** (a)(b)(c) 검증 |
| **GM1** | `./gradlew check` + `./gradlew :app:connectedDebugAndroidTest` + 스모크 증빙 6종 + `uv run pytest -q` | GATE_GM1.md + 사용자 승인 |

---

## 11. 미해결 결정 목록 (Advisor·사용자 상신 — 권고안 포함)

| ID | 결정 사항 | 선택지 | 권고 | 근거 | 블록 대상 |
|---|---|---|---|---|---|
| **U-1** | 확정 틱 시각 | (a) 16:20 (b) **17:00** (c) V-2 실측 후 17:30/18:00 | **(b), V-2 실측을 조건으로** | §5 논증 5개. 특히 16:20은 `daily_kr 16:50` 수집보다 앞서 자기모순(F-1) | MT1-06a, P-7 |
| **U-2** | coverage 억제 임계 0.80의 SSOT 위치 | (a) `indicators.yaml engine.preview_coverage_min` (b) `statemachine.yaml preview:` (c) 앱 상수 | **(a)** | **§8 P-1 보강란의 3안 비교표** 참조. 요지: coverage 산출 주체가 engine이고(F-3) 결측·스테일 규율이 같은 블록에 있다. (b)는 상태기계가 소비하지 않는 키가 되고, (c)는 SSOT 위반 | MT1-07c, P-1 |
| **U-3** | G-4 kr_cds 수집 여부 | (a) 모바일 수집 구현 (b) **미수집 확정 + "미수집" 배지** | **(b)**, 단 V-4 실측 후 확정 | optional·가중 1.5·스크래핑은 K-18류 상시 파손. **(b)는 D-23 §23.2의 67.7% 예시와도 정합**(F-6) — (a) 채택 시 MT1-07 ③ 기대값 재산출 필요 | MT1-04f, MT1-07c |
| **U-4** | VKOSPI 소스(F-10) | (a) 모바일도 realized_vol 폴백 강제 (b) 모바일은 실측 VKOSPI 사용 | **(a) + 실측 VKOSPI 병행 수집·판정 미사용** | 픽스처·골든·BT-05가 전부 폴백 기준이다. (b)는 GM1 하드 조건인 패리티를 즉시 깬다. 병행 수집으로 C1 전환 근거는 확보 | MT1-04c, MT1-05c |
| **U-5** | 알림 권한 거부 시 | (a) 앱 차단 (b) **정상 동작 + 배너·이력 대체** | **(b)** | 차단은 사용자 이탈. 단 배너 없는 (b)는 조용한 실패 | MT1-08a |
| **U-6** | 캐치업 소급 상한 — **값 + SSOT 승격 + 간극 처리 방식** | (a) 무제한 (b) **20영업일 + `catchup_max_ticks` SSOT화 + 벽시계 예산 10분 + 간극은 카운터 무조작·공시(엔진 변경 없음)** (c) 10영업일 + 카운터 리셋(엔진 변경) | **(b)** — 초안의 10과 P-13(엔진 변경)을 **모두 철회** | 10은 히스테리시스 상수 합(3+5+2)과 같아 여유 0. 20 = 2 × 완결 사이클(§4.6-B). 간극 이월 오차는 §9-B-4(c) 실측으로 **강등 2틱 조기·승격 0**에 유계이고, 리셋의 편익이 그 비용(서버 공유 엔진 변경 + BT-05 미검증 분기)보다 작다. **판정 영향값이므로 앱 상수가 아니다**(§4.6-A) | MT1-06d, P-11·P-12 |
| **U-7** | 해시 검증 소스셋 | (a) **계측 테스트 정본(TASK 원문) + Gradle·Robolectric 보강 병행** (b) Gradle+Robolectric으로 대체 | **(a)** — 초안의 (b) 권고를 **철회** | TASK 서브태스크 요구 축소는 브리프 §1 위반. 1·2층은 빌드 산출물만 증명하고 **설치된 APK가 로드하는 바이트**를 증명하지 않는다(K-16의 마지막 구간). 계측 실행 환경 확보만 별도 확인 사항 | MT1-01b |
| **U-8** | `run_log` 보존 정리 허용 | (a) append-only 물리 강제 동일 적용 (b) **lake와 분리, 180일 purge 허용** | **(b)** | lake(관측 원장)와 운영 로그는 성격이 다르다. (a)는 무한 성장 + 아키텍처 테스트 모순 | MT1-03c |
| **U-9** | 프리뷰 쿨다운·일 상한 | (a) 없음 (b) **60초 / 일 50회** | **(b)**, 앱 상수(SSOT 아님) | RC-14. 프리뷰는 국면을 커밋하지 않으므로(D-17 §1) 상태기계 입력 시퀀스에 **어떤 값도 입력하지 않는다** — §4.6-A 판별식상 판정 무관값. 캐치업 상한(U-6)과 대비되는 지점 | MT1-07a |
| **U-10** | 스모크의 실 API 키 취급 | (a) 사용자 실키 (b) 별도 테스트 키 | **(a) + 자동 유출 검사** | 실키 없이는 실동작 검증 불가. `smoke_collect.ps1`이 logcat grep을 자동 판정해 사람 눈에 의존하지 않음 | MT1-08e |
| **U-11** | 휴장일 확정 틱 규율의 문서 지위 | (a) 구현 관례 (b) **D-27 신설(또는 D-23 부기)** | **(b)** | 휴장일 커밋은 D-23 §23.2의 방향성 오류를 확정 국면에 주입한다 — 관례가 아니라 결정이어야 회귀를 막는다 | MT1-06a, P-9 |
| **U-12** | ECOS item_code 미해소(F-7) 처리 | (a) V-3 실측 후 P-2 반영 (b) 지표 상시 결측 수용 | **(a)**, 실패 시 (b)를 GM1에 명시 기록 | credit 축이 CDS(G-4)와 합쳐 2/3 결손이면 `distinct_axes` 충족이 실질적으로 불리해진다 — 게이트에 반드시 기록 | MT1-04d |
| **U-13** | 캐치업 노티 요약 규칙 | (a) 전이별 발신 (b) **요약 1건** | **(b)** | RC-5. 10일 캐치업이 노티 10건이면 채널이 죽는다 | MT1-08a |
| **U-14** | targetSdk | (a) 33 (b) **최신(35/36)** | **(b)** | 최신 유지 시 `POST_NOTIFICATIONS`·백그라운드 제약을 지금 다룬다. 미루면 M3에서 한꺼번에 터진다 | MT1-01a |
| **U-17** | 병합 결정 대기 2건(본 계획의 입장 고정, 최종 채택은 Advisor) | **M-42** 웜업 키명: A `engine.warmup_calendar_days` / D `engine.warmup_padding_days` · **M-39** 프리뷰 나이 산식: 본안(`visDay @ evalTickTime`, 24h 배수) / D안(실경과) | 키명은 **어느 쪽이든 수용**(위치 `indicators.yaml engine:`·값 550·하니스 동일성만 고정). 나이 산식은 **본안 유지 권고** | M-42는 이름만의 문제라 다툴 실익이 없다. M-39는 §9-B-1 비교표 — 시각 무관 안정성 + 산식 단일화(확정 경로 하니스 동일성이 구성적으로 보장) vs 최대 7h 관대. 대가는 카드의 `as_of` 상시 병기로 완화하고 GATE_GM1에 기록 | MT1-05a·07c |
| **U-16** | 초기 웜업 백필 범위(§4.4·§9-B-2) | (a) **550 달력일(하니스 `padding_days`와 동일)** (b) 400일로 축소 (c) 백필 없이 운영하며 이력을 쌓아 감 | **(a)** | 하니스와 다른 웜업은 z 기준선을 갈라 **BT-05 패리티를 깬다**. (c)는 최초 1년간 z 계열 6종이 상시 결측이라 사실상 판정 불가 — "조용히 틀린" 상태로 운영하는 것이라 §4.1 원칙 위반. 비용은 계열당 1~2회 기간 조회로 낮다 | MT1-04·08d, GM1 스모크 S0 |
| **U-15** | 실기기 스모크 **확장 절차** 승인 여부(§4.7) — 사용자 추가 시간 약 25분 | (a) 필수 절차만(게이트 명시 조건 충족) (b) **확장 4종 포함**(캐치업·오프라인·키오류·멱등) (c) 멱등(S9)만 추가 | **(b)** | (a)만으로는 §4.1 카탈로그 24종의 **실기기 증거가 0건**이고, GM1이 "실패 경로 UX 정의됨"을 JVM 테스트만으로 주장하게 된다. 특히 WorkManager 실지연·OEM 절전은 JVM에서 재현 불가. 승인 불가 시 **최소 (c)**를 권고(S9 멱등은 MT1-06 완료 기준 자체) | MT1-08e, GM1 |

---

## 12. 예상 커밋 단위 (`m1-xx:` 프리픽스, 영어 메시지)

```
m1-00: verify external endpoints for mobile collectors (V-1..V-6 journal)
m1-01a: scaffold gradle modules, version catalog, ktlint/detekt/kover
m1-01b: syncConfigs task + SHA-256 drift guard (K-16)
m1-01c: vendor krxkt library with pinned upstream hash
m1-01d: app skeleton (DI, navigation, theme, UiState)
m1-01e: failure invariants harness (INV-1..3, detekt/konsist rules)
m1-02a: python contract snapshot generator + freeze test
m1-02b: kotlin contract mirror + round-trip snapshot test
m1-03a: room append-only lake keyed by (series_id, field, as_of, lane, revision)
m1-03b: derive visible_at (pure fn) + loadSeries range contract + cutoff lookup
m1-03c: run_log (operational, purgeable) + tick_input (frozen engine inputs)
m1-03d: csv export + drive backup hook with failure paths
m1-04a: yahoo REST adapter with stooq fallback (K-01/K-18)
m1-04b: fred adapter (T+1 as-of, K-05)
m1-04c: krx adapter via krxkt (login, session expiry, business days)
m1-04d: ecos adapter with verified item codes (K-04)
m1-04e: kis adapter (optional, disabled by default)
m1-04f: decide kr_cds collection path (G-4)
m1-04g: collection orchestrator (budgets, partial-failure isolation)
m1-05a: port engine transforms/scoring/modifiers (min_periods, transform-before-visibility)
m1-05b: port statemachine with D-26 pairing and or_any_extreme
m1-05c: BT-05 parity harness (python export + layered L0..L6 comparison)
m1-06a: daily confirm tick scheduling with holiday skip
m1-06b: confirm tick pipeline (collect-append-evaluate-fold-commit-notify)
m1-06c: idempotent commit (unique constraint + mutex)
m1-06d: catch-up for missed business days (gap_before disclosure, backfill pit flag)
m1-06e: failure reason codes and notification budget
m1-07a: preview pipeline (non-committing, D-17)
m1-07b: isolate carry-forward to preview-only module (D-23)
m1-07c: coverage computation and suppression rule
m1-07d: suppression UX with accessible labels
m1-08a: three notification channels with dedup and catch-up summary
m1-08b: functional home screen
m1-08c: run history screen
m1-08d: minimal onboarding/settings (keys, keystore, battery hint, 550d warmup backfill)
m1-08e: device smoke runbook + evidence collection script
m1-08f: failure catalog regression suite
```

SSOT 변경 제안이 승인되면 별도 커밋으로 분리한다:
`chore(ssot): add engine.preview_coverage_min (D-23 §23.3-3)` / `chore(ssot): verified ECOS item codes (K-04)` /
`docs: confirm tick 16:20 -> 17:00 (M1 plan council)`.

---

## 13. 브리프 §5 필수 질문 대조표

| # | 질문 | 본 계획의 답 위치 |
|---|---|---|
| 1 | Gradle 구성·`check` 범위 | §9-A, §10(01a·01b) |
| 2 | kotlin_krx 통합·로그인 정책 확인 | §9-A(vendoring 권고), §7 V-2, F-9 |
| 3 | syncConfigs·해시 검증 소스셋 | §9-A, 결정 U-7, §10(01b) |
| 4 | contracts 미러·스냅샷 | §9-A, §8 P-10, §10(02a·02b) |
| 5 | Room append-only·as-of·CSV·백업 | §9-B-1(visible_at 파생 규칙·cadence 폴백)·§9-B-2(스키마·cutoff 역산 조회)·§9-B-4b(`tick_input` 동결), §4.6(run_log 분리), 결정 U-8 |
| 6 | collectors 6건·실측·폴백·결측·병렬 | §2 그래프, §7 실측표, §4.1 카탈로그, **§9-B-2 14쌍 계열·필드 계약 + §9-B-2a lane 격리**, §4.4 초기 웜업 백필, §10(04a~04g) |
| 7 | Kotlin 엔진·D-26·Double·KST | §9-B |
| 8 | BT-05 실행 형태·픽스처 주입·검증 위치 | **§9-C 전용 절**(주입 산출물 4파일 규격, L0~L6 계층별 기준, 창 범위, 퇴화 증인 4종) |
| 9 | 확정 틱 시각 논증 | **§5**(전용 절), 결정 U-1 |
| 10 | 캐치업 멱등·이중 실행·이력 | **§4.6**(규율)·**§4.6-A/B**(상한 도출)·**§9-B-4**(전량 fold 호출·`tick_input` 동결·간극 공시, 엔진 변경 없음), §10(06c·06d) |
| 11 | 프리뷰 carry-forward 격리·coverage·억제 UX | **§4.5**, §9-B(계산 위치) |
| 12 | 노티 3채널·홈의 M1/M2 경계 | **§4.2·§4.3** |
| 13 | 실기기 스모크 절차·GM1 증빙 | **§4.7** |

---

## 14. 이 계획이 스스로 인정하는 약점

정직성 조항(BACKTEST_PLAN §5의 정신을 계획 문서에 적용):

1. **§4.1 카탈로그 24종은 완전성이 증명되지 않았다.** 전수라고 주장했으나 이는 "현재 알려진 경로의 전수"다.
   완전성 방어는 `ReasonCode` enum 전건 검사(INV-3)로 **미래에 추가되는 경로가 테스트 없이 지나갈 수 없게**
   하는 구조적 장치이지, 오늘의 목록이 빠짐없다는 증명이 아니다.
2. **17:00 확정 틱은 아직 실측되지 않았다.** §5의 논증 5개 중 4개는 문서 정합성 논증이고, 물리적 확정성
   (17:00에 당일 KRX 데이터가 확정인가)은 V-2가 답해야 한다. 실측 전에 코드를 쓰면 M0의 K-04 실패가 반복된다.
3. **coverage 임계 0.80의 값 자체는 검증된 적이 없다.** D-23이 제시한 가설이며 D-04 규율("모든 임계값은 가설")이
   그대로 적용된다. P-1로 SSOT에 올리는 목적은 값의 정당화가 아니라 **C1에서 보정 가능한 위치에 두는 것**이다.
4. **실기기 1일 스모크는 K-15(OEM 절전)를 충분히 검증하지 못한다.** 절전 킬은 며칠에 걸쳐 발현하는 현상이고
   1일로는 표본이 1이다. 이는 M3의 7일 소크가 담당하며, M1에서는 "누락이 발생하면 이력에 보이는가"를
   검증하는 것으로 범위를 한정한다 — 이 한계는 GATE_GM1에 명시 기록해야 한다.
   **U-15가 미승인되면 여기에 한 줄이 더 붙는다**: "§4.1 실패 경로 24종 중 실기기에서 관측된 것은 0건이며,
   전부 JVM 테스트 근거다."
5. **`visible_at` 규칙은 하니스에서 이식한 것이지 프로덕션에서 실측된 것이 아니다.** 특히
   `us_market → 다음 KR 영업일` 규칙은 야후 일봉의 실제 게시 시각이 아니라 **근사-PIT 가정**이며
   (BACKTEST_PLAN §5-4의 KRW=X ~16시간 가시성 근사와 동종 문제), 실기기에서는 확정 틱 시각에 이미
   전일 미국 종가가 조회 가능할 수 있다. **패리티를 위해 근사 규칙을 그대로 쓰는 것**이 본 계획의 선택이며,
   실제 가시성과의 괴리는 C1(O-1·O-2)의 몫이다 — GATE_GM1에 이 선택과 사유를 기록한다.
6. **간극 이월 오차(강등 최대 2틱 조기)를 없애지 않고 공시로 처리한다.** §9-B-4(c)의 비용-편익 판단이며,
   "2틱은 작다"는 판단 자체가 가치 판단이다. 일 1틱 시스템에서 2영업일은 사용자에게 짧지 않을 수 있다 —
   실사용에서 문제로 드러나면 M3/C1에서 재검토한다. 지금 이 선택을 하는 이유는 대안(엔진 변경)이
   **서버와 공유하는 실행 명세를 BT-05가 검증하지 않는 방향으로 넓히기** 때문이다.
7. **fold 전량 재생은 configs 변경 이력을 고려하지 않는다.** `tick_input`에 `registry_version`을 기록해
   "이 틱은 어느 레지스트리로 계산됐는가"를 남기지만, 서로 다른 레지스트리로 산출된 틱이 한 타임라인에
   섞이는 것 자체를 막지는 않는다(0.3.1-rc → 0.4.0 승격 시 실제로 발생한다). M1은 기록까지만 하고,
   재계산·분할 정책은 레지스트리 승격을 다루는 C1/M3의 몫으로 명시 이월한다.
8. **프리뷰 나이 산식(M-39)은 최대 약 7시간 관대하다.** §9-B-1에서 시각 무관 안정성을 위해 의도적으로
   택한 대가이며, 스테일 배지가 하루 늦게 켜질 수 있다. 카드의 `as_of` 상시 병기가 유일한 완화이고,
   실사용에서 문제가 되면 M-39를 D안(실경과)으로 되돌리는 것이 회수 경로다.
9. **minSdk 29의 SQLite 윈도 함수 지원 여부를 실측하지 않았다.** 채택한 정렬+fold 형태가 버전 무관이라
   설계가 흔들리지 않을 뿐, "쓸 수 없다"는 단정은 본 계획의 근거가 아니다 — 라운드 3 서술을 정정했다.
10. **L2 허용 오차 1e-9은 실측 근거가 없는 가설이다.** pandas 롤링 누산과 Kotlin 구현의 실제 편차는
   MT1-05c 첫 실행에서 측정된다. 측정값이 이 범위를 넘으면 **오차를 늘리기 전에 알고리즘 정렬을 먼저 시도**한다
   (게이트를 느슨하게 해 성공으로 위장하지 않는다 — GATE_GM0 AD-1(iv)). 완화가 불가피하면 사유·측정값과 함께 상신한다.
