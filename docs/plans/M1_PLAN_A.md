# M1 실행 계획 — 관점 A (아키텍처·의존성)

- 작성일: 2026-08-06 · **개정: R2**(A-1~A-8 + 공통 ①②③) · **R3**(A-9~A-11) · **R4**(N-1·A-12) · **R5**(A-13 + 공통 ①②) · **R6**(A-14·A-15 + M-43b 전수표) · **R7**(A-16 이월 원천 계층 전환)
- 작성: plan-architect A · 절차: AAA_QUALITY_STANDARD §3 (plan council)
- 대상: **M1 모바일 코어**(TASK_mobile_m1.md, MT1-01~08) · 게이트: **GM1**
- 지위: 관점 A(모듈 경계·빌드 재현성·kotlin_krx 통합·계층 분리·의존성 핀)를 심화하되,
  **그 자체로 실행 가능한 전체 계획**이다. B(데이터·정합성)·C(UX·운영) 영역도 비워두지 않았으나,
  해당 관점 계획서와 충돌 시 각 관점의 전담 영역이 우선한다(§13 병합 지침).
- 입력 정본: `docs/plans/M1_COUNCIL_BRIEF.md` §1 허용 목록. 그 밖의 저장소 탐색은 하지 않았다.
- **본 문서는 계획만 쓴다.** configs/·contracts/·prompts/ 및 기존 코드는 일절 수정하지 않았다.
  변경이 필요한 항목은 전부 §11 "SSOT 변경 제안"으로만 기록했다.

---

## 0. 요약 — 이 계획이 확정하는 아키텍처 결정 9건

| ID | 결정 | 한 줄 근거 |
|---|---|---|
| **AD-A1** | 모듈 3개: `:engine`(순수 JVM) · `:krx`(순수 JVM) · `:app`(Android). 그 이상 쪼개지 않는다 | 경계마다 "기술적 강제"가 있다 — 엔진은 디바이스 없이 파리티를 돌려야 하고, krx는 외부 출처 코드다. 나머지는 패키지 분리로 충분(YAGNI) |
| **AD-A2** | kotlin_krx는 **벤더링(소스 복사) + 출처 매니페스트**. 컴포지트 빌드·mavenLocal·절대경로 참조는 비채택 | 저장소 하나만 clone하면 빌드돼야 한다(재현성). 버전 카탈로그 하나로 Kotlin/OkHttp/coroutines 정렬. 대안(서브모듈)은 §10 U-1로 상신 |
| **AD-A3** | assets는 **생성물**이다. `syncConfigs`가 `build/generated/ssot-assets/`로 내보내고 `src/main/assets`에 체크인된 사본을 두지 않는다 | K-16 드리프트를 테스트로 잡는 대신 **구조적으로 불가능하게** 만든다. 편집 가능한 사본이 없으면 드리프트가 없다 |
| **AD-A4** | 해시 검증은 **2층**: JVM 테스트(저장소 원본 ↔ 매니페스트) + 계측 테스트(APK assets ↔ 매니페스트) | JVM은 드리프트를, 계측은 "패키징된 산출물"을 증명한다. 서로 대체 불가. CI 기본은 JVM |
| **AD-A5** | carry-forward 격리는 **타입으로 강제**한다: `ConfirmInputs`/`PreviewInputs` 분리 + `PreviewResult`에 phase 필드 부재. 아키텍처 테스트(Konsist)는 증거용 이중화 | "호출하지 마라"는 규약보다 "호출할 수 없다"는 타입이 강하다. TASK 완료기준 ①②를 컴파일러가 먼저 만족시킨다 |
| **AD-A6** | BT-05 파리티는 **3층**(L1 transform 벡터 · L2 파이프라인 벡터 · L3 원시 픽스처 end-to-end 1창) + golden_mobile.yaml 직접 대조. 실행은 **JVM**(`:engine:test`) | 엔진이 Android API를 쓰지 않으므로 계측은 증명력을 더하지 않고 디바이스 의존만 더한다(BACKTEST_PLAN BT-05가 JVM 타깃을 이미 허용) |
| **AD-A7** | 확정 틱 = **17:00 KST**. 16:20은 문서 정정 대상 | `schedules.collection.daily_kr=16:50`보다 30분 빠른 확정 틱은 SSOT 자기모순이고, 골든이 `confirm_time_kst: 17:00`으로 동결돼 있다(§9 논증) |
| **AD-A8** | 확정 틱은 `PeriodicWorkRequest`가 아니라 **OneTime + 자기 재예약 + `enqueueUniqueWork(KEEP)`** | K-14(비정시)를 우회하지 않고 정면 대응. 목표 시각 제어·캐치업·이중 실행 방지가 한 경로로 합쳐진다 |
| **AD-A9** | 계획서에 **의존성 버전 숫자를 박지 않는다**. 제약(Kotlin ≥ 2.1.0, JDK 17 toolchain, minSdk 29, 동적 버전 금지)만 고정하고 실제 값은 MT1-00e 실측 후 카탈로그에 기록 | 근거 없는 낙관 금지. 버전 매트릭스는 조회로 판정한다(D-20 §20.3 정신) |
| **AD-A13** ★라운드5 | **원장에 `lane` 판별자를 두고 확정 틱 조회는 `lane = 0` 하드 필터로 프리뷰 행을 배제한다.** "확정 우선 정렬"은 채택하지 않는다 | 확정 수집이 실패한 날 프리뷰 행이 **유일한 행**이 되므로 *선호*는 그대로 폴백해 장중 부분봉을 종가로 동결한다(불가역). D-17 §3의 "확정 틱은 마감 기준 as-of로 읽는다"는 배제 의무이지 우선순위가 아니다. 하드 필터는 사고를 **정직한 결측**으로 바꾸고, 결측은 엔진이 이미 처리한다(D-02·D-25 §3) — 새 코드가 필요 없다(§2.12) |
| **AD-A12** ★라운드4 | **지표 산출은 3단계 파이프라인이다: ① 원계열 범위 조회 → ② 전체 계열에 transform 1회 → ③ transform *출력*에 가시성 색인 후 bisect lookup.** 가시성은 원관측이 아니라 **transform 출력**에 붙는다 | 정본(`run_replay.py:289-317`, `_build_*`)이 그 순서다. 활성 15지표 중 다수가 `rolling(window, min_periods=window)`를 요구해 **1행으로는 계산 불가**이며, 가시성을 앞당겨 부분 계열을 변환하면 창 절단·`min_periods` 미달로 **다른 값**이 나온다(대부분 NaN → 전 지표 결측 → 국면 영구 GREEN). 라운드 2~3 설계의 "최신 1행 리졸버"는 이 결함을 안고 있었다(§2.11) |
| **AD-A11** ★라운드3 | **국면은 상태를 이어받지 않고 매 틱 재산출한다 — 동결된 `tick_input` 시퀀스 전량 fold.** 상태기계 카운터를 저장하지 않는다 | `engine_ref.statemachine.run()`은 **초기 상태 주입 파라미터가 없다**(L114 `phase = config.initial_phase`, 카운터 전부 지역 변수). 증분 저장은 엔진 API 변경을 요구하고 골든·파리티를 흔든다. 전량 fold는 **하니스·골든이 이미 하는 그 동작 그대로**라 엔진 변경 0 · 코드 0줄 추가 · 파리티와 프로덕션이 같은 함수를 탄다(§2.10) |
| **AD-A10** ★신설 | **`visible_at`은 저장 필드가 아니라 파생 함수다.** `run_replay.py`의 가시성 규칙을 `:engine`의 **프로덕션 모듈**(`pit/Visibility.kt`)로 이식하고, 스테일 판정·as-of 조회·캐치업이 모두 이 함수 하나를 쓴다. `observed_at`은 감사·진단 전용 | 스테일 정본이 `is_stale_check`(visible_at 기준)인데 앱이 그 값을 만들지 못하면 프로덕션과 파리티가 다른 세계에서 돈다. 파생으로 두면 캐치업·리플레이·프리뷰가 같은 규칙을 공유하고, 저장하지 않으므로 달력 정정 시 백필이 없다(§2.8) |

추가로 **신설 서브태스크 2개**를 제안한다(TASK의 MT1-01~08은 전부 유지·세분화, 축소 없음):
`MT1-00`(실측 선행 — 다른 모든 것을 블록하는 사실 확정), `MT1-09`(실기기 스모크 절차서 + 진단 내보내기 — GM1 증빙 수집 수단).

### 0.1 라운드 1 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **A-1** 프로덕션 스테일 기준 시각 미정의 | **§2.8 신설** · MT1-05a2 · AT-7 | `visible_at`을 파생 함수로 정의(계열별 규칙표) + `:engine/pit/Visibility.kt` 신설 + Python 산출값과의 **동일성 단언**을 L2 파리티에 추가 |
| **A-2** 캐치업 as-of 쿼리 내부 모순 | **MT1-03a 재설계** · MT1-06b | 필터를 `observed_at <= evaluatedAt` → **`visible_at <= evaluatedAt`**로 교체(파생). 캐치업 틱의 `evaluatedAt` = **그 거래일 D의 확정 시각(17:00 KST)**로 확정. 두 완료 기준이 양립 |
| **A-3** 억제 임계 0.80 SSOT 부재 | **§11 C-9 신설** | `configs/indicators.yaml` `engine.preview_policy.min_coverage: 0.80` 신설 제안 + 스키마 테스트 영향 분석(부분집합 검사라 회귀 0) |
| **A-4** ECOS `VERIFY` 실위치 오기 | **§11 C-2 정정 + C-8 신설** · MT1-00b | 실위치는 `configs/indicators.yaml` `krx_credit_spread_delta.source.item_codes` — indicators.yaml 대상 제안으로 분리 |
| **A-5** 45.2 의미 오독 | **MT1-07 ④ 재정의** · U-2 문구 정정 | 45.2 = **전체 분모(31.0) 유지 + KR 지표 severity 0**(서버 동시각), "이월 후"가 아니다. 이월 등가성은 파생 관찰로 격하 |
| **A-6** 17:00 물리 전제 실측 부재 | **MT1-00g 신설** | 16:00~18:00 폴링으로 지수 종가·투자자 순매수·VKOSPI의 **최초 확정 시각** 실측 → AD-3b "동시 재확인" 이행 |
| **A-7** 캐치업 상한 30의 근거 부재 | **§3 MT1-06b · U-11** | 30 **철회**. 잠정 20(≈1개월 거래일) + 3가지 도출 근거 + range 조회 상한 실측(MT1-00a~c) 후 확정 |
| **A-8** kotlin_krx 빈 리스트 정책 | **MT1-04c · MT1-06b · §7 R-14** | 빈 응답을 휴장 근거로 **쓰지 않는다**. 휴장은 사전 판정, 거래일의 빈 응답은 `Failed(EmptyOnTradingDay)`로 분류·이력 노출·재시도 |
| **공통 ①** 0.80 SSOT | §11 C-9 | 위 A-3 |
| **공통 ②** visible_at 산출 규칙 | §2.8 | 위 A-1 |
| **공통 ③** 커버리지 측정·강제 배선 | **§2.9 신설** · MT1-01b | JaCoCo 규칙·제외 목록·임계(코어 ≥90%/기타 ≥70%)·`check` 배선·실행 명령을 구체화 |

### 0.2 라운드 2 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **A-9** 상태기계 프로덕션 호출·상태 지속 모델 부재 | **§2.10 신설**(AD-A11) · MT1-03c · MT1-06a·06b · AT-9 | **(a)** 국면을 이어받지 않는다 — 동결 `tick_input` 전량 fold로 **매 틱 재산출**(엔진 API 사실과 정합) **(b)** 신설 `tick_input` 테이블에 `Tick`의 4필드(`composite`(NULL 허용)·`distinct_axes`·`any_crit`·`any_extreme`) + 감사 컬럼을 append-only·UPDATE 차단으로 동결 **(c)** 캐치업 절단은 **`composite=NULL` 틱 동결**로 표현 — D-25 §3이 이미 "국면·스트릭·카운터 동결"을 규정하므로 **엔진 변경 불요**. 라운드 2의 "직전 확정 국면 + 오늘 1틱만 계산"은 표현 불가이므로 **철회** |
| **A-10** stale 등호 규약 미기재 | **§2.8 규약 명문화** · MT1-05a | `(evaluatedAt − visibleAt) > window` — **초과만 stale, 등호는 fresh**. 경계 등호 증인 **W-V4** 신설(정확히 window면 유효, +1ms면 결측) |
| **A-11** 캐치업 상한의 SSOT 위치 부적합 | **§11 C-5 재지정** | `configs/sources.yaml` 신설안 **철회** → `configs/statemachine.yaml` `profiles.mobile_daily.catchup_max_trading_days`(동종 틱 카운트 파라미터와 같은 블록, B 제안 7·C P-11과 정렬) |

### 0.3 라운드 3 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **N-1** transform 입력 시계열 조회 계약 부재 | **§2.11 신설**(AD-A12) · §2.7 L1~L3 재정의 · MT1-03a 재설계 · MT1-05a2·대응표 | 3단계 파이프라인을 정본 순서대로 명문화: **Stage 1 원계열 범위 조회**(계약·워밍업 550일·revision 축약) → **Stage 2 전체 계열 transform 1회** → **Stage 3 출력 시계열 가시성 색인 + bisect lookup**. 라운드 2~3의 "최신 1행 리졸버"는 Stage 3의 역할이었고 Stage 1을 대체할 수 없음을 명시. 지뢰 6(2계열 인덱스 정렬)·7(**bisectRight 등호** — 확정 틱 시각에 막 가시화된 KRX 지표가 매 틱 소실되는 함정) 신설 |
| **A-12** 증인 W-S1의 대조 무효 가능성 | **§2.10 W-S1 조건 고정** | `promote_sustain_ticks=1`이라 D6가 승격 조건을 충족하면 `run([D6])`도 AMBER가 되어 대조가 무너진다 → D6를 **`14 ≤ composite < 20` + `any_crit=false` + `any_extreme=false`**(AMBER 승격 미충족 ∧ `exit_AMBER` 미충족)로 고정해 fold=AMBER / 단일틱=GREEN 대조를 성립시킨다 |

### 0.4 라운드 4 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **A-13** 프리뷰 적재의 확정 틱 오염 경로 무방비 | **§2.12 신설**(AD-A13) · MT1-03a 스키마·쿼리 · MT1-07a · AT-10 · R-20 | `observation.lane`(0=확정, 1=프리뷰) 신설 + Stage 1 쿼리에 **`AND lane = 0` 하드 필터** + `UNIQUE`에 `lane` 편입. "확정 우선 정렬"(B안)은 **확정 수집 실패일에 그대로 폴백**하므로 비채택 — 근거·비교는 §2.12(병합 결정 **M-43** 입력) |
| **공통 ①** 프리뷰 행 배제의 SQL 수준 명시 | **§2.12 쿼리 3종 + §2.11 Stage 1 계약** | 확정=`lane = 0` / 프리뷰 신선분=`lane IN (0,1)` / **carry-forward=`lane = 0`**(D-23 §23.3-1 "직전 **확정**값"). DAO는 원시 쿼리를 노출하지 않고 이 3개 이름만 노출 — 호출부가 WHERE를 빠뜨릴 수 없다 |
| **공통 ②** 프리뷰 경로의 `evaluated_at` 정의 + M-39 입장 | **§2.12 (c)** | **프리뷰 `evaluatedAt = 호출 시각(now, UTC)`**, `visibleAt`은 §2.8 그대로 일 단위 유지 → 나이 = `now − visibleAt` = **실경과**. 주논거 2개(정직한 as_of 표기 / 스냅하면 미가시 값을 보게 됨) + 보조 1개(`daily_kr 30h` 해상도) — **라운드 5에서 논거 3의 사실 오류 정정**(A-14) |

### 0.5 라운드 5 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **A-15** `previewSeries()` tie-break 역방향 | **§2.12 (b) 쿼리 ② 정정** · (b-0) 표 ② · 증인 **W-P5** | `lane DESC` → **`lane ASC`(확정 우선)**. 같은 `as_of`에서 종가는 장중 부분봉보다 **항상 우월**하며, "신선분"은 최신 *적재 시각*이 아니라 **최선의 관측**이다. 13:00 부분봉 → 17:00 종가 → 18:00 프리뷰 시나리오를 W-P5로 고정(`lane DESC`로 되돌리면 실패) |
| **A-14** M-39 논거 3의 사실 오귀속 | **§2.12 (c) 논거 3 재작성** · §2.8 동일 오류 정정 · U-15 | `48h·96h는 24h의 배수`이고 BT-03 스윕 선정값은 **`daily_us 48h`**이며 `daily_kr`은 **스윕 미대상**(indicators.yaml L240-241)임을 확인 → 라운드 4의 두 주장 **철회**, 살아남는 사실(`daily_kr 30h`만 비배수라 스냅 시 24h와 구별 불가)만 **보조 논거**로 축소. 결론은 논거 1·2로 유지. §2.8의 같은 오류("전부 정확히 걸릴 수 있다")도 함께 정정 |
| **공통(M-43b)** 프리뷰 경로 원장 접근 규율 전수표 | **§2.12 (b-0) 신설** | 읽기 지점 **3개 전수** × 판별자 값 × tie-break × 근거 × 증인을 한 표로. 쓰기 2지점·이월 미기록도 표 아래 1줄. 새 읽기 지점 추가 시 표 갱신이 선행이며 AT-10이 표 밖 조회를 금지 |

### 0.6 라운드 6 반려 사유 해소 대조표

| 반려 | 해소 위치 | 한 줄 요지 |
|---|---|---|
| **A-16** 읽기 지점 ③의 반환 계층 단절 | **§2.12 (b-0) 표 ③ 전환 + 쿼리 ③ 교체** · §2.10 스키마 승격 · MT1-07a · AT-10 · R-21 | **권고 (a) 채택** — carry-forward 원천을 `observation.lastConfirmed()`(원계열 값) → **`tick_input.lastCommittedSeverities()`(severity 맵)**로 전환. 반환이 곧 severity라 `compute_composite`에 직결(도출 경로 자체가 사라진다), `tick_input`은 확정 틱만 쓰므로 **레인 필터 불요**, D-23 §23.3-1 "직전 확정값" = 확정 틱이 **실제 커밋한 severity**를 문자 그대로 충족, 개정치 유입으로 커밋값과 갈릴 여지 소멸 |
| **A-16 (부수)** M-43b 표 "반환 계층" 열 + `severities_json` 지위 | **(b-0) 표 · §2.10 스키마 주석** | 표에 **반환 계층** 열 신설(①② = 원계열 관측값 → Stage 2·3 소비, ③ = **severity 맵** → `compute_composite` 직결). `severities_json`을 "fold 미입력·감사"에서 **"이월 원천(필수 컬럼)"으로 승격**, 결측 지표도 `null`로 명시 기록해야 "그때 결측이었다"가 재현됨을 명기 |
| **A-16 (M-50)** 0행·깊이 규정 | **증인 W-P6·W-P7** | `tick_input` **0행(설치 직후)** → carry-forward 미수행, 결측 유지(임의 대체 없음) → raw coverage 하락 시 억제 규율 작동. **이월 깊이 = 1**(마지막 평가 틱)로 고정하고, 그 틱에서 결측이던 지표는 프리뷰에서도 결측 — 지표별 walk-back은 SSOT에 없는 정책이므로 `ponytail:` 상향 경로로만 남김 |

---

## 1. 계획 전제 (GM0 이후 확정 사실의 반영 확인)

브리프 §2의 12개 확정 사실이 계획 어디에 반영됐는지 대조한다. 누락 시 반려 사유가 된다.

| 브리프 §2 | 반영 위치 |
|---|---|
| 1. registry **0.3.1-rc**를 assets에 굽는다 | AD-A3(생성 assets는 저장소 원본 그대로) · MT1-01c · §11 C-4(golden_mobile.yaml의 `registry_version: 0.1.0` 스탬프 노후 지적) |
| 2. D-26 짝지음 + `or_any_extreme`는 **엔진 의미론**, Kotlin 포팅·BT-05 범위 포함 | MT1-05d(§4.5) · AD-A6의 L2/L3 벡터에 `any_crit`·`any_extreme` 입력 포함 · §7 R-04 |
| 3. 확정 틱 시각 재확인 의무 | AD-A7 · §9 전용 논증 · §11 C-1(SSOT 이관 제안) |
| 4. G-4 (kr_cds) 실측 후 (a)/(b) 상신 | MT1-00d · MT1-04f · §10 U-4(권고 = (b)) |
| 5. KRX는 kotlin_krx 사용, **야후 ^KS11 폴백 비채택**(재제안 금지) | AD-A2 · MT1-04c. 본 계획은 KR 지수의 야후 폴백을 **일절 제안하지 않는다**. 글로벌 지표의 Stooq 폴백만 MT1-04a에 둔다 |
| 6. M1은 LLM 미호출, 프리뷰도 자동 호출 금지 | MT1-07 산출물에 LLM 경로 없음 · §4.8 노티 3채널에 리포트 트리거 없음 · prompts는 assets에 복사만(M2 대비) |
| 7. 뉴스 2지표 `enabled:false` 유지, kr_cds `optional:true` | MT1-05a 로더가 `enabled` 필터를 engine_ref와 동일 의미로 구현(§4.5) |
| 8. D-23 커버리지 규율 = MT1-07 완료기준 4항 구속 | §4.7 전체 + §10 U-2(coverage 정의 모호성 해소 상신) |
| 9. contracts 스냅샷은 **Python 측도 신설** | MT1-02a(python-implementer) · MT1-02b |
| 10. REVIEW_M0 신설 규율 4건 | §12 위임 규율(증인 테스트 의무·형제 계열 증거·git status 첨부·qa 선행 대조) |
| 11. 모델 배정 D-20 §20.2 | §12 위임표 |
| 12. Windows·cp949·계측은 실기기 필요 | MT1-01a의 인코딩 규율(§3.2) · §4.1 `check`에서 계측 분리 · MT1-09 |

---

## 2. 아키텍처 결정 상세 (관점 A 심화)

### 2.1 모듈 경계 (AD-A1)

```
mobile/                                   ← Gradle 루트 (settings.gradle.kts 여기)
├── settings.gradle.kts                   rootProject.name = "branch-console-mobile"
├── build.gradle.kts                      플러그인 alias(apply false) + subprojects 공통 규율
├── gradle.properties                     JVM args·인코딩·AndroidX
├── gradle/libs.versions.toml             ★ 의존성 버전 SSOT (버전 카탈로그)
├── gradle/wrapper/gradle-wrapper.properties   distributionUrl + distributionSha256Sum
├── engine/                               ★ 순수 Kotlin/JVM — Android 의존 0
│   src/main/kotlin/com/branchconsole/
│       contracts/     evidence-pack/1 · scenario-snapshot/1 미러 (MT1-02)
│       engine/config/ RegistryLoader · TransformSpecParser · StaleWindows
│       engine/        transforms · scoring · modifiers · statemachine
│       engine/api/    ConfirmInputs · PreviewInputs · TickResult · PreviewResult
│   src/test/kotlin/   단위 + 파리티(L1·L2·L3) + 계약 스냅샷 왕복
├── krx/                                  ★ 순수 Kotlin/JVM — kotlin_krx 벤더링 (MT1-01d)
│   src/main/kotlin/com/krxkt/**          upstream 그대로 (패키지명 유지)
│   src/test/kotlin/com/krxkt/**          upstream 단위 테스트(MockWebServer)만, integration/ 제외
│   PROVENANCE.md + krx-manifest.sha256   출처 커밋 SHA + 파일별 해시
└── app/                                  ★ Android application
    src/main/kotlin/com/branchconsole/app/
        collect/       Collector 인터페이스 + 어댑터 6종 + Orchestrator
        lake/          Room DB · Entity · DAO(append-only) · AsOfQuery · Export
        tick/confirm/  확정 틱 Worker · 캐치업 · 실행 이력      ← preview 참조 금지(AD-A5)
        tick/preview/  프리뷰 파이프라인 · CarryForward          ← ConfirmInputs 생성 불가
        notify/  ui/  settings/  di/(수동 컨테이너)
    src/test/kotlin/                      JVM 단위 + Robolectric(Room·Worker)
    src/androidTest/kotlin/               계측 — assets 해시·엔진 스모크·1일 스모크 보조
```

**왜 3개인가 (경계마다 기술적 강제가 있다)**

1. `:engine`이 `:app`에서 분리되는 이유는 계층 미학이 아니라 **실행 가능성**이다. BT-05 파리티와
   골든 대조는 CI에서 디바이스 없이 반복 실행돼야 하는데(브리프 §2-12), Android 모듈의 단위 테스트는
   AGP 변형·리소스·Robolectric에 묶인다. 순수 JVM 모듈이면 `./gradlew :engine:test`가 수 초에 끝난다.
2. `:engine`이 Android SDK를 **참조할 수 없다는 사실 자체가 계약**이다. `android.util.Log`·`Context`·
   `SharedPreferences`가 엔진에 스며들 물리적 경로가 없어진다. 설정 로딩은 `ConfigSource`
   (`fun open(name: String): InputStream`) 추상화 하나로 받는다 — 앱은 `AssetManager`, 테스트는
   저장소 파일. 추상화는 이 **하나만** 만든다(인터페이스 1개, 구현 2개 — 실제 사용처가 둘이므로 §PRINCIPLES YAGNI 위반 아님).
3. `:krx`가 분리되는 이유는 **출처가 다르기 때문**이다. 외부에서 들여온 코드에 ktlint/detekt 기본 룰셋을
   그대로 걸면 대량 위반이 나고, 그 억제를 `:app`에 섞으면 우리 코드의 품질 신호가 오염된다.
   모듈이 분리돼 있으면 억제 범위가 정직하게 `:krx`로 한정되고 "여기는 수입품"이 빌드 파일에 남는다.
4. **더 쪼개지 않는 이유**: `:data`/`:domain`/`:ui` 분해는 M1 규모(단일 앱·단일 팀·단일 배포)에서
   빌드 그래프 복잡도만 늘린다. 계층 규율은 §2.6의 타입 분리 + 아키텍처 테스트가 더 싸고 강하게 강제한다.
   M2에서 UI가 커지면 그때 `:ui` 분리를 재검토한다(비용이 발생한 뒤에 지불).

**모듈 의존 방향** (순환 금지, Gradle이 강제):
`:app → :engine`, `:app → :krx`. `:engine`은 어디에도 의존하지 않는다. `:krx`도 마찬가지.
`:engine ↛ :krx` — 엔진은 수집을 모른다.

### 2.2 Gradle·버전 카탈로그·빌드 재현성 (AD-A9)

**버전 카탈로그가 유일한 버전 선언 지점**이다. 모듈 빌드 파일에 문자열 좌표(`"group:artifact:version"`)를
쓰는 것을 금지하고, 기계적으로 강제한다:

```kotlin
// mobile/build.gradle.kts — subprojects 공통
configurations.all {
    resolutionStrategy {
        failOnDynamicVersions()      // "1.+", "latest.release" 차단
        failOnChangingVersions()     // -SNAPSHOT 차단
    }
}
```

이 두 줄이 AAA §2.3 "의존성 버전 핀"의 실행 가능한 정의다. 추가로:

- **Gradle 배포 핀**: `gradle-wrapper.properties`에 `distributionSha256Sum` 명시 — 래퍼 변조·부분 다운로드 차단.
- **JDK 핀**: `kotlin { jvmToolchain(17) }` + `java.toolchain.languageVersion = JavaLanguageVersion.of(17)`.
  로컬 JDK 버전에 빌드가 흔들리지 않는다. 17을 고르는 이유는 kotlin_krx가 이미 17이고 AGP 8.x가 17을 요구하기 때문.
- **저장소 잠금**: `settings.gradle.kts`에 `repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` + `google()`·`mavenCentral()`만.
  `mavenLocal()` 금지(비재현 빌드의 대표 원인).
- **`gradle/verification-metadata.xml`(의존성 체크섬 검증)은 M1에서 비채택.** 신규 의존 추가마다
  메타데이터 갱신 비용이 붙고, AAA §2.3이 요구하는 것은 "버전 핀"이지 "아티팩트 서명 검증"이 아니다.
  M3 릴리스 준비에서 재검토 항목으로 이월한다(§10 U-8).
- **인코딩 규율 (Windows cp949 함정, 브리프 §2-12)**:
  `gradle.properties`에 `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`,
  각 모듈에 `compileOptions { encoding = "UTF-8" }`, `tasks.withType<Test> { systemProperty("file.encoding", "UTF-8") }`.
  한글 KDoc·테스트 메시지가 cp949로 깨지면 aaa-critic이 산출물을 읽지 못한다 — 품질 사고가 아니라 절차 사고가 된다.

**`./gradlew check`가 포함하는 것 (브리프 §5-1의 답)**

| 포함 | 태스크 | 디바이스 |
|---|---|---|
| 정적분석 | `ktlintCheck`, `detekt` (전 모듈) | 불요 |
| 엔진·계약·파리티 | `:engine:test` (L1·L2·L3 파리티, 계약 스냅샷 왕복, 아키텍처 테스트 일부) | 불요 |
| KRX | `:krx:test` (MockWebServer 단위, `integration` 태그 제외) | 불요 |
| 앱 단위 | `:app:testDebugUnitTest` (Robolectric 포함: Room·Worker) | 불요 |
| assets 동기화 | `:app:syncConfigs` (preBuild 의존) + `ConfigsManifestJvmTest` | 불요 |
| 수동 사본 금지 | `:app:verifyNoCheckedInAssets` (`src/main/assets/configs` 부재 단언) | 불요 |
| krx 출처 | `:krx:verifyKrxProvenance` (매니페스트 해시 대조) | 불요 |
| 커버리지 | `:engine:jacocoTestCoverageVerification`(모듈 ≥90%), `:app:jacocoTestCoverageVerification`(`app.lake.**` ≥90% + 전체 ≥70%) — 규칙·제외 목록은 **§2.9** | 불요 |
| Android lint | `:app:lintDebug` | 불요 |
| **미포함** | `connectedDebugAndroidTest` (계측) | **필요** |

release 변형 단위 테스트는 비활성해 실행 시간을 절반으로 줄인다
(`androidComponents { beforeVariants(selector().withBuildType("release")) { it.enableUnitTest = false } }`).
계측 항목은 `./gradlew connectedDebugAndroidTest`로 **명시 분리**하고, GM1 체크리스트(§14)에서 별도 줄로 관리한다.

### 2.3 kotlin_krx 통합 (AD-A2 — 브리프 §5-2의 답)

**실측한 사실** (`D:\android_2025\kotlin_krx`, 읽기 전용):

- 순수 **Kotlin/JVM 라이브러리**다(`kotlin("jvm") 2.1.0` + `java-library`, JDK 17). Android 의존 0.
  → Android 모듈로 그대로 소비 가능. minSdk 29에서 `java.time`·OkHttp 4.12(minSdk 21) 모두 적합.
- 의존: OkHttp 4.12.0, **Gson 2.10.1**, kotlinx-coroutines 1.7.3, kotlinx-datetime 0.5.0.
- 원격 저장소 **존재**: `https://github.com/gmdjlee/kotlin_krx.git` (origin). 로컬 HEAD `6cc8180`.
  로컬 작업 트리에 미추적 파일 5건(문서·테스트) — 서브모듈 채택 시 push 상태 확인이 선행이다(MT1-00e).
- `KrxClient.login(id, pw)` **존재** — KRX 2026 로그인 정책 대응 코드가 이미 있다.
  세션 만료 시 `LOGOUT` 응답 → `AuthenticationError`. Referer 전략 이중화(outerLoader / mdiLoader).
- `KrxIndex.getVkospi(start, end)` **존재** (MDCSTAT01201, `indTpCd=1`, `idxIndCd=300`).
  → **K-02의 서버 측 결론(realized_vol 폴백)이 모바일에서는 성립하지 않을 수 있다.** §10 U-3의 근원.
- `KrxIndex.getBusinessDays(start, end)` 존재 → 휴장일 판정에 쓸 수 있다(K-03·K-06).
- `KrxStock.getMarketTradingByInvestor(...)` 존재 → `foreign_net_sell_kospi` 경로.

**통합 방식 비교**

| 방식 | 재현성 | 버전 정렬 | 적응 자유도 | 판정 |
|---|---|---|---|---|
| (i) 절대경로 `includeBuild("D:/android_2025/kotlin_krx")` | **불가** — 저장소 밖 디렉토리에 빌드가 의존. 다른 머신·CI에서 clone만으로 빌드 실패 | — | — | **탈락** (AAA §2.3 위반) |
| (ii) `mavenLocal()` + `1.0.0-SNAPSHOT` | **불가** — SNAPSHOT + 로컬 저장소는 비재현 빌드의 정의 | — | — | **탈락** |
| (iii) git submodule + `includeBuild` (컴포지트) | 가능(커밋 SHA 핀) | 위험 — 포함 빌드가 자체 Kotlin 플러그인 버전을 들고 온다. 앱과 다른 Kotlin 버전이 stdlib 중복·경고를 만들 수 있다 | 낮음 — 업스트림 파일을 고칠 수 없다(자격증명 주입·rate limit 삽입 불가) | 차선 (U-1) |
| (iv) **벤더링(소스 복사) + 출처 매니페스트** | **높음** — 저장소 하나만 clone하면 끝 | 완전 — 카탈로그 하나가 Kotlin/OkHttp/coroutines를 정렬 | 높음 — Android 적응 가능 | **채택** |

**채택 근거 3개**

1. **재현성이 최우선 제약이다.** GM1은 "다른 세션·다른 머신에서 `./gradlew check`가 green"임을 요구한다.
   (i)·(ii)는 이 조건을 원천적으로 못 만족한다.
2. **적응이 필요하다.** 그대로 쓸 수 없는 지점이 최소 3곳이다: ① 자격증명이 `local.properties` 경유
   (Gradle 프로퍼티) — 앱에서는 Keystore/EncryptedSharedPreferences에서 주입해야 한다(K-17)
   ② rate limit·휴장 스킵(K-03)은 호출자 책임으로 남아 있다 — 어댑터 계층에서 감싼다
   ③ `integration` 태그 테스트 20여 개는 실네트워크 의존 — 복사 대상에서 제외해야 `:krx:test`가 CI에서 돈다.
3. **업스트림 변경 압력이 낮다.** `PROGRESS.md` = "ALL PHASES COMPLETE", `TASK.md` Backlog = (none).
   포크 드리프트 비용이 실질적으로 발생하지 않는 상태다.

**벤더링 실행 규율** (드리프트를 관리 가능하게 만드는 최소 장치)

- 복사 범위: `src/main/kotlin/**` 전체 + `src/test/kotlin/**`에서 `integration/` 하위 **제외**.
  **미사용 API도 삭제하지 않는다** — 재수입 diff를 단순하게 유지하는 편이 코드 몇 KB보다 싸다. 미사용 코드는 R8이 제거한다.
- `mobile/krx/PROVENANCE.md`: upstream URL · 복사 시점 커밋 SHA(`6cc8180` 또는 MT1-00e 확인 후 최신) ·
  제외 목록 · 우리가 가한 변경 목록(변경했다면 파일별로 사유 1줄).
- `mobile/krx/krx-manifest.sha256`: 복사한 파일별 SHA-256. `verifyKrxProvenance` 태스크가 `check`에서 대조 →
  **"우리가 몰래 수정했는데 PROVENANCE에 안 적은" 상태를 빌드가 잡는다.**
- 재수입 절차서 1쪽(`mobile/krx/REIMPORT.md`): upstream fetch → diff → 매니페스트 갱신 → `:krx:test` green.

**KRX 로그인 정책 대응 확인 절차** (브리프 §5-2 후단, MT1-00c):
`data-verifier`가 실제 kotlin_krx 저장소에서 통합 테스트를 실행해 4가지를 확정한다 —
(a) 무로그인 outerLoader 경로로 지수 OHLCV(MDCSTAT00301)·투자자별 거래(MDCSTAT02203)가 되는가
(b) 로그인 필요 시 `login(id,pw)`가 현행 정책에서 성공하는가 (c) VKOSPI(MDCSTAT01201, mdiLoader+세션)가 되는가
(d) 세션 만료 주기·재로그인 비용. 산출은 §11 C-2(sources.yaml 실측 기록 제안).

### 2.4 syncConfigs 아키텍처 (AD-A3·AD-A4 — 브리프 §5-3의 답)

**복사 대상**: `configs/*.yaml` 5종(`analogue_seed`·`indicators`·`news_topics`·`sources`·`statemachine`)
+ `prompts/*.md` 2종(`daily_digest`·`scenario_report`). M1이 런타임에 쓰는 것은 `indicators`·`statemachine`뿐이지만,
디렉토리 통째 동기화가 선택 복사보다 싸고 M2를 미리 만족시킨다.

**핵심 설계 — 드리프트를 테스트가 아니라 구조로 막는다**

```kotlin
// mobile/app/build.gradle.kts (요지)
val repoRoot = rootProject.layout.projectDirectory.dir("..")     // mobile/ 의 부모 = 저장소 루트
val ssotAssets = layout.buildDirectory.dir("generated/ssot-assets")

val syncConfigs by tasks.registering {
    inputs.dir(repoRoot.dir("configs")).withPathSensitivity(RELATIVE)
    inputs.dir(repoRoot.dir("prompts")).withPathSensitivity(RELATIVE)
    outputs.dir(ssotAssets)
    doLast {
        // configs/*.yaml, prompts/*.md 복사 + ssot.sha256 매니페스트 생성
        // 매니페스트 형식: "<sha256>  configs/indicators.yaml" 한 줄씩, 경로 오름차순
    }
}
android.sourceSets["main"].assets.srcDir(ssotAssets)
tasks.named("preBuild") { dependsOn(syncConfigs) }
```

- 복사본은 `build/` 안에 있고 `.gitignore` 대상이다. **편집 가능한 사본이 저장소에 존재하지 않으므로
  K-16 드리프트가 물리적으로 발생하지 않는다.** 기존 설계("src/main/assets로 복사하고 해시로 검증")보다
  한 단계 강한 보장이며, 검증 테스트는 남겨 이중화한다.
- `verifyNoCheckedInAssets`: `mobile/app/src/main/assets/configs`·`.../prompts` 경로가 존재하면 실패.
  누군가 "편해서" 수동 사본을 되살리는 회귀를 빌드가 잡는다.

**해시 검증 테스트의 소스셋 (브리프 §5-3의 핵심 질문 — 답: 둘 다, 역할이 다르다)**

| 층 | 소스셋 | 무엇을 증명하는가 | 왜 다른 층이 대체 못 하는가 |
|---|---|---|---|
| **L-A** `ConfigsManifestJvmTest` | `:app` **단위(JVM)** | 저장소 `configs/`·`prompts/` 원본 파일의 SHA-256 = 생성 매니페스트의 값. 파일 **집합**도 일치(신규 config 추가 후 sync 누락 검출) | 계측 테스트는 디바이스가 필요해 CI 기본 경로에 못 넣는다 |
| **L-B** `ConfigsAssetsInstrumentedTest` | `:app` **androidTest(계측)** | `AssetManager`로 실제 APK에서 읽은 바이트의 SHA-256 = 매니페스트 값 | JVM 테스트는 **패키징 경로**(aapt·번들·압축)를 지나지 않는다. 스테일 빌드 캐시·머지 룰 사고를 잡는 유일한 층 |

L-A는 `systemProperty("ssot.repoRoot", ...)`·`systemProperty("ssot.assetsDir", ...)`를 Gradle이 주입한다
(작업 디렉토리 가정 금지 — AGP 변경에 취약).
D-18·TASK MT1-01의 "계측 테스트가 SHA-256 일치 검증"은 L-B가 문자 그대로 만족시키고, L-A는 CI 실행 가능성을 보탠다.

### 2.5 SSOT 로딩 경계 — YAML 파서 선택

engine_ref는 `yaml.safe_load` → `dict` → dataclass다. Kotlin도 **같은 모양**(파싱 → Map → 타입 접근자)을 취해야
파리티 논증이 단순해진다.

| 후보 | 판정 |
|---|---|
| `org.snakeyaml:snakeyaml-engine` (YAML 1.2, `java.beans` 미사용) | **채택 권고**. Map/List로 로드 → engine_ref와 동형. Android 호환성은 MT1-00e에서 계측 스모크로 실증 |
| `org.yaml:snakeyaml` (1.x/2.x) | 비권고 — JavaBeans 경로가 Android `java.beans` 부재와 충돌한 이력이 있다. Map 전용 사용이면 동작하나 위험을 남길 이유가 없다 |
| `com.charleskorn.kaml` | 비채택 — kotlinx.serialization 버전과 강결합. `thresholds`가 평평/중첩 두 형태인 이질 스키마라 타입 직결의 이득이 작다 |
| 빌드 타임 YAML→JSON 변환 후 kotlinx.serialization | **비채택**. assets가 저장소 원본과 바이트 동일해야 K-16 해시 규율이 성립한다. 변환본을 함께 실으면 사본이 둘이 되어 AD-A3를 무너뜨린다 |

`snakeyaml-engine`의 Android 실동작 확인은 **MT1-01c의 계측 스모크 1건**(assets에서 `indicators.yaml` 로드 →
지표 15종 파싱 성공)으로 GM1 전에 증명한다.

### 2.6 계층 분리와 아키텍처 테스트 (AD-A5 — 브리프 §5-11의 답)

**타입으로 강제한다 (1차 방어).**

```kotlin
// :engine — com.branchconsole.engine.api
class ConfirmInputs internal constructor(val rows: List<ResolvedIndicator>)  // 확정 경로 전용
class PreviewInputs internal constructor(val rows: List<ResolvedIndicator>)  // 프리뷰 전용

fun evaluateConfirm(inputs: ConfirmInputs, profile: ProfileParams, cfg: EngineConfig): ConfirmTickResult
fun evaluatePreview(inputs: PreviewInputs, cfg: EngineConfig): PreviewResult
```

- `ConfirmTickResult`는 `phase`·`prevPhase`를 갖고 상태기계를 통과한다.
  **`PreviewResult`에는 phase 필드가 아예 없다** — 프리뷰가 상태기계를 호출하지 않는다는 사실이
  타입에 새겨진다. TASK MT1-07 완료기준 ①("프리뷰가 상태기계 상태를 변경하지 않음")을
  런타임 단언이 아니라 **컴파일 시점**에 만족시킨다.
- carry-forward 함수의 반환 타입은 `PreviewInputs`뿐이다. `ConfirmInputs`의 생성자는 `internal`이며
  as-of 원장 조회 경로에서만 만들어진다. → **확정 틱이 이월값을 삼킬 타입 경로가 없다.**
  TASK 완료기준 ②("확정 틱 경로에서 carry-forward 호출 불가")의 1차 증명.
- `ResolvedIndicator`에는 `carriedForward: Boolean` 플래그를 둔다. 이는 방어가 아니라 **표시·감사용**
  (스테일 배지·커버리지 계산). 타입이 방어를 맡고 플래그는 정보를 맡는다 — 역할을 섞지 않는다.

**아키텍처 테스트로 증거화한다 (2차 방어, TASK가 명시 요구).** `Konsist`(JVM 테스트 라이브러리, 카탈로그 핀) 사용:

| 규칙 | 대상 | 근거 |
|---|---|---|
| AT-1 | `app.tick.confirm` 패키지의 어떤 파일도 `app.tick.preview`를 import하지 않는다 | TASK MT1-07 ②, D-23 §23.3-4 |
| AT-2 | `carryForward` 심볼 참조는 `app.tick.preview` 안에서만 존재한다 | 동상 |
| AT-3 | `:engine` 소스에 `android.` import 0건 | AD-A1 |
| AT-4 | `java.time.LocalDateTime`·`LocalDate.now()` import는 `app.ui` 밖에서 0건 (naive 금지, K-05) | AAA §2.1 |
| AT-5 | `Float` 타입 선언 0건(수치 경로) — Double 고정(K-07) | AAA §2.1 |
| AT-6 | `@Update`·`@Delete` 애노테이션 사용 0건 | TASK MT1-03, D-22 §22.1 |
| **AT-7** | `isStale`·as-of 리졸버 호출부에 `visibleAt` 이외의 시각을 넘기는 경로 0건(파라미터명 검사 + 리졸버 단일 진입점 강제) | §2.8, 반려 A-1 |
| **AT-8** | `observed_at`(`observedAt`) 참조는 `app.lake.export`·`app.ui`·`app.diagnostics`에서만 — **판정 경로(`tick.**`·`:engine`)에서 0건** | §2.8·MT1-03a, 반려 A-2 |
| **AT-9** | `tickInputDao.freeze` 참조는 `app.tick.confirm`에서만 — `app.tick.preview`·`app.ui`에서 0건 | §2.10, D-17(프리뷰 비커밋)의 원장 쓰기 차원 강제 |
| **AT-10** | `observation` 조회는 DAO 2메서드(`confirmSeries`/`previewSeries`) 밖에 존재하지 않고, 확정 경로(`app.tick.confirm`·`IndicatorRuntime`)에서 `previewSeries` 참조 0건. `tick_input` 읽기는 fold(확정)와 `lastCommittedSeverities()`(프리뷰) 2곳 | §2.12 (b-0) 전수표, 반려 A-13·A-16 |

Konsist를 새 의존성으로 들이는 판단: 손으로 소스 스캐너를 짜면 60줄 + 정규식 취약성이고, Konsist는
규칙당 3~5줄이다. AAA가 "아키텍처 테스트"를 완료기준으로 요구하는 이상 증거는 반드시 코드로 남아야 하므로,
더 적은 코드로 더 정확한 쪽을 택한다. (§10 U-9로 대안 검토 여지는 남긴다.)

**detekt로 SSOT 규율 보강**: `:engine`에 `MagicNumber` 룰을 강하게 적용해 임계·가중치 리터럴 유입을 차단한다.
허용 예외는 `0`·`1`·`-1`·`100.0`(D-02 공식 상수)·`3.0`(severity 상한, engine_ref와 동일 위치)만 명시 등록하고,
그 외 억제 주석은 사유 필수(AAA §2.3).

### 2.7 BT-05 파리티 아키텍처 (AD-A6 — 브리프 §5-8의 답)

**문제 정의.** BT-05는 "동일 픽스처 입력 → |Δcomposite| ≤ 0.05, 국면 타임라인 일치, golden_mobile 일치"를 요구한다.
그런데 `backtest/run_replay.py`는 **하니스 고유 의미론**(경험적 거래일 달력, 근사-PIT 가시성 규칙, 스테일 판정)을
품고 있다. Kotlin이 이를 통째로 재구현하면 배포물이 아닌 개발 도구를 이중 구현하게 되고(비용·드리프트),
반대로 파리티를 엔진 안쪽으로만 좁히면 "동일 픽스처 → 동일 산출"이라는 문언이 증명되지 않는다.

**해법: 파리티 경계를 데이터로 고정한다.** Python이 "하니스 정책"을 데이터로 내보내고, Kotlin은
**프로덕션 코드만으로** 그 데이터를 소비해 계산한다.

| 층 | 입력 | Kotlin이 실행하는 것 | 판정 |
|---|---|---|---|
| **L1 transform 벡터** (**Stage 2**) | `backtest/parity/transform_vectors.jsonl` — 실제 픽스처에서 뽑은 (함수, 파라미터, **전체 입력 배열**, 기대 출력 배열). 지뢰 6의 2계열 정렬 케이스 2종 포함 | `Transforms.kt` 전 함수 | 원소별 `abs(Δ) ≤ 1e-9`, **NaN 위치 완전 일치**(`min_periods` 경계가 여기서 잡힌다) |
| **L2 파이프라인 벡터** (**Stage 3 이후**) | `backtest/parity/pipeline_<window>_mobile.json` — 틱별 {지표별 **선택된 `row_date`**·값·`visible_at`·cadence, modifier 입력(hy_oas_level, KRW=X high/low/prev_close)} + 기대 {severity 맵, composite, coverage, distinct_axes, any_crit, any_extreme, phase, fired_axes} | `visibleAt` 재계산·동일성 단언 → 스테일 판정 → severity → modifier → composite → distinct_axes → **상태기계(D-25 §1~4 + D-26)** | 게이트: `\|Δcomposite\| ≤ 0.05` · 타임라인 완전 일치 · fired_axes 집합 일치 · **선택된 `row_date` 일치**(지뢰 7의 bisect 방향 오류를 여기서 잡는다). 리포트: 실제 `\|Δ\|` 최대값(목표 1e-9) |
| **L3 end-to-end 1창** (**Stage 1~3 전체**) | 원시 롱포맷 CSV(`series_id, field, as_of, value`, **패딩 550일 포함**) — 골든 양성 창 `w2024_carry_unwind` 한정. 가시성 맵은 **교차검증용으로만** 동봉(Kotlin이 직접 계산한다, §2.8) | Stage 1 조회 → Stage 2 transform → Stage 3 색인·lookup → 위 전 과정 | L2와 동일 기준. **"동일 픽스처 입력 → 동일 산출"의 문자적 증명** |
| **L0 골든 대조** | `backtest/golden_mobile.yaml`을 **복사 없이 직접 읽는다**(테스트 태스크에 `systemProperty("repoRoot")`) | 없음(비교만) | L2·L3 산출 = 동결 타임라인 (phase·composite·coverage·fired_axes) |

- **L1+L2가 수학적으로 L3를 함의**하지만, 함의를 논증으로 남기지 않고 **1창에 한해 실제로 돌린다.**
  비용은 롱포맷 CSV 파서 20줄 + 가시성 맵 소비뿐이고, 그 대가로 "경계를 옮겨 쉬운 것만 증명했다"는
  반론이 원천 차단된다(aaa-critic 대비).
- 벡터 생성기(`backtest/gen_parity_vectors.py`)는 **결정론**이어야 한다 — 재실행 시 바이트 동일.
  Python 테스트 `test_parity_vectors_are_reproducible`가 이를 강제한다. 벡터 파일은 저장소에 커밋한다(재현성).
- L0가 `golden_mobile.yaml`을 사본 없이 읽는 것은 AD-A3와 같은 원칙이다 — **사본이 없으면 드리프트가 없다.**
- **실행 위치는 JVM**(`:engine:test`). 근거: 엔진은 Android API를 쓰지 않으므로 계측이 추가로 증명하는 것이
  없고, 디바이스 의존만 늘어 CI 회귀가 불가능해진다. BACKTEST_PLAN §BT-05가 "또는 로컬 JVM 테스트 타깃"을
  이미 허용한다. 대신 **계측 스모크 1건**으로 "assets 경로로 로드한 configs로 계산해도 산출이 동일"함을
  확인해, 로딩 경로 차이가 결과를 바꾸지 않음을 증명한다(중복 최소).

**파리티의 알려진 지뢰 3개** (계획 단계에서 미리 못 박는다 — 구현 중 발견하면 늦다)

1. **rolling 표준편차의 자유도.** pandas `Series.rolling(w).std()`는 기본 **ddof=1(표본)**이다.
   Kotlin에서 모표준편차(ddof=0)를 쓰면 z-score 전 지표가 어긋난다. `zscore`·`realized_vol_kospi_20d`에
   ddof=1을 명시하고, L1 벡터에 **길이 짧은 창**(w=3~5)을 포함해 차이가 크게 드러나도록 설계한다.
2. **`min_periods=window` 규약.** 앞쪽 `window-1`개는 NaN이어야 한다. Kotlin이 부분 창으로 값을 채우면
   전이 시점이 앞당겨져 타임라인이 갈린다. L1 벡터가 이 경계를 반드시 포함한다.
3. **누적 순서.** `compute_composite`는 `num += w*s; den += w*3.0`을 **indicators.yaml 선언 순서**로 누적한다
   (Python dict는 삽입 순서 보존). Kotlin이 `HashMap`을 쓰면 순서가 달라져 부동소수 마지막 비트가 갈린다.
   → **`LinkedHashMap` 사용을 규율로 고정**하고, 아키텍처/단위 테스트로 "지표 순회 순서 = YAML 선언 순서"를 단언한다.
   `|Δ| ≤ 0.05` 게이트는 여유롭지만, 골든 대조는 `rel=1e-9` 수준을 목표로 하므로 여기서 무너진다.

### 2.8 `visible_at` — 프로덕션 산출 규칙 (AD-A10, 반려 A-1·A-2 해소)

**문제.** 스테일 판정의 정본은 `backtest/run_replay.py:352` `is_stale_check`이고, 그 기준 시각은
**`visible_at`(가시화 시각)**이다. docstring이 근거를 실측으로 명시한다 — 달력일 자정을 as_of로 쓰면
"kr_close(17:00 KST)에 막 가시화된 당일 값이 자정 대비 8시간 지남으로 오판되어 intraday_30m(90분)
임계를 즉시 넘긴다". 반면 `engine_ref/registry.py:317` `is_stale`의 파라미터명은 `as_of`다 —
**이름만 as_of이고 호출자가 무엇을 넣느냐가 의미를 정한다**(하니스는 visible_at을 넣는다).
따라서 Kotlin 엔진은 **`visibleAt`을 받는 함수**로 이식해야 하고, 그 값을 **앱이 스스로 산출**할 수 있어야 한다.
라운드 1 계획은 이 값을 파리티 벡터로 "주입받기"만 했다 — 프로덕션에 산출처가 없었다. 아래로 해소한다.

**결정: `visible_at`은 저장하지 않고 파생한다.**

```kotlin
// :engine — com.branchconsole.engine.pit.Visibility
enum class CalendarKind { US_MARKET, FRED, KRX, FX }

/** as_of(T)가 최초로 알려지는 확정 틱 시각(UTC). run_replay.raw_visibility_grid_day +
 *  visibility_tick_utc(profile=mobile_daily)의 이식. grid = KR 거래일 오름차순 목록. */
fun visibleAt(
    kind: CalendarKind, asOf: LocalDate, grid: TradingDayGrid,
    fredLagDays: Int, confirmTimeKst: LocalTime,
): Instant?
```

| 계열 종류 | 대상 series | 가시 그리드일 | 근거(정본) |
|---|---|---|---|
| `US_MARKET` | `^VIX`·`^VIX3M`·`^MOVE`·`^GSPC`·`DX-Y.NYB` | as_of **이후** 첫 KR 거래일 | `_first_grid_day_after` — 미국 마감은 KST 다음날 새벽 반영 |
| `FRED` | `BAMLH0A0HYM2`·`T10Y2Y` | `as_of + lag_days`(indicators.yaml `source.lag_days`) **이상** 첫 KR 거래일 | `_first_grid_day_on_or_after` + FRED T+1(K-05) |
| `KRX` · `FX` | KRX 계열 · `KRW=X` | as_of **이상** 첫 KR 거래일 | `_first_grid_day_on_or_after` — 일봉은 마감에야 확정 |
| 2계열 지표 | `vix_term_structure`(^VIX,^VIX3M) · `global_corr_break`(^GSPC,KOSPI) | 각 계열 값의 **최댓값**(worst-of-inputs) | `combined_visibility_utc` |

그리고 mobile_daily 프로파일에서 **가시 시각 = 그 그리드일의 확정 틱 시각(17:00 KST, §9·C-1)**이다
(`visibility_tick_utc`의 `profile == "mobile_daily"` 분기). 즉 `confirmTimeKst`가 SSOT에서 와야 하며,
이것이 §11 C-1(확정 시각의 SSOT 이관)이 **선택이 아니라 필수**인 두 번째 이유다.

**왜 저장하지 않는가 (파생의 정당성)**

1. `visible_at`은 `(kind, as_of, 거래일 그리드, lag, 확정 시각)`의 **순수 함수**다. 저장하면 그리드가
   정정될 때(임시 휴장 추가 등) 모든 과거 행을 백필해야 한다 — append-only 원장에서는 불가능하다.
2. 파생이면 **캐치업·프리뷰·리플레이·파리티가 같은 함수 하나**를 쓴다. 규칙이 두 벌 존재할 여지가 없다.
3. 저장 비용·마이그레이션·드리프트가 전부 사라진다. 계산 비용은 틱당 15지표 × 이진탐색 1회로 무시할 만하다.

**as-of 조회에서의 사용** — **라운드 4 정정(반려 N-1)**: `visibleAt`은 **원관측 행이 아니라 transform
출력 시계열의 각 행**에 붙는다(정본 `build_known_series`가 받는 인자는 `value_series` = transform 산출물이다).
따라서 조회는 §2.11의 3단계를 따른다: **Stage 1**이 원계열을 범위로 읽고, **Stage 2**가 전체 계열을
변환하고, **Stage 3**이 그 출력에 `visibleAt`을 부착해 `visibleAt <= evaluatedAt`인 마지막 행을 고른다
(`bisectRight` 등호 포함 — 지뢰 7). 그 다음 `isStale(visibleAt, evaluatedAt, profile, cadence)`로 무효 판정한다.
라운드 2~3에 적었던 "DB가 후보를 좁히고 리졸버가 최신 1행을 고른다"는 서술은 **Stage 3의 역할을 Stage 1에
잘못 놓은 것**이므로 §2.11로 대체한다.

**스테일 등호 규약** (파리티 지뢰 4 — 반려 A-10): 정본 두 곳이 일치한다 —
`engine_ref/registry.py:323` `(evaluated_at - as_of) > stale_window(...)`,
`run_replay.py:367` `(evaluated_at - visible_at) > stale_windows[...]`. 둘 다 **`>` (초과)**다.
따라서 **경과 시간이 창과 정확히 같으면 stale이 아니다(fresh)**. Kotlin이 `>=`로 구현하면 경계 틱에서
그 지표가 통째로 결측 처리되어 분모에서 빠지고 composite·`distinct_axes`가 어긋난다.
이 경계는 실제로 발생한다: 확정 틱은 매일 같은 시각이라 경과가 정확히 **24h의 배수**가 되고,
`daily_us 48h`·`fred_daily 96h`는 **24h의 배수이므로 정확 일치가 실측으로 일어난다**
(`daily_kr 30h`는 배수가 아니라 확정 틱에서는 정확 일치가 나오지 않지만, 프리뷰는 임의 시각이라 발생 가능).
→ **증인 W-V4**: 경과 = 창 정확히 일치 → **유효**, 창 + 1ms → **결측**. 두 방향을 한 테스트에서 단언한다.

**cadence 폴백 함정** (파리티 지뢰 5 — 여기서 같이 못 박는다): `mobile_daily`의 `stale_profiles`에는
`intraday_30m` 키가 **없다**. `engine_ref.registry.stale_window`는 "없으면 그 프로파일의 `daily_kr`을
대신 적용"한다(Advisor 지정 해석). `usdkrw_z`·`vkospi_z`·`kospi_drawdown`이 `cadence: intraday_30m`을
선언하므로, 모바일에서 이 3지표는 **30h(daily_kr) 창**을 받는다. Kotlin이 키 부재를 예외나 0으로 처리하면
이 3지표가 통째로 스테일 처리되어 composite가 붕괴한다. → MT1-05a의 명시 요구사항 + 전용 단위 테스트.

**증인 테스트 3종** (없으면 이 규칙은 검증되지 않은 주장이다):

| ID | 시나리오 | 기대 |
|---|---|---|
| W-V1 | `^VIX` as_of=T를 T일 17:00 틱에서 조회 | **보이지 않는다**(us_market은 T 이후 첫 거래일). 이 단언이 실패하도록 규칙을 `on_or_after`로 바꾸면 테스트가 잡아야 한다(퇴화 입력 증인) |
| W-V2 | FRED as_of=T, `lag_days=1`을 T+1 거래일 틱에서 조회 | 보인다. T일 틱에서는 안 보인다 |
| W-V3 | KRX as_of=T(휴장일 관측)를 조회 | 다음 거래일 17:00에 최초 가시(`on_or_after`가 미래가 아닌 현재 쪽으로만 당긴다) |
| **W-V4** | `evaluatedAt − visibleAt`이 스테일 창과 **정확히 일치** / 창 **+1ms** | 각각 **유효** / **결측**(등호는 fresh — 위 규약) |

**L2 파리티에 단언 1줄 추가**: Python이 내보낸 `visible_at`과 **Kotlin이 계산한 `visibleAt`이 동일**해야 한다.
이로써 "주입받기만 한다"는 라운드 1의 결함이 구조적으로 제거된다 — 벡터는 이제 크러치가 아니라 교차검증이다.

### 2.9 커버리지 측정·강제 배선 (공통 명시 요구 ③)

AAA §2.3: 코어 모듈(engine·statemachine·contracts·lake) **라인 커버리지 ≥ 90%**, 나머지 ≥ 70%.
"목표"로 적으면 지켜지지 않는다 — **빌드가 실패해야** 기준이다.

| 모듈 | 도구 | 규칙 | 명령 |
|---|---|---|---|
| `:engine` (engine·statemachine·contracts 전부 여기) | `jacoco` 플러그인 | 모듈 전체 `LINE ≥ 0.90` | `./gradlew :engine:jacocoTestCoverageVerification` |
| `:app` | AGP + `jacoco` (`testDebugUnitTest` exec 기반) | `com.branchconsole.app.lake.**` **≥ 0.90**, 그 외 전체 **≥ 0.70** (두 개의 `rule` 블록) | `./gradlew :app:jacocoTestCoverageVerification` |
| `:krx` | — | **면제**(수입 코드, upstream 테스트 유지가 기준). PROVENANCE에 면제 사유 기록 | `./gradlew :krx:test` |

`check`에 배선: `tasks.named("check") { dependsOn("jacocoTestCoverageVerification") }` (두 모듈 각각).

**제외 패턴은 계획서에 열거해 감사 가능하게 한다** — 생성 코드 제외는 정당하고 수기 코드 제외는 부정이다:
`**/*_Impl*`(Room 생성), `**/*_Factory*`, `**/BuildConfig.*`, `**/R.class`·`**/R$*.class`,
`**/*ComposableSingletons*`, `**/*$$serializer*`(kotlinx.serialization 생성).
**UI 패키지(`app.ui.**`)는 제외하지 않는다** — 70% 규칙 안에서 상태 홀더 테스트로 충족한다
(제외하면 §2.2 실패 경로 검증이 커버리지에서 사라진다).

`:app`의 커버리지는 Robolectric 단위 테스트에서 산출한다(계측 커버리지 병합 없음) — 계측은 GM1 시점
수동 실행이라 CI 임계로 쓸 수 없다. 계측 전용 코드가 70%를 끌어내리면 그 코드를 얇게 만드는 것이 답이다.

### 2.10 국면 지속 모델 — 동결 `tick_input` 전량 fold (AD-A11, 반려 A-9 해소)

**엔진 API 사실** (`engine_ref/statemachine.py:106-196`, 정본):

```python
def run(ticks: list[Tick], profile: ProfileParams, config: StatemachineConfig) -> list[str]:
    phase = config.initial_phase            # L114 — 항상 GREEN에서 시작
    ticks_in_phase = 1                      # L115
    promote_streaks = dict.fromkeys(levels, 0)   # L118
    demote_streak = 0; cooldown = 0         # L119-120
```
- **시작 국면·카운터를 주입할 파라미터가 없다.** 국면은 오직 `ticks` 리스트에서 파생된다.
- 따라서 매일 `run([오늘 틱])`을 부르면 **국면이 매일 GREEN에서 재시작**한다 — 라운드 2 계획은
  이 호출 모델을 규정하지 않아 이 결함이 잠재해 있었다.
- `Tick`의 필드는 정확히 4개: `composite: float|None`, `distinct_axes: int`, `any_crit: bool`, `any_extreme: bool`.

**선택지와 판정**

| 안 | 내용 | 판정 |
|---|---|---|
| **(I) 증분 상태 저장** | 5개 카운터(`phase`·`ticks_in_phase`·레벨별 `promote_streaks`·`demote_streak`·`cooldown`)를 매 틱 저장하고 이어받음 | **탈락.** `run()`에 초기 상태 주입·최종 상태 반환을 더하는 **엔진 API 변경**이 필요하다 — `engine_ref`는 골든·파리티의 계산 정본이라(D-18) 변경 자체가 회귀 위험이고, M1 착수 전에 D-25 부기·골든 재확인을 요구한다. 또한 저장된 카운터는 프로파일 파라미터가 바뀌면 **의미가 조용히 상해서** 감지되지 않는다 |
| **(F) 동결 `tick_input` 전량 fold** | 확정 틱마다 그날의 `Tick` 4필드를 동결 저장하고, 매 틱 **전 시퀀스를 `run()`에 통째로 넘겨** 타임라인을 재산출. 오늘 국면 = 반환 타임라인의 마지막 원소 | **채택.** 엔진 변경 0. `backtest/test_golden.py`·`run_replay`가 **이미 하는 바로 그 호출**이라 프로덕션과 파리티가 같은 코드 경로를 탄다. 상태가 없으므로 이어받기 버그의 표면 자체가 없다 |

**(a) 프로덕션이 국면을 날마다 어떻게 이어받는가** — **이어받지 않는다. 재산출한다.**
연속성은 저장된 카운터가 아니라 **입력 시퀀스의 불변성**에서 나온다.
```kotlin
// 확정 틱 (MT1-06a)
val frozen: List<Tick> = tickInputDao.allOrderedByDate() + todayTick   // 동결 시퀀스 + 오늘
val timeline: List<String> = StateMachine.run(frozen, profile, config)
val phaseAfter  = timeline.last()
val phaseBefore = timeline.getOrElse(timeline.size - 2) { config.initialPhase }
tickInputDao.freeze(todayTick)          // append-only, PK=trading_date
tickRunDao.record(phaseBefore, phaseAfter, ...)   // 파생 감사값
```
- **`tick_run.phase_after`는 정본이 아니라 파생 캐시**다. 정본은 `tick_input` 시퀀스 하나뿐이다.
- **멱등의 진짜 근거**: fold는 동결 입력의 순수 함수다. 같은 원장에서 몇 번을 다시 돌려도 **비트 동일한
  타임라인**이 나온다. 라운드 2의 "PK 충돌 차단"은 이중 커밋만 막았을 뿐 결과 동일성은 증명하지 못했다.
- **성능**: 252틱/년, 10년이면 2,520틱 — fold 1회는 마이크로초 단위다.
  `ponytail: 전량 fold(O(n)); n이 수만 틱이 되어 확정 틱 지연이 체감되면 그때 스냅샷+재개로 올린다 —
  단 그 업그레이드는 엔진 API 변경(안 I)을 수반하므로 골든 재확인이 딸려온다.`
- **초기 부트스트랩은 하지 않는다.** 첫 확정 틱이 시퀀스의 1번째이며 국면은 GREEN에서 출발한다
  (골든·하니스의 창 시작 규약과 동일). 안전성 논증: `mobile_daily.promote_sustain_ticks = 1` +
  `skip_levels = true`이므로 **승격은 1틱에 수렴**하고(설치 당일 RED 조건이면 그날 RED로 직행),
  강등만 `demote_below 3`틱 지연된다 — 즉 초기 오차는 **항상 안전한 방향(낮게 시작)**이다.
  과거 원장을 fold해 부트스트랩하는 것은 §10 U-13으로 상신(권고: M1 비채택).

**(b) 근거 데이터를 어느 테이블 어느 컬럼에 보존하는가** — 신설 `tick_input`(MT1-03c):

```
tick_input                              -- 상태기계 입력의 유일한 정본. append-only, 동결 후 불변
  trading_date    TEXT PRIMARY KEY      -- 하루 1행 (이중 커밋 물리 차단)
  composite       REAL NULL             -- ★ NULL = 평가 불능(D-25 §3). Tick.composite
  distinct_axes   INTEGER NOT NULL      -- ★ Tick.distinct_axes
  any_crit        INTEGER NOT NULL      -- ★ Tick.any_crit   (0/1)
  any_extreme     INTEGER NOT NULL      -- ★ Tick.any_extreme(0/1) — D-26·or_any_extreme 필수
  coverage        REAL NOT NULL         -- 감사·표시(Tick 아님)
  severities_json TEXT NOT NULL         -- ★ 지표 id → severity(Int|null) 전량. fold 입력은 아니지만
                                        --   **carry-forward의 이월 원천**(§2.12 ③, 라운드6 승격) +
                                        --   감사·UI 상위지표. 결측 지표도 null로 명시 기록해야
                                        --   "그때 결측이었다"가 프리뷰에서 재현된다
  registry_version TEXT NOT NULL        -- 어느 레지스트리로 산출됐나 (예: 0.3.1-rc)
  frozen_at       INTEGER NOT NULL      -- UTC epoch millis
  gap_reason      TEXT NULL             -- composite IS NULL인 사유(UNRECONSTRUCTABLE_GAP 등)
  -- observation과 동일한 UPDATE/DELETE 차단 트리거
```
★ 4개가 `Tick`의 전 필드다 — **전량 fold의 재구성 가능성이 스키마로 보장된다.**
`severities_json`은 라운드 6에서 **"fold 미입력·감사"에서 "carry-forward 이월 원천(필수 컬럼)"으로 승격**됐다
(§2.12 ③, 반려 A-16) — 누락되면 프리뷰의 D-23 §23.3-1 규율이 성립하지 않는다.
라운드 2의 `tick_run`은 `distinct_axes`·`any_crit`·`any_extreme`가 없어 재구성이 불가능했다(반려 A-9 지적).
`tick_run`은 **실행 메타데이터**(status·타이밍·오류·재시도)로 역할이 분리되며 남는다 — 실패 재시도가 있는
`tick_run`과 성공 시에만 1행이 생기는 `tick_input`은 수명이 다르므로 한 테이블로 합치지 않는다.

**동결의 의미(중요)**: `tick_input`은 커밋 시점에 **불변**이다. 이후 원장에 정정(revision)이 들어와도
과거 틱을 다시 쓰지 않는다. 근거 — 경보 시스템에서 "어제의 ORANGE가 오늘 조용히 GREEN이 되는 것"은
감사 불가능한 거동이다. 정정은 **미래 틱에만** 반영된다.

**프로파일 파라미터 변경의 귀결(정직하게 명시)**: `promote_sustain`·`demote_below`·`min_dwell`·
`reentry_cooldown`이 바뀌면 fold가 **과거 타임라인을 재산출**한다. 이는 안 (I)이 카운터를 조용히
이어받아 구·신 파라미터를 섞는 것보다 **낫다**(일관성 보장). 다만 사용자에게는 국면 이력이 바뀐 것처럼
보이므로, `registry_version` 변화가 감지되면 실행 이력에 "레지스트리 변경으로 타임라인 재산출" 항목을 남긴다.

**(c) 캐치업 절단 시 카운터 처리 — 엔진 변경 없이 표현 가능한가: 가능하다.**
라운드 2의 "직전 확정 국면 + 오늘 1틱만 계산"은 현행 API로 **표현 불가**이므로 **철회**한다.
대체 설계는 이미 엔진에 있는 의미론을 쓴다 — **D-25 §3**:

> 전 지표 결측(`Tick.composite is None`)은 평가 불능이다. 국면·모든 스트릭·dwell 카운터·cooldown을
> 그 틱에서 완전히 **동결**한다(전이 없음, 틱 미소비) — GREEN으로 떨어뜨리지 않는다.
> (`statemachine.py:124-127` — `timeline.append(phase); continue`)

즉 **재구성 불가능한 공백 거래일은 `composite = NULL` 틱으로 동결**하면, fold가 그 구간을 통과할 때
국면과 모든 카운터가 그대로 유지된 채 오늘 틱으로 이어진다. 정확히 원하던 의미이고, **엔진 변경 0**이다.

| 캐치업 상황 | 처리 | 표현 |
|---|---|---|
| 공백 ≤ 상한, 재수집 성공 | 누락 거래일별 `Tick` 정상 산출·동결 | 정상 fold |
| 공백 ≤ 상한이나 특정일 전 지표 결측 | 그 날 `composite = NULL` 동결 | D-25 §3 동결 |
| 공백 > 상한(재구성 포기) | 해당 거래일들을 `composite = NULL`, `gap_reason = UNRECONSTRUCTABLE_GAP`으로 동결 + UI "원장 공백" 배지 | D-25 §3 동결 — 국면이 공백 이전 값에서 그대로 이어진다 |

→ **엔진 변경 제안은 필요 없다.** (필요했다면 §11에 정식 상신했을 것이다. 유일하게 엔진 변경을 부르는 것은
위 `ponytail:` 주석의 성능 업그레이드 경로뿐이며, M1 규모에서는 발생하지 않는다.)

**아키텍처 강제 (AT-9)**: `tick_input`에 쓰는 코드 경로는 **확정 틱 하나뿐**이다 —
`app.tick.preview`·`app.ui`에서 `tickInputDao.freeze` 참조 0건. D-17 "프리뷰는 국면을 커밋하지 않는다"가
타입(§2.6)에 이어 **원장 쓰기 권한**으로도 이중 강제된다.

**증인 테스트 4종** (MT1-06a):

| ID | 시나리오 | 기대 |
|---|---|---|
| W-S1 | D1~D5에 AMBER 유지 조건 틱 동결 → D6 확정 틱 실행 | `phase_after = AMBER`. **fold를 `run([오늘틱])`으로 바꾸면 GREEN이 나와 실패**(A-9 회귀 감시) |
| W-S2 | 같은 원장으로 확정 틱을 2회 실행 | 타임라인 **비트 동일**, `tick_input` 행 수 불변 |
| W-S3 | 공백 3일을 `composite=NULL`로 동결 후 오늘 틱 | 공백 이전 국면·스트릭이 보존된 채 이어짐(GREEN 강등 없음) |
| W-S4 | `any_extreme=true`가 지속되는 시퀀스에서 `exit_ORANGE` 조건 충족 | D-26 짝지음으로 **이탈 차단**(프로덕션 fold가 D-26을 실제로 태우는지 확인) |

**W-S1의 입력 조건 고정** (반려 A-12 — 조건을 안 박으면 대조가 무너진다):
`mobile_daily.promote_sustain_ticks = 1`이므로 D6 틱 자체가 AMBER 승격 규칙
(`composite_gte: 20` **또는** `or_any_crit`)을 충족하면 `run([D6])`도 AMBER를 내어 fold와 구분되지 않는다.
따라서 D6를 **승격 미충족 ∧ 이탈 미충족** 구간으로 고정한다:

| 틱 | 조건 | 의도 |
|---|---|---|
| D1 | `composite = 22.0`, `distinct_axes = 1`, `any_crit = false`, `any_extreme = false` | `composite_gte 20` 충족 → sustain 1틱으로 **AMBER 진입** |
| D2~D5 | `composite = 16.0` (동일 플래그) | `20` 미만이라 승격 없음, `exit_AMBER(composite_lt 14)` 미충족이라 강등 스트릭 0 → AMBER 유지 |
| **D6** | **`composite = 16.0`, `any_crit = false`, `any_extreme = false`** (**`14 ≤ composite < 20`**) | **fold → AMBER** / **`run([D6])` → GREEN**(초기 GREEN에서 승격 조건 미충족) — 대조 성립 |

`any_crit = false`가 필수다(`or_any_crit`가 참이면 composite와 무관하게 AMBER 승격이 성립한다).
`min_dwell_ticks = 5`·`demote_below_ticks = 3`이지만 D6에서 이탈 조건 자체가 거짓이라 강등 경로는 열리지 않는다.
**단언 2개를 한 테스트에 둔다**: `fold(D1..D6).last() == "AMBER"` **그리고** `run(listOf(D6)).last() == "GREEN"` —
두 번째가 "이 증인이 실제로 구분력을 갖는다"는 증거다(REVIEW_M0 퇴화 입력 증인 규율).

### 2.11 지표 런타임 파이프라인 — 원계열 조회 → transform → 가시성 lookup (AD-A12, 반려 N-1 해소)

**정본 순서** (`backtest/run_replay.py` — `_build_*` 빌더 → `build_known_series`:289 → `lookup_known`:320):

```
Stage 1  series_values(df, series_id, field)          # 원계열 전체(패딩 포함), as_of 오름차순
Stage 2  transforms.zscore(close, window=252) 등      # ★ 전체 계열에 1회. 부분 계열 아님
Stage 3  build_known_series(출력시계열, input_ids)    # ★ transform 출력의 각 row_date에 visible_at 부착
         lookup_known(ks, evaluated_at)               #   bisect_right(visibility_ts, evaluated_at) - 1
```

**라운드 2~3 설계의 결함(자인)**: §2.8·MT1-03a의 as-of 리졸버는 "계열별 **최신 1행**"을 골라 넘겼다.
그런데 활성 15지표의 transform 요구는 다음과 같다 —

| 요구 창 | 지표 |
|---|---|
| `zscore(window=252)` | `vix_level_z`·`move_index_z`·`dxy_z`(위에 `pct_change_5d`)·`usdkrw_z`(위에 `pct_change_1d`)·`vkospi_z`·`foreign_net_sell_kospi`(위에 `rolling_sum(5)`) |
| `drawdown_from_high(window=60)` | `kospi_drawdown` · `spx_drawdown_momentum`(+`neg_zscore(pct_change_5d, 252)`) |
| `rolling_corr(20)` − `rolling_mean_corr(120)` | `global_corr_break` |
| `zscore(window=60)` + gate | `kospi_volume_distribution` |
| `delta_bp(lookback=5)` | `hy_oas_delta`·`krx_credit_spread_delta`·`kr_cds_5y_delta`·`ust_2s10s_move` |

전부 `rolling(window, min_periods=window)`다 — **1행으로는 NaN이 나온다.** 그리고 가시성을 Stage 1로
앞당겨 "가시화된 행만" 변환하면 창이 절단돼 **다른 값**이 나온다(BT-05 L1/L2 확정 실패).
→ 아래 계약으로 대체한다. **정본을 그대로 옮길 뿐 새 발명은 없다.**

#### Stage 1 — 원계열 조회 계약 (`:app` → `:engine`이 소비)

```kotlin
// :engine — 인터페이스만 engine에, 구현은 :app(Room)
interface RawSeriesSource {
    /** (as_of, value) 오름차순. as_of당 1행(revision 최댓값). 가시성 필터 없음. */
    fun series(seriesId: String, field: String, from: LocalDate, to: LocalDate): List<Pair<LocalDate, Double>>
}
```
```sql
-- MT1-03a: Stage 1 = DAO confirmSeries() (라운드 2의 "최신 1행 LIMIT 1"을 대체)
SELECT series_id, field, as_of, value, MAX(revision) AS revision
  FROM observation
 WHERE series_id = :s AND field = :f
   AND lane = 0                      -- ★ §2.12 프리뷰 적재 행 하드 배제 (D-17 §3)
   AND as_of BETWEEN :from AND :to
 GROUP BY as_of                      -- as_of당 최신 revision 1행으로 축약
 ORDER BY as_of ASC
```
`observation` DAO는 이 `confirmSeries()`와 §2.12 (b)의 `previewSeries()` **2개만** 노출하고 원시 관측 조회를
제공하지 않는다 — 호출부가 `lane` 조건을 빠뜨릴 경로 자체가 없다(AT-10). carry-forward 원천(③)은
`observation`이 아니라 `tick_input.lastCommittedSeverities()`다(§2.12 (b-0)).

| 계약 항목 | 규정 |
|---|---|
| **레인** | **`lane = 0`(확정)만.** 프리뷰 적재 행(`lane = 1`)은 SQL 수준에서 배제된다 — §2.12, D-17 §3 |
| 범위 `to` | 평가 거래일 D (그 이후 as_of는 어차피 Stage 3에서 배제되나 읽지 않아 비용 절감) |
| 범위 `from` | **D − `warmup_calendar_days`**. 값은 하니스 `backtest/windows.yaml` `padding_days: 550`과 **동일해야** 파리티가 성립한다 → 코드 리터럴 금지, §11 **C-11**로 SSOT 신설 |
| as_of당 행 수 | 1행(revision 최댓값). 이것이 라운드 2 증인 **W-L4**의 위치다 |
| 결측 as_of | **행 없음**으로 표현(빈칸을 만들지 않는다). rolling은 **행 순서** 기준이므로 정본과 행 집합이 같아야 한다 |
| 가시성 필터 | **적용 금지**(Stage 3의 일). 앞당기면 창이 절단된다 |
| look-ahead 안전성 | `transforms`의 rolling 계열은 **인과적**(과거만 참조)이므로 미래 행이 섞여도 시점 T의 출력은 불변이다(정본 `test_prefix_stability_no_lookahead`가 보증). 방어는 Stage 3의 `visible_at ≤ evaluatedAt`이 담당한다 — **두 방어를 바꿔 달면 안 된다** |

**워밍업 550일의 충분성**은 주장하지 않고 **테스트로 강제**한다: 레지스트리에서 파싱한 최대 요구
거래일(중첩 포함 — 예 `zscore(252)` on `pct_change_5d` = 257, `rolling_mean_corr(120)` on `rolling_corr(20)` = 140)을
거래일→달력일로 환산(×1.45)한 값이 `warmup_calendar_days` 이하임을 단언한다. 레지스트리가 커지면
이 테스트가 먼저 깨져 알려준다.
`ponytail: 워밍업을 transform 파라미터에서 자동 파생하는 안은 비채택 — 중첩 깊이 계산이 파서에 얹혀
복잡도가 오르고, SSOT 키 하나가 더 싸고 감사 가능하다. 레지스트리 확장이 잦아지면 그때 파생으로 올린다.`

#### Stage 2 — transform (전체 계열, 1회)

`:engine`의 `Transforms.*`를 **Stage 1이 반환한 전 구간**에 적용한다. 파라미터는 registry 파서에서 온다(05a).
지표별 조립은 정본 `_build_*`와 1:1 대응한다(예: `dxy_z` = `zscore(pct_change_5d(close), 252, absolute=true)`).

#### Stage 3 — 가시성 색인 + lookup (`pit/KnownSeries.kt`, 05a2에 포함)

```kotlin
class KnownSeries(rowDates: List<LocalDate>, visibilityTs: List<Instant>, values: List<Double>) {
    companion object {
        /** NaN 행 제외, visibleAt null 행 제외, rowDate 오름차순. 정본 build_known_series:289. */
        fun build(output: List<Pair<LocalDate, Double>>, inputSeriesIds: List<String>, ...): KnownSeries
    }
    /** 정본 lookup_known:320 — bisectRight(visibilityTs, evaluatedAt) - 1, 음수면 null. */
    fun lookup(evaluatedAt: Instant): Triple<LocalDate, Instant, Double>?
}
```
- 2계열 이상 지표의 `visible_at`은 **각 입력 계열 규칙의 최댓값**(worst-of-inputs, §2.8).
- 반환된 `visible_at`이 그대로 **스테일 판정의 입력**이다(§2.8 등호 규약 — 초과만 stale).

**파리티 지뢰 6 — 2계열 인덱스 정렬**: `vix_term_structure`는 `ratio(vix, vix3m)`인데 정본은 pandas의
인덱스 정렬을 탄다(한쪽에 없는 날짜는 NaN). `global_corr_break`는 정본이 **명시적으로 `_align_to_ffill`**을 쓴다
(KOSPI 인덱스에 SPX를 forward-fill 정렬). Kotlin은 이 둘을 **다르게** 구현해야 한다 —
`ratio`는 교집합 밖 NaN, `global_corr_break`는 ffill 정렬. 하나로 뭉치면 값이 갈린다. L1 벡터에 둘 다 포함.

**파리티 지뢰 7 — `bisectRight`의 등호 (치명)**: 정본은 `bisect_right(visibility_ts, evaluated_at) - 1`이므로
**가시 시각 == `evaluatedAt`인 행이 포함**된다. 그런데 mobile_daily에서 KRX·FX 계열의 가시 시각은
**바로 그 거래일의 확정 틱 시각**이다(§2.8) — 즉 **매 확정 틱마다 등호에 정확히 걸린다.**
Kotlin이 `bisectLeft`(또는 `< ` 비교)를 쓰면 **KRX 4지표 + KRW=X가 매 틱 통째로 소실**되어
composite·`distinct_axes`가 붕괴한다. §2.8의 stale 등호(초과만 stale)와 **쌍을 이루는 등호 함정**이다.
→ 증인 **W-K1**: 가시 시각 == `evaluatedAt` → **선택됨**, 가시 시각 == `evaluatedAt + 1ms` → **선택 안 됨**.

#### 3단계와 기존 증인의 매핑 (라운드 2 산출물 유지)

| 증인 | 소속 Stage | 비고 |
|---|---|---|
| W-L4(revision 최댓값) | Stage 1 | SQL `GROUP BY as_of` + `MAX(revision)` |
| W-L1(캐치업 행 보임)·W-L2(미래 as_of 배제)·W-L3(^VIX 당일 배제) | **Stage 3** | 라운드 2에서 "as-of 리졸버" 단언이던 것을 Stage 3 lookup 단언으로 재배치. 내용·기대값 불변 |
| W-V1~W-V3(가시 규칙)·W-V4(stale 등호) | Stage 3 입력 | §2.8 |
| **W-K1**(lookup 등호) | Stage 3 | 신설 |
| W-W1(워밍업 충분성) | Stage 1 | 신설 — 레지스트리 최대 요구 창 ≤ `warmup_calendar_days` |

### 2.12 레인 분리 — 프리뷰 적재가 확정 원장을 오염시키지 못하게 (AD-A13, 반려 A-13 해소)

**의무와 위험이 같은 문장에서 나온다.** `docs/ARCHITECTURE_SPLIT.md` **D-17 §3**:

> 프리뷰 수집치도 Room lake에 append한다(observed_at=now). **일일 확정 틱은 마감 기준 as-of로 읽는다**
> — PIT 규율(D-06) 유지.

앞 절이 적재를 **의무화**하고 뒷 절이 확정 틱의 읽기를 **제한**한다. 라운드 4의 Stage 1 쿼리
(`… MAX(revision) … GROUP BY as_of`)에는 그 제한이 없었다 — `source` 열은 provider 구분(yahoo/krx/…)일 뿐
레인 구분이 아니다.

**실패 시나리오(불가역)**: D일 13:00 프리뷰가 `KRX:1001 close as_of=D`(장중 부분봉)를 append →
그날 17:00 확정 수집이 실패 → 그 셀의 **유일한 행이 프리뷰 값** → Stage 1이 그것을 골라 종가로 취급 →
`tick_input` 동결(§2.10) → append-only라 정정 불가 → **252일 워밍업 창에 영구 잔류**해 이후 전 z-score를 오염.

#### (a) 판별자 설계 — 왜 `lane` 컬럼 + 하드 필터인가

| 안 | 내용 | 판정 |
|---|---|---|
| **(가) `source` 문자열 표식 + 확정 우선 정렬** | `source='krx_preview'` 등으로 표시하고 `ORDER BY`에서 확정을 앞세움 | **탈락 (결정적)**. *우선순위*는 확정 행이 **존재할 때만** 작동한다. 위 시나리오는 확정 행이 **없는** 경우이므로 그대로 프리뷰로 폴백한다 — 즉 이 안은 문제의 그 케이스를 못 막는다. 부수적으로 provider × lane으로 `source` 값이 배증하고 필터가 문자열 매칭이 되어 신규 provider 추가 시 누락되기 쉽다 |
| **(나) 프리뷰 전용 별도 테이블** | `observation_preview` 분리 | 차선. 구조적으로는 가장 강하나 스키마·트리거·내보내기·진단이 두 벌이 되고, 프리뷰 읽기 경로가 UNION이 된다 — 방어 1개를 얻고 유지 지점 4개를 늘린다 |
| **(다) `lane` 컬럼 + `WHERE lane = 0` 하드 필터** | `lane INTEGER NOT NULL`(0=확정, 1=프리뷰), 확정 조회는 필터로 **배제** | **채택.** 컬럼 1개 + SQL 조건 1개. 확정 행이 없으면 결과는 **결측**이고, 결측은 엔진이 이미 처리한다(D-02 분모 제외 / 전 지표 결측이면 D-25 §3 동결) — **새 코드가 필요 없다** |

**필터 > 선호의 원리**: 사고를 *대체값*으로 덮으면 시스템은 조용히 틀린 값을 확신하고, *결측*으로 두면
이미 있는 결측 경로가 정직하게 처리한다. D-17 §3의 "마감 기준으로 읽는다"는 **배제 의무**로 읽는 것이 맞다.
`ponytail: lane은 정수 1개다. 레인이 3개 이상(예: 서버 스냅샷 provider, D-21 INT)으로 늘면 그때 enum으로 승격한다.`

#### (b-0) **프리뷰 경로의 원장 접근 규율 — 읽기 지점 전수표 (M-43b, 라운드 6 필수 확인 대상)**

3라운드 연속 결함원의 원인은 "읽기 지점을 열거하지 않고 지점별로 대응"한 것이었다.
**프리뷰가 관여하는 원장 읽기 지점은 정확히 3개이며, 아래가 그 전수다.** 새 읽기 지점을 만들려면
이 표에 행을 추가하는 것이 선행이다(AT-10이 표 밖의 조회를 금지한다).

| # | 읽기 지점 | 대상 테이블 · DAO 메서드 | **반환 계층** | **판별자 값** | tie-break | 근거 | **증인** |
|---|---|---|---|---|---|---|---|
| **①** | **확정 틱 Stage 1 조회**(§2.11) | `observation` · `confirmSeries()` | **원계열 관측값** `(as_of, value)` → Stage 2·3이 소비 | **`lane = 0`만** (하드 필터, 프리뷰 행 배제) | `revision DESC` | D-17 §3 "확정 틱은 마감 기준 as-of로 읽는다" | **W-P1**(확정 수집 실패일에도 프리뷰 값 미사용 → 결측) · W-P3(두 레인 공존) |
| **②** | **프리뷰 신선분 조회** | `observation` · `previewSeries()` | **원계열 관측값** `(as_of, value)` → Stage 2·3이 소비 | **`lane IN (0, 1)`** | **`lane ASC` = 확정 우선**, 그다음 `revision DESC` | 같은 `as_of`에서 종가(확정)는 장중 부분봉(프리뷰)보다 **항상 우월**하다 — "신선분"은 최신 *적재 시각*이 아니라 **최선의 관측** | **W-P5**(13:00 프리뷰 → 17:00 확정 → 18:00 프리뷰에서 **종가 선택**) |
| **③** | **carry-forward 원천** | **`tick_input`**(★라운드6 전환) · `lastCommittedSeverities()` | **severity 맵**(지표 id → `Int?`) — `compute_composite`에 **직결** | **불요** — `tick_input`은 확정 틱만 쓰므로(AT-9) 테이블 자체가 확정 전용 | `WHERE composite IS NOT NULL ORDER BY trading_date DESC LIMIT 1` | D-23 §23.3-1 "직전 **확정**값을 이월" = 확정 틱이 **실제로 커밋한 severity**. 원계열 재조회는 계층이 어긋나고(A-16) 개정치 유입 시 커밋값과 갈린다 | **W-P2**(개정) · **W-P6** ★신규(0행 = 설치 직후 → 이월 없이 결측 유지, M-50) |

`observation` 쓰기 지점은 2개다: 확정 틱 수집 → `lane = 0`, 프리뷰 수집 → `lane = 1`(`observed_at = now`, D-17 §3).
`tick_input` 쓰기 지점은 1개(확정 틱, AT-9).
carry-forward 이월값은 **어느 테이블에도 쓰지 않는다**(D-23 §23.3-1 "Room에 새 레코드로 쓰지 않는다").
**①②는 `observation`(원계열 계층), ③은 `tick_input`(severity 계층)** — 반환 계층이 다르므로 소비자도 다르다:
①②는 Stage 2·3으로, ③은 `compute_composite`로 직행한다.

#### (b) 스키마·쿼리 (공통 요구 ① — SQL 수준 명시)

```
observation
  …(§3 MT1-03a 기존 컬럼)…
  lane INTEGER NOT NULL             -- 0 = 확정 틱 수집, 1 = 프리뷰 수집(D-17 §3)
  UNIQUE(series_id, field, as_of, lane, revision)    -- ★ lane 편입: 같은 as_of에 두 레인 공존 허용
  INDEX(series_id, field, lane, as_of)               -- 확정 조회가 인덱스 선두를 탄다
```
`revision`은 **레인별로** 채번한다(프리뷰 정정이 확정 revision을 밀지 않는다).

DAO는 **원시 쿼리를 노출하지 않고** 아래 3개 이름만 노출한다 — 술어가 메서드 안에 있어 호출부가 빠뜨릴 수 없다:

```sql
-- ① confirmSeries(): 확정 틱 Stage 1. 프리뷰 행은 SQL 수준에서 배제된다.
SELECT as_of, value, MAX(revision) AS revision FROM observation
 WHERE series_id = :s AND field = :f AND lane = 0        -- ★ 하드 필터
   AND as_of BETWEEN :from AND :to
 GROUP BY as_of ORDER BY as_of ASC;

-- ② previewSeries(): 프리뷰의 신선분. 두 레인을 모두 보되 동일 as_of는 **확정이 이긴다**.
SELECT as_of, value FROM observation o
 WHERE series_id = :s AND field = :f AND lane IN (0, 1)
   AND as_of BETWEEN :from AND :to
   AND o.rowid = (SELECT i.rowid FROM observation i
                   WHERE i.series_id = o.series_id AND i.field = o.field
                     AND i.as_of = o.as_of AND i.lane IN (0, 1)
                   ORDER BY i.lane ASC, i.revision DESC LIMIT 1)  -- ★ lane ASC = 확정 우선
 ORDER BY as_of ASC;

-- ③ lastCommittedSeverities(): carry-forward 원천. ★라운드6 전환 — observation이 아니라 tick_input.
--    반환이 곧 severity 맵이라 compute_composite에 직결된다(계층 단절 해소, 반려 A-16).
SELECT trading_date, severities_json, registry_version FROM tick_input
 WHERE composite IS NOT NULL          -- 평가 불능·공백 틱(D-25 §3)은 이월 원천이 아니다
 ORDER BY trading_date DESC LIMIT 1;  -- "직전 확정" = 마지막으로 평가된 확정 틱 1건
```

**③이 `observation` 재조회가 아닌 이유** (반려 A-16 — 두 대안이 모두 막혀 있다):
- **원값을 `classify_severity`에 직접 투입 = 오류.** severity는 **transform 출력**을 임계와 비교해 얻는다.
  원 VIX≈15를 z 임계 `{1.5, 2.0, 3.0}`에 넣으면 z-score 6지표가 **상시 severity 3**이 된다.
- **원계열 재주입 후 재변환 = §2.11 3단계 계약 위반.** 이월 1점을 위해 Stage 1~2를 다시 도는 것은
  파이프라인 밖 우회 경로를 만드는 일이고, 그 경로는 파리티 대상이 아니다.
- **`tick_input`은 확정 틱만 쓴다(AT-9)** → ③에 레인 필터가 **구조적으로 불요**하다.
- **개정치 유입 문제도 사라진다**: `observation`을 다시 읽으면 확정 커밋 이후 들어온 revision이 섞여
  "확정 틱이 실제로 커밋한 값"과 갈릴 수 있다. `tick_input`은 동결이므로 갈릴 수 없다(§2.10).

**이월 깊이 = 1(마지막 평가 틱)로 고정한다.** 그 틱에서 이미 결측이던 지표는 **프리뷰에서도 결측으로 남는다**
(지표별로 더 과거까지 거슬러 올라가지 않는다). 근거: D-23 §23.3-1의 문언이 "직전 확정값"으로 단수이고,
지표별 심층 탐색은 SSOT에 없는 정책을 발명하는 일이며 임의로 오래된 값을 되살린다.
`ponytail: 이월 깊이 1. 특정 지표가 며칠씩 결측이라 커버리지가 상시 미달하면 그때 지표별 walk-back을
D-23 개정 제안과 함께 도입한다 — 정책 신설이므로 코드보다 결정이 먼저다.`

이월값의 스테일 배지 `as_of`는 그 `tick_input` 행의 `trading_date`(확정 시각)를 쓴다.

- 프리뷰 적재는 `lane = 1`, `observed_at = now`(D-17 §3 문언 그대로).
- `tick_input` 동결은 영향 없다 — 쓰는 경로가 확정 틱뿐이다(AT-9).
- **AT-10 신설**: `app.tick.confirm`·`IndicatorRuntime`의 확정 경로에서 `previewSeries` 참조 0건,
  그리고 `observation`을 읽는 코드는 위 3개 DAO 메서드 밖에 존재하지 않는다.

#### (c) 프리뷰 경로의 `evaluated_at` (공통 요구 ② — 병합 결정 M-39에 대한 A의 입장)

| 경로 | `evaluatedAt` | 기존 규정 |
|---|---|---|
| 확정 틱 | **D 17:00 KST**(SSOT `confirm_time_kst`) | §2.10·C-1 유지 |
| 캐치업 틱 | **그 거래일 D의 확정 시각** | §3 MT1-06b 3항 유지 |
| **프리뷰** | **호출 시각 `now`(UTC instant)** | **본 절에서 확정** |

`visibleAt`은 프리뷰에서도 **§2.8 그대로 일 단위**(그리드일의 확정 시각)를 쓴다 — 레인마다 가시성 규칙을
따로 두면 확정·프리뷰·하니스가 세 벌이 되어 §2.8이 무의미해진다. 따라서 나이는

> **나이 = `now − visibleAt` = 실경과 시간** (24h 배수가 아니다)

이며, 스테일 판정도 **확정 틱과 완전히 같은 함수·같은 창**에 인자만 다르게 넣는다.

**근거** (1·2가 주논거, 3은 보조):
1. **정직성**: D-17 §2가 프리뷰에 `as_of` 표기를 의무화한다. 사용자에게 보이는 "몇 시간 전 데이터"는
   실경과여야 한다. 13:00에 본 전일 종가는 20시간 전 값이지 "1일 전"이 아니다.
2. **미가시 값 유입 차단**: `evaluatedAt`을 17:00으로 스냅하면 그날 17:00에 가시화될 값들이
   13:00 프리뷰에서 **이미 보이게** 된다(§2.11 지뢰 7의 등호가 그대로 성립하므로). 프리뷰가 미래를 본다.
3. **창 해상도 보존 (라운드 5 정정 — 반려 A-14, 범위를 사실에 맞게 축소)**:
   `mobile_daily`의 창 중 **`daily_kr 30h`만 24h의 배수가 아니다**(`daily_us 48h`·`fred_daily 96h`는 배수다).
   `evaluatedAt`을 확정 시각으로 스냅하면 경과가 24h의 배수만 나와 **30h가 24h와 구별되지 않는다**
   (24h ≤ 30h fresh, 48h > 30h stale — 즉 "1일 관용"으로 뭉개진다). 실경과로 재야
   "전일 종가는 다음날 23:00까지 유효"라는 30h의 원래 의미가 살아난다.
   > **철회한 주장**: 라운드 4에 적은 "48h·96h도 24h 배수가 아니다"와 "BT-03 스윕 선정값의 의미가 사라진다"는
   > **둘 다 사실 오류**다. 48h·96h는 배수이고, BT-03이 스윕해 선정한 것은 `daily_us 48h`이며
   > `daily_kr`은 "극단값에서도 거동 무효 확인, **스윕 미대상**"으로 기록돼 있다(indicators.yaml L240-241).
   > 따라서 이 논거는 **보조**로 격하하고, 결론은 논거 1·2가 지탱한다.

확정 틱에서 경과가 24h 배수로 나오는 것은 틱 자체가 일 단위이기 때문이며, 비대칭이 아니라
**같은 식에 그 틱의 실제 시각을 넣은 결과**다.

#### (d) 증인 테스트

| ID | 시나리오 | 기대 |
|---|---|---|
| **W-P1** ★ | D일에 프리뷰가 `KRX:1001 close as_of=D, lane=1`만 적재(확정 수집 실패) → D 확정 틱 실행 | 그 지표는 **결측**(프리뷰 값 미사용) → D-02 분모 제외, 전 지표 결측이면 `composite=NULL` 동결. **`AND lane = 0`을 제거하면 프리뷰 값이 선택되어 실패**(퇴화 입력 증인) |
| **W-P2** ★개정(A-16) | 프리뷰가 자기 lane=1 행을 적재하고, 확정 커밋 이후 `observation`에 새 revision까지 들어온 상태에서 carry-forward 수행 | 이월값 = **마지막 `tick_input` 행의 severity**(프리뷰 값도, 이후 revision을 재변환한 값도 아니다). `observation` 재조회로 되돌리면 revision 유입으로 값이 갈려 실패 |
| **W-P6** ★신규(A-16·M-50) | `tick_input` **0행**(설치 직후 첫 프리뷰) | carry-forward **미수행** — 결측은 결측으로 남는다(임의 대체값 없음). `coverage`(raw)가 그만큼 낮게 나오고 `min_coverage` 미달이면 §2.12 규율대로 판정 억제 |
| **W-P7** ★신규(A-16) | 마지막 `tick_input` 행에서 이미 결측이던 지표 | 프리뷰에서도 **결측 유지**(이월 깊이 1 — 더 과거로 walk-back 하지 않는다) |
| **W-P3** ★ | 같은 `(series, field, as_of)`에 lane 0·1 각각 revision 0 적재 | `UNIQUE` 충돌 없이 공존, `confirmSeries()`는 lane 0만 반환 |
| **W-P4** ★ | 프리뷰를 13:00에 실행, 전일 17:00 가시 값 | 나이 = **20h**(24h 아님), `daily_kr 30h` 창 안이므로 유효 |
| **W-P5** ★신규(A-15) | 13:00 프리뷰가 `as_of=D, lane=1`(장중 부분봉) 적재 → 17:00 확정 틱이 `as_of=D, lane=0`(종가) 적재 → **18:00 프리뷰 실행** | `previewSeries()`가 **17:00 종가**를 선택한다(13:00 부분봉 아님). **`lane ASC`를 `lane DESC`로 되돌리면 부분봉이 선택되어 실패**(퇴화 입력 증인) |

---

## 3. 서브태스크 분해

표기: **[P]** = 같은 웨이브 내 병렬 가능 · **위임** = 담당 서브에이전트 · 각 항목의 완료 기준은
**실행 가능한 명령**으로 적는다(Windows는 `./gradlew` → `.\gradlew.bat`).
전 항목 공통으로 `qa-verifier → aaa-critic` 2단 PASS가 완료의 정의다(AAA §1).

### MT1-00 실측 선행 (신설) — 다른 모든 것을 블록하는 사실 확정

| ID | 내용 | 위임 | 블록 대상 |
|---|---|---|---|
| **00a** [P] | 야후 REST 실측: `^VIX`·`^VIX3M`·`^MOVE`·`^GSPC`·`DX-Y.NYB`·`KRW=X` 6종 — 엔드포인트 형태(chart v8), 인증/crumb 필요 여부, 심볼 URL 인코딩, 일봉 필드 가용성. **Stooq 폴백 심볼 매핑표** 실측(6종 각각 대응 심볼 존재 여부 — 없으면 "폴백 불가"를 사실로 기록) | data-verifier | MT1-04a |
| **00b** [P] | FRED: `BAMLH0A0HYM2`·`T10Y2Y` observations 응답 형태·결측 표기(`"."`)·개시일. **ECOS K-04**: `721Y001` 하위 item_code 실조회로 `corp_aa3y`·`ktb_3y` 확정. **`VERIFY` 플레이스홀더의 실위치는 `configs/indicators.yaml`의 `krx_credit_spread_delta.source.item_codes`다**(sources.yaml에는 stat_code 주석만 있다 — 라운드 1의 오기 정정, 반려 A-4). 반영 대상 SSOT 제안은 §11 **C-8** | data-verifier | MT1-04b·04d |
| **00c** [P] | kotlin_krx 실측(§2.3 후단 4항): 무로그인 가부 / `login()` 현행 성공 여부 / **VKOSPI(MDCSTAT01201) 가부** / 지수 OHLCV에 **거래대금 필드** 존재 여부(`kospi_volume_distribution` 필수) / 투자자별 순매수 단위·부호 / `getBusinessDays` 동작 | data-verifier | MT1-01d·04c, **U-3** |
| **00d** [P] | G-4 CDS 접근성: 모바일에서 정적 GET + 단순 추출로 KR CDS 5Y를 얻을 수 있는가. **판정 기준 사전 고정** — "정적 GET 1회 + 정규식 1개로 추출 가능하고 3일 연속 성공"이면 (a) 수집 구현, 아니면 (b) 미수집 확정 | data-verifier | MT1-04f, **U-4** |
| **00e** [P] | 툴체인: AGP↔Kotlin↔Gradle 호환 매트릭스 확인, `snakeyaml-engine` 최신 안정 버전·Android 호환 근거, Konsist·Robolectric·work-testing·room-testing 최신 안정, kotlin_krx origin push 상태(`git log origin/main..HEAD`) | kotlin-implementer | MT1-01a 전부 |
| **00f** [P] | (선택) KIS 자산 재사용 가능성 — TinyOscillator 검증 자산의 인증 방식·엔드포인트 범위 | data-verifier | MT1-04e |
| **00g** ★신설 [P] | **확정 틱 17:00의 물리 전제 실측** (반려 A-6, AD-3b "M1 실제 확정 틱 설계와 동시 재확인" 이행). **연속 3거래일 이상**, 매 거래일 `16:00 / 16:30 / 17:00 / 17:30 / 18:00 / 19:00` KST에 폴링해 각 데이터셋이 **그 날의 최종값을 처음 반환하는 시각**을 기록한다: ① KOSPI 지수 종가·거래대금(1001, MDCSTAT00301) ② 투자자별 순매수(MDCSTAT02203) ③ VKOSPI(MDCSTAT01201) ④ `KRW=X` 일봉. **산출 = "안전한 최소 확정 시각"**. 부수 산출: 각 collector의 **range 조회 상한**(한 번에 요청 가능한 최대 일수) — MT1-06b 캐치업 상한의 입력 | data-verifier | **AD-A7 / §9 / C-1·C-6 / MT1-06** |

**00g의 판정 규칙(사전 고정 — 사후 합리화 금지)**: 4개 데이터셋 전부가 17:00 이전에 최종값을 내면
**17:00 확정**(§9의 논증이 그대로 성립). 하나라도 17:00 이후면 → (i) 가장 늦은 확정 시각 + 30분 여유로
확정 시각을 **상향** 제안하고 §11 C-1·C-6의 값을 그에 맞춰 갱신, (ii) 동시에 `backtest/replay.yaml`
`confirm_time_kst`와의 불일치가 생기므로 **골든 재산출 필요 여부를 backtest-analyst가 판정**한다
(하니스는 이 값에 무감함이 BT-03에서 실측됐으므로 골든 무회귀가 예상되나, 예상을 근거로 쓰지 않고 실행해 확인한다).
17:00보다 이르게 낮추는 방향은 검토하지 않는다 — `daily_kr` 수집 크론 16:50이 하한이다.

**완료 기준**: 실측 결과가 `docs/journal/2026-08-XX_MT1-00_verification.md`에 재현 절차와 함께 기록되고,
sources.yaml 변경이 필요한 항목은 §11의 변경 제안 형식으로 상신(직접 수정 금지).
"확인 불가"도 유효한 산출이며, 그 경우 무엇이 막았는지를 적는다(REVIEW_M0 규율 ②: 결측 귀속에는 증거를 붙인다).

### MT1-01 스캐폴드 + SSOT 동기화

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **01a** | `mobile/` Gradle 루트, 3모듈, 버전 카탈로그, 툴체인 17, minSdk 29, 인코딩 규율, 동적버전 금지(§2.2) | kotlin-implementer | `cd mobile && ./gradlew projects` 3모듈 표시 · `./gradlew :engine:test :app:assembleDebug` green · `./gradlew dependencies --configuration debugRuntimeClasspath` 출력에 SNAPSHOT·동적 버전 0건 |
| **01b** | ktlint + detekt + **JaCoCo 임계 검증(§2.9: 규칙 2종·제외 6패턴·`check` 배선)** + release 단위테스트 비활성 | kotlin-implementer | `cd mobile && ./gradlew check` green · `./gradlew check --dry-run`에 `connected*` 부재 · `./gradlew :engine:jacocoTestCoverageVerification :app:jacocoTestCoverageVerification` green · **임계 미달 상황을 임시로 만들면 실패**함을 증인으로 확인 |
| **01c** | `syncConfigs`(생성 assets) + `ssot.sha256` 매니페스트 + L-A JVM 테스트 + L-B 계측 테스트 + `verifyNoCheckedInAssets` + snakeyaml-engine 로드 스모크 | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*ConfigsManifest*"` green · `./gradlew connectedDebugAndroidTest --tests "*ConfigsAssets*"` green · `configs/indicators.yaml` 1바이트 변경 후 재실행 시 **실패**함을 증인 테스트로 확인 |
| **01d** | `:krx` 벤더링 + PROVENANCE.md + `krx-manifest.sha256` + `verifyKrxProvenance` + integration 테스트 제외 + 자격증명 주입 지점(`KrxCredentials` 인터페이스) | kotlin-implementer | `./gradlew :krx:test` green(네트워크 0) · `./gradlew :krx:verifyKrxProvenance` green · 소스 1바이트 변경 시 **실패** 증인 |

K-xx: 01c → **K-16**, 01d → **K-03**(rate limit 훅 자리 확보)·**K-17**(자격증명이 소스·assets에 없음).

### MT1-02 계약 미러 + 스키마 스냅샷 (브리프 §5-4의 답)

**스냅샷 파일 위치·형식** — `contracts/snapshots/` 신설(기존 `contracts/*.py`는 **무수정**, §11 C-3):

```
contracts/snapshots/evidence-pack-1.schema.json      # model_json_schema(by_alias=True) 동결
contracts/snapshots/evidence-pack-1.example.json     # 전 필드 채운 정본 인스턴스
contracts/snapshots/scenario-snapshot-1.schema.json
contracts/snapshots/scenario-snapshot-1.example.json
scripts/gen_contract_snapshots.py                    # 생성기(읽기 전용으로 contracts import)
```

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **02a** | Python 생성기 + 동결 스냅샷 4종 + `tests/test_contract_snapshots.py`(스키마 재생성 일치, example 왕복, 재생성 결정론) | python-implementer | `uv run pytest tests/test_contract_snapshots.py -q` green · `uv run python scripts/gen_contract_snapshots.py --check` (재생성 diff 0) |
| **02b** | Kotlin 미러(kotlinx.serialization) + 커스텀 직렬화기 + 왕복·동결 일치 테스트 | kotlin-implementer | `cd mobile && ./gradlew :engine:test --tests "*ContractSnapshot*"` green |

**"동결 일치 판정"의 정의** (모호하면 게이트가 게이트 구실을 못 한다):

1. **구조 일치**: Kotlin `SerialDescriptor`를 순회해 얻은 {필드명(직렬화명), 필수/선택, 타입 종류}
   집합이 `*.schema.json`의 `properties`/`required`와 일치. 필드 추가·삭제·선택성 변경이 즉시 FAIL.
2. **값 왕복**: `example.json` → 디코드 → 인코드 → **JsonElement 트리 동등**(바이트 비교 아님).
   바이트 비교는 키 순서·수치 표기 차이로 언어 간에 필연 실패한다 — 의미 동등이 올바른 기준이다.
3. **Python 측 대칭**: 같은 `example.json`을 pydantic이 `model_validate_json` → `model_dump_json(by_alias=True)`
   후 트리 동등. 즉 **같은 파일을 양쪽이 각각 왕복**한다(브리프 §2-9의 "왕복 검증").

**미리 못 박는 미러 함정 5개** (여기서 안 정하면 구현 중에 반드시 터진다):

| # | pydantic | Kotlin 대응 | 위험 |
|---|---|---|---|
| 1 | `datetime` → `"2024-08-05T08:00:00+00:00"` | `Instant` 기본 직렬화는 `...Z` | **표기 불일치**. 커스텀 `KSerializer<Instant>`가 pydantic 형식으로 쓰고 두 형식 모두 읽도록 한다. 시간대 오프셋(`+09:00`)·소수초 케이스를 벡터에 포함 |
| 2 | `tuple[float, float]` (`kospi_range_pct`) → `[a, b]` | `Pair`는 `{"first":..,"second":..}`로 직렬화 | **형태 불일치**. `List<Double>` + `init { require(size == 2) }` |
| 3 | `Literal[...]` (Phase, event type 등) | `enum class` + `@SerialName` | 문자열 값이 정확히 일치해야 함. 전 값 왕복 벡터 필수 |
| 4 | `schema_id` 필드에 `alias="schema"` | `@SerialName("schema")` | 별칭 누락 시 조용히 다른 키가 나간다 |
| 5 | `confloat(ge=0, le=100)`·`min_length=2` | `init { require(...) }` | Kotlin은 제약을 표현하지 않으면 검증이 사라진다. 경계값 벡터(0·100·초과)로 증인 테스트 |

K-xx: **K-05**(datetime tz-aware 강제), **K-07**(Double).

### MT1-03 Room append-only lake (브리프 §5-5의 답)

**스키마** (D-06: `observed_at`, `as_of`, `source`, `revision`, `raw`):

```
observation
  id           INTEGER PK AUTOINCREMENT
  series_id    TEXT NOT NULL      -- 픽스처 롱포맷과 동일 어휘(파리티·백테스트 호환)
  field        TEXT NOT NULL      -- close / high / low / value / net_buy_value ...
  as_of        INTEGER NOT NULL   -- epoch millis, UTC (데이터 기준 시점)
  observed_at  INTEGER NOT NULL   -- epoch millis, UTC (수집 시각)
  revision     INTEGER NOT NULL DEFAULT 0
  source       TEXT NOT NULL      -- yahoo / stooq / fred / ecos / krx / kis
  value        REAL               -- Double, NULL 허용(명시적 결측 관측)
  raw          TEXT               -- 원응답 일부(감사용, 선택)
  lane         INTEGER NOT NULL   -- ★ 0 = 확정 틱 수집, 1 = 프리뷰 수집 (§2.12, D-17 §3)
  UNIQUE(series_id, field, as_of, lane, revision)   -- lane 편입: 두 레인 공존 허용, revision은 레인별 채번
  INDEX(series_id, field, lane, as_of)
```

**append-only 물리 강제 3중**:
1. DAO에 `@Update`·`@Delete` **미구현** (TASK 문언, AT-6이 회귀 감시).
2. **SQLite 트리거** — `@Database` 콜백에서 생성:
   `CREATE TRIGGER observation_no_update BEFORE UPDATE ON observation BEGIN SELECT RAISE(ABORT,'append-only'); END;`
   + DELETE 동일. **raw query 우회조차 막힌다** — DAO 미구현보다 한 단계 강하다.
3. 마이그레이션 규약: 파괴적 스키마 변경 금지, 신규 컬럼만. `fallbackToDestructiveMigration` **금지**(원장 소실).

**as-of 조회** (PIT·K-11 방어의 핵심) — **라운드 1 설계 철회·재설계**(반려 A-2):

> **철회한 설계**: `AND observed_at <= :evaluatedAt`. 이 절은 캐치업을 **논리적으로 무효화**한다 —
> 놓친 거래일 D의 데이터는 오늘 수집되므로 `observed_at = 오늘`이고, D의 확정 틱은 `evaluatedAt = D 17:00`이라
> **모든 행이 배제되어 전 지표 결측 → D-25 §3 "평가 불능" 동결**이 된다. 반대로 `evaluatedAt = now`로 두면
> D의 국면을 오늘 정보로 판정하는 셈이라 PIT가 무너지고 증인 테스트도 공허해진다. 두 완료 기준(MT1-03a의
> look-ahead 차단, MT1-06b의 캐치업 성공)이 이 절 아래에서 양립할 수 없다.

**재설계: 시간 필터를 `observed_at`에서 파생 `visible_at`으로 옮긴다** (§2.8),
**그리고 그 필터는 `observation` 행이 아니라 transform 출력에 적용된다**(§2.11, 라운드 4 정정).

```sql
-- Stage 1 (§2.11) = DAO confirmSeries(). 원계열을 "범위"로 낸다(최신 1행 아님 — 롤링 창 필요).
SELECT as_of, value, MAX(revision) AS revision
  FROM observation
 WHERE series_id = :s AND field = :f
   AND lane = 0                          -- ★ §2.12: 프리뷰 적재 행 하드 배제 (D-17 §3)
   AND as_of BETWEEN :from AND :to       -- from = D − warmup_calendar_days(C-11), to = D
 GROUP BY as_of                          -- as_of당 최신 revision 1행 (W-L4)
 ORDER BY as_of ASC
```
```kotlin
// Stage 2 → Stage 3 (§2.11): 전체 계열 변환 후, 출력에 가시성을 붙여 고른다
val output = Transforms.zscore(raw, window = 252)                       // Stage 2
val known  = KnownSeries.build(output, inputSeriesIds = listOf("^VIX")) // Stage 3 색인
val hit    = known.lookup(evaluatedAt)   // bisectRight − 1 (등호 포함, 지뢰 7)
```

- **캐치업이 성립한다**: 오늘 수집한 D-1일 KRX 행의 `visible_at`은 "D-1 이상 첫 거래일의 17:00" =
  D-1 17:00 ≤ D 17:00 → **보인다.** 수집 시각(`observed_at`)이 언제였는지는 무관하다.
- **look-ahead가 더 강하게 차단된다**: `as_of = D+1`인 행이나, `^VIX` `as_of = D`(미국계는 D 이후 첫 거래일에
  가시)인 행은 `visible_at > D 17:00` → **보이지 않는다.** 즉 "언제 수집했는가"가 아니라
  **"그때 알 수 있었는가"**로 판정한다 — K-11의 본래 정의다.
- `observed_at`은 **감사·진단 전용**으로 남는다(D-06 요구 필드, "얼마나 늦게 수집됐는가" 이력·MT1-09b 진단).
  판정 경로에서 읽지 않는다 — 이를 AT-8(아키텍처 테스트)로 못 박는다.

**증인 테스트 6종** (전부 비공허 — 각각 실패 가능한 조건이 실재한다. Stage 매핑은 §2.11 말미 표):

| ID | Stage | 삽입 | 조회 | 기대 |
|---|---|---|---|---|
| W-L1 | 3 | KRX `as_of=D-1`, `observed_at=D+3`(캐치업 상황) | `evaluatedAt = D 17:00` | **선택된다** (라운드 1 설계에서는 실패했을 케이스) |
| W-L2 | 3 | KRX `as_of=D+1`, `observed_at=D-5`(미리 들어온 미래 행) | `evaluatedAt = D 17:00` | **선택되지 않는다** |
| W-L3 | 3 | `^VIX` `as_of=D`(미국계) | `evaluatedAt = D 17:00` | **선택되지 않는다** (D 이후 첫 거래일 17:00에 최초 가시) |
| W-L4 | 1 | 동일 `(series, field, as_of)`에 `revision 0,1` | 임의 | `revision` 최댓값 1행으로 축약 |
| **W-L5** ★ | 1 | 252거래일 이상 연속 원계열 | `zscore(window=252)` 요구 지표 | **Stage 1이 창을 채워** 산출이 NaN이 아니다. **범위 조회를 `LIMIT 1`로 되돌리면 NaN → 결측**으로 실패(N-1 회귀 감시) |
| **W-K1** ★ | 3 | 가시 시각 == `evaluatedAt` / == `evaluatedAt + 1ms` | — | 각각 **선택됨 / 선택 안 됨**(`bisectRight` 등호, 지뢰 7) |

W-L1과 W-L2·W-L3가 **서로 반대 방향**이므로, 필터를 어느 쪽으로 잘못 옮겨도 최소 하나가 실패한다 —
"항상 통과하는 단언"이 될 수 없다(REVIEW_M0 규율 ① 증인 요건 충족).

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **03a** | 엔티티(**`lane` 포함**)·`observation` DAO 2메서드(`confirmSeries`/`previewSeries`)·트리거·**Stage 1 범위 조회**(§2.11·§2.12)·멱등 append(동값 무시/이값 revision+1, 레인별 채번) | kotlin-implementer (**05a2 선행**) | `./gradlew :app:testDebugUnitTest --tests "*Lake*"` green — 포함: UPDATE/DELETE 시도 예외, **증인 W-L1~W-L5·W-K1·W-P1·W-P3·W-P5 전건**, 멱등 재삽입, 워밍업 범위 조회 |
| **03b** | 일 1회 CSV 내보내기 + **SAF 폴더 지정** 백업 훅 + 설정 토글 | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*Export*"` green(내보내기 포맷·행수·헤더) |
| **03c** ★라운드3 | **`tick_input` 동결 테이블**(§2.10 (b) 스키마) + `tick_run` 역할 분리 + UPDATE/DELETE 트리거 | kotlin-implementer | `--tests "*TickInput*"` green — `Tick` 4필드 왕복(NULL composite 포함), 동결 후 UPDATE 시도 예외, 동일 `trading_date` 재삽입 차단 |

**Drive 백업 훅의 범위 축소 판단**: Google Drive REST + 로그인 연동은 M1 목적(원장 무결·기능 검증)에
필요하지 않다. **SAF(`ACTION_OPEN_DOCUMENT_TREE`)로 사용자가 지정한 폴더에 CSV를 쓰고, 그 폴더를
Drive 앱이 동기화하게 한다** — Drive 전용 코드 0줄로 TASK의 "Drive 백업 훅(on/off)"을 만족한다.
전용 연동이 필요하면 M2/M3에서 사용자 결정으로 추가(§10 U-7).

K-xx: **K-05**(전부 UTC epoch millis 저장, 표시만 KST), **K-07**(REAL=Double), **K-11**(observed_at 절).

### MT1-04 collectors 6건 (브리프 §5-6의 답)

**공통 아키텍처** (먼저 확정해야 a~f를 병렬 위임할 수 있다 — **04g를 선행 산출로 분리한 이유**):

```kotlin
interface Collector {
    val id: String                                     // sources.yaml provider 키와 동일
    suspend fun collect(range: ClosedRange<LocalDate>): CollectOutcome   // ★ range 필수
}
sealed interface CollectOutcome {
    data class Ok(val rows: List<ObservationRow>) : CollectOutcome
    data class Partial(val rows: List<ObservationRow>, val failures: List<SeriesFailure>) : CollectOutcome
    data class Failed(val reason: FailureReason) : CollectOutcome        // 키 미설정/네트워크/쿼터/차단
}
```

- **range 조회는 선택이 아니라 요구사항이다.** 캐치업(MT1-06b)이 놓친 N일을 재구성하려면 과거 구간을
  다시 받아와야 한다. 단일 시점 API만 지원하는 어댑터가 하나라도 있으면 캐치업이 성립하지 않는다.
  (KIS만 예외 — 프리뷰 실시간 전용이라 캐치업 대상이 아니다.)
- **부분 실패는 지표별 결측으로 흡수**한다. 오케스트레이터는 `supervisorScope` + 어댑터별 `runCatching`으로
  한 소스의 예외가 전체 틱을 죽이지 못하게 한다(TASK 공통 요구, K-01·K-18 정면 대응).
- 네트워크 층: OkHttp 클라이언트 **1개 인스턴스 공유**(커넥션 풀·타임아웃 일원화) + Retrofit(JSON 계열).
  CSV 계열(Stooq)은 `ResponseBody` 직파싱 — CSV 하나 때문에 컨버터를 늘리지 않는다.

| ID | 내용 | 위임 | 실측 의존 | 완료 기준(명령) |
|---|---|---|---|---|
| **04g** | `Collector` 계약 · 오케스트레이터(병렬·부분실패·호출 예산 가드·rate limit) · 픽스처 테스트 하네스 | kotlin-implementer | — | `./gradlew :app:testDebugUnitTest --tests "*Orchestrator*"` green — 1개 어댑터 예외 시 나머지 정상 수집 시나리오 포함 |
| **04a** [P] | 야후 6종 + **Stooq 폴백** | kotlin-implementer | 00a | `--tests "*YahooCollector*"` green(정상·결측·404·본문 스키마 변경·폴백 전환) |
| **04b** [P] | FRED 2계열 (T+1, `"."` 결측 표기) | kotlin-implementer | 00b | `--tests "*FredCollector*"` green |
| **04c** [P] | KRX via `:krx` — 지수 OHLCV(1001)·거래대금·투자자별 순매수·(조건부)VKOSPI. **rate limit ≥1s·휴장 사전 스킵·로그인 세션 관리·빈 응답 구분 장치**(아래) | kotlin-implementer | 00c | `--tests "*KrxCollector*"` green(MockWebServer — 세션 만료 재로그인, **거래일 빈 응답 → Failed 분류**, 휴장일 사전 스킵) |
| **04d** [P] | ECOS `721Y001` 2 item_code (`corp_aa3y`·`ktb_3y` — 실위치는 `configs/indicators.yaml`, §11 C-8) | kotlin-implementer | 00b | `--tests "*EcosCollector*"` green |
| **04e** [P] | KIS 어댑터 — `sources.yaml` `enabled:false` **기본 비활성 유지**, 프리뷰 통합 지점 + 키 입력 경로만 | kotlin-implementer | 00f | `--tests "*KisCollector*"` green(비활성 시 호출 0회 단언 포함) |
| **04f** | G-4 판정 반영 — (a) 수집 구현 또는 (b) 미수집 확정 + UI "미수집" 배지 + GATE_GM1 기록 | kotlin-implementer | 00d, **U-4 결정** | (b)인 경우: `--tests "*CdsUncollected*"` green(배지 노출·분모 제외 무왜곡 단언) |

**04c 전용 — kotlin_krx의 빈 리스트 정책 흡수** (반려 A-8). 업스트림 오류 정책(`kotlin_krx/CLAUDE.md`
Error Handling 표)은 **Parse Errors → 빈 리스트 + 경고 로그**, **Empty Response → 빈 리스트(휴장 가능)**로
**두 사건을 같은 반환값에 뭉갠다**. 그대로 쓰면 조용한 파싱 실패가 "휴장"으로 기록되어 그 날의 틱이
정상 스킵으로 남고 캐치업이 영영 되돌아오지 않는다 — AAA §2.2 "조용한 실패" 그 자체다. 흡수 규율 3조:

1. **빈 응답을 휴장 근거로 쓰지 않는다.** 휴장 판정은 **사전**에 한다(`getBusinessDays` 1차 / 경험적 달력 2차).
2. 사전 판정이 "거래일"인데 빈 리스트가 오면 → `CollectOutcome.Failed(EmptyOnTradingDay)`로 분류한다.
   결측으로 기록하고, `tick_run.status = PARTIAL` + 실행 이력에 노출하고, **다음 캐치업의 재시도 대상**이 된다.
3. 경험적 거래일 달력은 **성공 응답에서만** 만든다(실패로 인한 빈 결과가 "휴장"으로 굳는 되먹임 차단).

업스트림 소스는 **수정하지 않는다** — 어댑터 한 층에서 흡수하는 편이 포크 diff보다 싸고, `verifyKrxProvenance`가
지키는 "무수정" 상태가 유지된다. 이 판단을 `PROVENANCE.md`에 "미수정, 어댑터에서 흡수(사유)"로 기록한다.

**전 어댑터 공통 테스트 전략**: **네트워크 금지**. 응답 픽스처(실측에서 저장한 실제 페이로드 축약본)를
`src/test/resources/`에 두고 MockWebServer로 재생. 각 어댑터마다 최소 5경로 — 정상 / 부분 결측 /
스키마 변경 / HTTP 오류 / 키 미설정. AAA §2.2 "조용한 실패 금지"가 여기서 검증된다.

**주의 (계획이 미리 경고해야 할 것)**: MT1-00b에서 ECOS item_code가 확정되면 모바일은
`krx_credit_spread_delta`(가중 2.0)를 **실제로 수집**하게 된다. 그런데 백테스트 픽스처는 이 지표를 수집한 적이 없어
(run_replay `_ALWAYS_MISSING_INDICATORS`) 골든의 coverage가 0.887(= (31.0-3.5)/31.0)로 동결돼 있다.
→ **파리티는 무해**(동일 입력 기준이며 벡터는 픽스처에서 나온다). 그러나 **프로덕션 국면 판정은
백테스트가 보정한 적 없는 분모 위에서 돌게 된다.** 리스크로 등록(§7 R-07)하고 C1 재보정 대상에 명시 이관을 권고한다.

K-xx: 04a → **K-01·K-18**, 04c → **K-03**, 04d → **K-04**, 04g/전체 → **K-10**(호출 예산), **K-17**(키).

### MT1-05 Kotlin 엔진·상태기계 + BT-05 (브리프 §5-7의 답)

**engine_ref ↔ Kotlin 모듈 대응표**

| engine_ref | Kotlin (`:engine`) | 포팅 시 주의 |
|---|---|---|
| `registry.py` — YAML 로드, transform 문자열 정규식 파싱, modifier rule 파싱, `stale_window`/`is_stale`, `ProfileParams`/`StatemachineConfig` | `config/RegistryLoader.kt` · `config/TransformSpecParser.kt` · `config/StaleWindows.kt` | 정규식 3종(`_KWARG_RE`, `_GATE_RE`, duration, `_FALLBACK_WINDOW_RE`)과 **괄호 깊이 파서**(`_extract_call_body`)를 그대로 옮긴다. `\b` 경계(`zscore` vs `neg_zscore` 오매칭 방지)는 Kotlin `Regex`에서도 동일 의미. **파서 전용 파리티 벡터**(transform 문자열 → 파싱 결과)를 별도로 둔다. **`isStale`의 첫 인자는 `visibleAt`으로 명명한다**(정본은 `run_replay.is_stale_check` — §2.8). **cadence 키 부재 시 `daily_kr` 폴백**(지뢰 4) 전용 테스트 필수 |
| `run_replay.py` §2 가시성(`raw_visibility_grid_day`·`visibility_tick_utc`·`combined_visibility_utc`) — **하니스가 아니라 프로덕션 규칙** | `pit/Visibility.kt` · `pit/TradingDayGrid.kt` (**★신설 05a2**) | §2.8 규칙표 그대로. 2계열 지표는 worst-of-inputs. mobile_daily는 그리드일의 **확정 틱 시각**을 쓴다(→ C-1 필수). 증인 W-V1~W-V4 |
| `series_values`(원계열 추출) · `build_known_series`:289 · `lookup_known`:320 — **파이프라인 Stage 1·3** | `RawSeriesSource`(인터페이스, 구현은 `:app`) · `pit/KnownSeries.kt` (**05a2 포함**) | §2.11 계약. `lookup`은 **`bisectRight − 1`(등호 포함)** — 지뢰 7. NaN 행·`visibleAt` null 행 제외 규칙까지 정본 그대로. 증인 W-K1·W-L5 |
| `_build_*` 지표 조립(15종) — **Stage 2 조립부** | `engine/IndicatorRuntime.kt` | 정본과 1:1 대응. `_align_to_ffill`(global_corr_break)과 `ratio`의 정렬 규칙이 **다르다**(지뢰 6). `vkospi_z`의 K-02 폴백 분기(창 데이터로 판정, 하드코딩 아님)도 이식 |
| `transforms.py` (pandas Series) | `Transforms.kt` (`DoubleArray` / `List<Double?>`) | **ddof=1**, `min_periods=window`, NaN 전파. §2.7 지뢰 1·2 |
| `scoring.py` | `Scoring.kt` | 등호 포함 `>=`, `direction="abs"`, `is_extreme`(severity와 완전 분리), `compute_composite`가 `(score, coverage)` 반환, `score=null` = 평가 불능(D-25 §3) |
| `modifiers.py` | `Modifiers.kt` | `hy_level_boost`는 **초과(>)**, `usdkrw_intraday_force`는 **이상(>=)**. 결측 기저도 강제 승급됨(원문 그대로) |
| `statemachine.py` | `StateMachine.kt` | **D-25 §1~4 전부** + **D-26 짝지음**(`_escape_blocks_exit`, 레벨-로컬, reset 경로) + `or_any_extreme`. `_KNOWN_UPGRADE_KEYS` 미지 규칙 → 즉시 실패(조용한 "항상 충족" 금지) |

**수치·시간 규율**: 전 계산 `Double`(K-07, `Float` 금지 — AT-5). KST는 `ZoneId.of("Asia/Seoul")` 명시,
저장·비교는 `Instant`(UTC). `LocalDateTime` 금지(AT-4, K-05). 반올림은 표시 계층에서만.
지표 순회는 `LinkedHashMap`(§2.7 지뢰 3).

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **05a** | 설정 로더 + transform/modifier 문자열 파서 + 파서 파리티 벡터 + **stale cadence 폴백** | kotlin-implementer | `./gradlew :engine:test --tests "*ConfigLoader*" --tests "*SpecParser*" --tests "*StaleWindow*"` green (폴백: mobile_daily × intraday_30m → 30h 단언) |
| **05a2** ★신설 | **`pit/Visibility.kt` + `TradingDayGrid` + `pit/KnownSeries.kt`(Stage 3) + `RawSeriesSource` 계약** — §2.8·§2.11 이식 (03a·06b의 선행) | kotlin-implementer | `--tests "*Visibility*" --tests "*KnownSeries*"` green — **증인 W-V1~W-V4 · W-K1** 포함 |
| **05b2** ★라운드4 | **`engine/IndicatorRuntime.kt`** — 15지표 Stage 1~3 조립(정본 `_build_*` 1:1), 워밍업 충분성 테스트(W-W1) | kotlin-implementer | `--tests "*IndicatorRuntime*"` green — 지뢰 6(2계열 정렬 2종)·W-L5(창 충족) 포함 |
| **05b** [P] | transforms 12종 + **L1 파리티** | kotlin-implementer | `--tests "*TransformParity*"` green(원소별 1e-9, NaN 위치 일치) |
| **05c** [P] | scoring + modifiers | kotlin-implementer | `--tests "*Scoring*" --tests "*Modifier*"` green(경계 등호·결측·abs·extreme 격리) |
| **05d** [P] | statemachine (D-25 §1~4 + D-26) | kotlin-implementer | `--tests "*StateMachine*"` green — **상수 입력 한계진동 부재**(D-26 회귀) 증인 포함 |
| **05e** [P] | **Python** 파리티 벡터 생성기 + 결정론 테스트 (05b~d와 동시 진행) | python-implementer | `uv run python backtest/gen_parity_vectors.py && uv run pytest backtest/test_parity_vectors.py -q` green |
| **05f** | L2 파이프라인 파리티 + **`visibleAt` 동일성 단언**(§2.8) + L3 end-to-end 1창 + L0 골든 대조 + 결과 분석 | kotlin-implementer → **backtest-analyst**(분석·리포트) | `./gradlew :engine:test --tests "*Parity*"` green · 리포트에 `max\|Δcomposite\|` 실측치·타임라인 diff 0·**visible_at 불일치 0건** 기재 |

**증인 테스트 의무**(REVIEW_M0 규율 ①): 05b·05f의 각 파리티 단언마다 **퇴화 입력 증인**을 붙인다 —
기대값을 일부러 어긋나게 넣었을 때 그 단언이 **실제로 실패함**을 증명하는 테스트. 없으면 "항상 통과하는 단언"을
파리티 통과로 오독할 수 있다(M0에서 실제 발생한 반려 사유).

K-xx: **K-07**, **K-05**, **K-11**(transform은 인과적 — 미래 원소 미참조 증인).

### MT1-06 일일 확정 틱 + 캐치업 (브리프 §5-10의 답)

**AD-A8 상세.** `PeriodicWorkRequest`는 반복 주기만 주고 시각을 못 준다(K-14와 겹쳐 시각 통제 불가).
따라서:

```
enqueueUniqueWork("confirm_tick", ExistingWorkPolicy.KEEP,
    OneTimeWorkRequestBuilder<ConfirmTickWorker>()
        .setInitialDelay(nextConfirmInstant(now) - now)      // 다음 17:00 KST 거래일
        .setConstraints(NetworkType.CONNECTED)
        .setBackoffCriteria(EXPONENTIAL, ...)
        .build())
```
- 워커는 실행 말미에 **자기 자신을 다음 확정 시각으로 재예약**한다. 재예약 실패 시 앱 시작 시점에 복구.
- `KEEP` 정책이 **이중 예약**을 막고, 실행 이력 테이블의 PK가 **이중 커밋**을 막는다(아래).
- 앱 시작 시 무조건 `ensureScheduled()` 호출(멱등) — OEM 절전(K-15)에 죽어도 다음 실행 때 되살아난다.

**국면 산출 = 전량 fold** (§2.10 — 이 서브태스크의 핵심 계약):
확정 틱은 `tick_input` 전 시퀀스 + 오늘 틱을 `StateMachine.run(...)`에 **통째로** 넘겨 타임라인을 재산출하고,
마지막 원소를 오늘 국면으로 삼는다. 상태기계 카운터를 저장하지 않는다(엔진 API에 주입 경로가 없다).

**멱등·이중 실행 방지** — 두 층:

```
tick_run                                 -- 실행 메타데이터(재시도 있음). 정본 아님
  trading_date TEXT PRIMARY KEY          -- 하루 1커밋의 물리 강제
  profile TEXT, started_at INTEGER, finished_at INTEGER,
  status TEXT,                           -- RUNNING / OK / PARTIAL / FAILED / SKIPPED_HOLIDAY
  phase_before TEXT, phase_after TEXT,   -- ★ fold 산출의 파생 캐시(감사·표시용)
  composite REAL, coverage REAL, missing_indicators TEXT, error TEXT
```
- 시작 시 `INSERT`(충돌 시 중단) → 같은 날짜 재실행이 **DB 레벨에서** 차단된다(1층).
- **2층(결정성)**: 설령 재실행되더라도 fold는 동결 입력의 순수 함수라 **동일 타임라인**이 나온다(W-S2).
  PK 차단만으로는 "이중 실행이 상태를 오염시키지 않음"(AAA §2.2)이 증명되지 않는다 — 이 2층이 증명한다.
- 실패로 끝난 `tick_run` 행은 재시도 허용(상태 전이만). `tick_input`은 **성공 시에만** 1행 동결된다.
- 이 테이블이 "실행 이력 화면"(TASK 요구)과 K-15 "틱 누락 노출"의 데이터 소스를 겸한다.

**캐치업 설계** (반려 A-2·A-7·A-8 반영 재설계):

1. 마지막 `status=OK` 거래일 ~ 오늘 사이의 **누락 거래일 목록** 산출.
2. **거래일 판정**: 1차 = `:krx` `getBusinessDays`(실측 API), 실패 시 2차 = **경험적 판정**
   ("그 날짜에 KRX 계열 관측이 **성공 응답으로** 존재하면 거래일") — `run_replay.trading_days`와
   **동일 원칙**이라 하니스·프로덕션의 달력 의미가 갈리지 않는다. `exchange_calendars` 상당물을
   앱에 이식하지 않는다(M0에서 XKRX 달력이 2026 임시 휴장을 못 따라가 유령 결측을 만든 교훈).
   **"성공 응답으로"가 핵심**이다 — 파싱 실패의 빈 리스트를 관측 부재로 세면 그 날이 영구 휴장으로
   굳는다(A-8의 되먹임). 실패는 `EmptyOnTradingDay`로 별도 기록되고 달력에 영향을 주지 않는다.
3. **캐치업 틱의 `evaluatedAt` 정의 (반려 A-2 해소 — 이 값이 계약이다)**:
   > **거래일 D의 캐치업 틱은 `evaluatedAt = D의 확정 틱 시각(17:00 KST, C-1의 SSOT 값)`을 쓴다.
   > `now`를 쓰지 않는다.**

   그리고 원장 조회 필터는 `observed_at`이 아니라 **파생 `visible_at`**이다(§2.8·MT1-03a).
   이 둘의 조합으로 라운드 1의 모순이 사라진다:
   - 오늘 수집한 D-1일 KRX 행 → `visible_at = D-1 17:00 ≤ D 17:00` → **보인다**(캐치업 성립).
   - `as_of = D+1` 행, `^VIX as_of = D` 행 → `visible_at > D 17:00` → **안 보인다**(K-11 유지).
   - `observed_at`이 판정에 개입하지 않으므로 "언제 수집했는가"가 국면을 바꾸지 못한다 —
     같은 원장 상태에서 캐치업을 몇 번 돌려도 같은 타임라인이 나온다(**멱등의 진짜 근거**).
4. 누락 구간을 **range 수집** → Room append(멱등) → 날짜별로 **순차** 평가·커밋.
   상태기계는 순서 의존이므로 병렬 금지.
5. **상한** — 라운드 1의 `30`은 근거 없이 적은 값이므로 **철회**한다(반려 A-7).
   재설정: **잠정 20 거래일(≈1개월)**, 확정은 MT1-00g의 range 조회 상한 실측 후. 도출 근거 3개:
   - **(하한)** 현실적 최장 오프라인 구간(연휴·출장 등) ≈ 10 거래일 + 여유 2배 → **≥ 20**.
   - **(상한 ①)** transform이 요구하는 롤링 창은 252 거래일이다. 공백이 **원장 보유 이력을 넘으면**
     캐치업이 아니라 **재구축**이 정답이다 → 상한은 252보다 훨씬 작아야 의미가 있다.
   - **(상한 ②)** 20 거래일을 넘는 공백의 국면 타임라인은 **회고적 가치만** 있다(사용자가 필요한 것은
     오늘의 국면이다). **(라운드 3 정정 — 반려 A-9)** 라운드 2의 "직전 확정 국면 + 오늘 1틱만 계산"은
     현행 엔진 API로 **표현 불가**하므로 철회한다(`run()`은 항상 `initial_phase`에서 시작).
     대체: 상한 초과 구간의 거래일을 **`composite = NULL`, `gap_reason = UNRECONSTRUCTABLE_GAP`으로 동결**한다.
     D-25 §3이 그 틱에서 국면·스트릭·dwell·cooldown을 **동결**하므로, fold가 공백을 통과해도
     국면이 공백 이전 값 그대로 이어진다 — **엔진 변경 0**(§2.10 (c)). UI "원장 공백" 배지 +
     실행 이력 기록으로 사실을 노출한다. 조용히 버리지 않는다.
   - **(비근거 명시)** KRX rate limit(K-03)은 상한의 근거가 **아니다** — 수집이 range 호출이라
     호출 수가 공백 일수에 비례하지 않는다. 라운드 1이 이것을 근거로 든 것은 오류였다.
   값의 SSOT 이관은 §11 **C-5**, 최종 수치 확정은 §10 **U-11**.
6. **캐치업 중 노티 억제**: 2일 이상 소급 커밋에서는 각 날짜의 전이 노티를 발신하지 않고
   **최종 상태 1건만** 알린다(과거 경보 폭주 차단). 억제된 전이는 실행 이력에 전부 남는다.

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **06a** | 확정 틱 워커(자기 재예약·유니크 워크·부분 실패 흡수·**전량 fold 국면 산출**·`tick_input` 동결·노티 트리거) | kotlin-implementer (**03c·05d 선행**) | `./gradlew :app:testDebugUnitTest --tests "*ConfirmTick*"` green (work-testing) — **증인 W-S1~W-S4 전건**(§2.10) |
| **06b** | 캐치업(누락 산출·`evaluatedAt=D 17:00`·**날짜별 `tick_input` 동결 후 fold**·상한 초과분 `NULL` 동결·노티 억제) | kotlin-implementer | `--tests "*Catchup*"` green — **중단 후 3일 캐치업(W-L1 상황에서 결측 0)**·**이중 실행**·**휴장일 스킵**·**동일 원장 2회 캐치업 → 비트 동일 타임라인**·**상한 초과 시 `NULL` 동결 + 국면 보존(W-S3)** |
| **06c** | 실행 이력 테이블·조회 API | kotlin-implementer | `--tests "*TickRun*"` green(동일 날짜 재삽입 차단 증인) |

K-xx: **K-14**(자기 재예약·지연 허용), **K-15**(누락 이력 노출), **K-06**(크론은 KST 고정, 데이터는 as-of 정렬), **K-11**.

### MT1-07 프리뷰 (D-17·D-23) (브리프 §5-11의 답)

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **07a** | 타입 분리(`ConfirmInputs`/`PreviewInputs`/`PreviewResult`) + **프리뷰 적재 `lane=1`·`observed_at=now`**(D-17 §3) + carry-forward(**`tick_input.lastCommittedSeverities()`** = severity 직결 이월, 깊이 1, 이월값 Room 미기록) + **프리뷰 `evaluatedAt = now`**(§2.12 (c)) | kotlin-implementer (**03c 선행**) | `./gradlew :engine:test --tests "*PreviewTypes*"` + `:app:testDebugUnitTest --tests "*CarryForward*"` green — **이월값이 Room에 쓰이지 않음** + **증인 W-P2·W-P4·W-P6·W-P7** 포함 |
| **07b** | coverage(raw) 산출 · **`engine.preview_policy.min_coverage`(C-9) 로드** 기반 억제 · D-23 재현 | kotlin-implementer (**C-9 승인 선행**) | `--tests "*Coverage*"` green — ③(raw 67.7% 산출·억제)·④(66.7 vs 45.2, 분모 정의 대비)·④-b(이월 시 45.2 도달) 각각 독립 테스트. **임계 리터럴 0 확인**(detekt MagicNumber) |
| **07c** | 아키텍처 테스트(Konsist **AT-1~AT-8**) | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*Architecture*"` green + **위반 코드를 임시 삽입하면 실패**하는 증인 |

**억제 임계 0.80의 출처** (반려 A-3 해소): 이 숫자는 **현재 SSOT 어디에도 없다.** D-23 §23.3-3 본문에만
있고 `configs/*.yaml`에 키가 없어, 그대로 구현하면 착수 즉시 CLAUDE.md §1(임계 하드코딩 금지) 위반이다.
→ §11 **C-9**로 `configs/indicators.yaml` `engine.preview_policy.min_coverage: 0.80` 신설을 제안한다.
**C-9 승인 전에는 MT1-07b에 착수하지 않는다**(선행 의존).

**계획이 반드시 해소해야 하는 모호성 (→ §10 U-2 상신)**: D-23 §23.3은 "coverage < 80%면 억제"라고만 말하는데,
carry-forward는 분모를 유지하므로 이월 후 coverage는 회복된다. 이월 후 수치로 억제를 판정하면 억제가 거의 걸리지 않아
규율이 무력화되고, TASK 완료기준 ③의 "67.7% 산출"도 재현되지 않는다.
→ **권고: `coverage`는 "이월 제외, 실제 신규 관측 기준"(raw)으로 정의**하고 억제 판정에 쓴다.
이월값은 composite 계산에는 반영(방향 왜곡 차단)하되 coverage에는 계상하지 않는다.

**테스트 ③과 ④의 정의** (라운드 1의 ④ 서술은 D-23 §23.2 원문을 오독했다 — 반려 A-5, 아래로 정정):

> **정정.** D-23 §23.2의 **45.2는 "이월 후" 값이 아니라 "서버 동시각" 값**이다:
> `100×(21.0×2 + 10.0×0)/(31.0×3) = 45.2` — **전체 분모 31.0을 유지**하고 결측이 아닌
> KR 지표를 severity 0으로 계상한 수다. 66.7은 `100×(21.0×2)/(21.0×3)` — **결측 제외 분모 21.0**.
> 즉 §23.2가 보이는 것은 **"분모가 줄어서 생기는 한 단계 과대평가"**이며, 두 수의 대비가 그 병리다.

- **④는 순수 계산 벡터 테스트**(`:engine`) — §23.2의 severity·weight 벡터를 그대로 넣어
  **(i) 결측 제외 분모 → 66.7**, **(ii) 전체 분모 + KR severity 0 → 45.2** 두 수를 산술 재현한다.
  두 수의 차(21.5)가 **분모 정의만으로 생긴 방향성 오류의 크기**임을 단언한다.
- **④-b (파생 관찰, 별도 단언)**: carry-forward가 이월한 직전 확정값의 severity가 0이면
  프리뷰 composite가 (ii)와 **같은 45.2**에 도달한다 — 즉 이월이 이 병리의 해법으로 작동함을 보인다.
  이것은 §23.2 원문의 주장이 아니라 §23.3-1의 규율이 만드는 **귀결**이므로 ④와 분리해 단언한다.
  (이월값의 severity가 0이 아닌 일반 케이스는 45.2와 다른 값이 나오는 것이 정상이다.)
- **③은 앱 시나리오 테스트**(`:app`) — 실제 KR 4지표 결측 상황에서 **raw coverage 21.0/31.0 = 67.7%** 산출,
  `min_coverage`(C-9) 미달 판정, "국면 판정 불가" 표기, 잠정 경보 미발신을 검증한다.
  (실전에서는 CDS가 이월 소스조차 없어(G-4) 유효 분모가 §23.2 예시와 다를 수 있다 — 그래서 ④를 벡터로 고정한다.)

K-xx: **K-11**(이월값은 원장에 쓰지 않는다 — 원장 오염 = 미래 리플레이 오염).

### MT1-08 로컬 노티·기본 화면(기능판) (브리프 §5-12의 답)

**M1 / M2 경계** — 명시적으로 긋는다:

| 항목 | M1(기능 검증) | M2(디자인) |
|---|---|---|
| 노티 | 채널 3종 등록 + 트리거 로직 + 문구 뼈대 | 문구 다듬기·리치 노티·액션 버튼 |
| 홈 | Material3 기본 컴포넌트로 국면·composite·상위 3지표·마지막 틱 시각·coverage 배지 | 디자인 토큰·국면 팔레트·모션·차트·위젯 |
| 판정 | AAA **§2.2**(실패 경로·조용한 실패 금지)만 적용 | **§2.4·§2.5** 적용 |
| 위임 | **kotlin-implementer** | **ui-craftsman** |

M1 UI는 M2에서 전면 교체될 것이 확정돼 있으므로 **투자를 최소화**한다. 다만 §2.2는 M1에도 걸린다 —
빈 화면·무한 스피너·조용한 실패는 M1에서도 FAIL이다.

| ID | 내용 | 위임 | 완료 기준(명령) |
|---|---|---|---|
| **08a** | 노티 채널 3종(국면 전이 / 잠정 경보 / 틱 실패) + 트리거 | kotlin-implementer | `--tests "*Notification*"` green — 억제 상태(coverage<80%)에서 잠정 경보 **미발신** 단언 포함 |
| **08b** | 기능판 홈 + 실행 이력 화면 + 설정(키 입력) 화면 | kotlin-implementer | `--tests "*HomeState*"` green(로딩·오류·빈 상태 3분기 존재 단언) |
| **08c** | 온보딩 최소 — API 키 입력(Keystore/EncryptedSharedPreferences), 절전 예외 안내 | kotlin-implementer | `--tests "*KeyStore*"` green — **키가 로그·백업에 남지 않음** 단언(`android:allowBackup` 정책 포함) |

K-xx: **K-15**(절전 예외 안내), **K-17**(키 보안).

### MT1-09 실기기 스모크 절차서 + 진단 내보내기 (신설) (브리프 §5-13의 답)

Advisor·Worker는 실기기에 접근할 수 없다. 따라서 **사용자가 수행할 절차**와 **기계가 수집할 증빙**을
분리해 설계해야 GM1이 성립한다.

| ID | 내용 | 위임 | 완료 기준 |
|---|---|---|---|
| **09a** | `docs/runbooks/M1_SMOKE.md` — 설치·키 입력·절전 예외 등록·17:00 확정 틱 관측·프리뷰 3회·증빙 수집까지 사용자 체크리스트 | kotlin-implementer(문서) | 절차서에 각 단계의 **기대 관측**과 **실패 시 조치**가 있고, 사용자가 한 번에 따라갈 수 있음(aaa-critic 판정) |
| **09b** | 앱 내 **진단 내보내기** — 실행 이력 + 마지막 틱 요약 + 설정 상태(키 존재 여부만, 값 제외) JSON 파일 공유 | kotlin-implementer | `--tests "*Diagnostics*"` green — **키 값이 산출물에 포함되지 않음** 단언(K-17) |

**GM1 증빙 수집 방법**: 사용자가 스모크 수행 후 진단 JSON 1개 + 홈 화면 스크린샷을 제출 →
Advisor가 `docs/gates/GATE_GM1.md`에 인용. 로그 수집을 `adb logcat`에 의존시키지 않는다
(사용자 부담·PII 위험). 앱이 자기 증빙을 만드는 쪽이 재현 가능하고 안전하다.

---

## 4. 의존성 그래프와 병렬 웨이브

```
        ┌──────────────────────── MT1-00 실측 (00a·00b·00c·00d·00e·00f 전부 병렬) ─────────┐
        │  00e ─┐                                                                          │
        └───────┼──────────────────────────────────────────────────────────────────────────┘
                ▼
W1   MT1-01a 스캐폴드·카탈로그
                │
        ┌───────┼───────────────┬────────────────┐
        ▼       ▼               ▼                ▼
W2   01b 정적분석  01c syncConfigs   01d :krx 벤더링(←00c)   05e 파리티 벡터 생성기(Python, 독립)
        │       │               │                │
        └───┬───┴───────┬───────┘                │
            ▼           ▼                        │
W3      02a/02b 계약   05a 설정 로더·파서(←01c) ─▶ 05a2 Visibility(§2.8)
        (02a는 Python, 01과 독립 — W1과 동시 착수 가능)      │
                                    ┌─────────────────────────┘
                                    ▼
W3.5                    03a/03b/03c Room  (★ 05a2 선행 — as-of 리졸버가 visibleAt을 쓴다.
                                            03c tick_input = §2.10 fold의 정본, 06a 선행)
            │           │                        │
            │           │              ┌─────────┼─────────┐
            │           │              ▼         ▼         ▼
W4          │           │           05b transforms  05c scoring  05d statemachine   (3자 병렬)
                                          └─▶ 05b2 IndicatorRuntime(Stage 1~3 조립, ←03a·05b)
            │           │              └─────────┬─────────┘
            │           │                        ▼
W5          │       04g 오케스트레이터(←03a)   05f BT-05 파리티(←05b·c·d·05e)
            │           │
            │   ┌───────┼───────┬───────┬───────┬───────┐
            │   ▼       ▼       ▼       ▼       ▼       ▼
W6          │  04a    04b     04c     04d     04e     04f      (6자 병렬, 각 실측 의존)
            │   └───────┴───────┴───┬───┴───────┴───────┘
            ▼                       ▼
W7                        MT1-06a/b/c 확정 틱·캐치업 (←03·04·05)
                                    │
                        ┌───────────┴───────────┐
                        ▼                       ▼
W8               MT1-07a/b/c 프리뷰        MT1-08a/b/c 노티·홈     (병렬)
                        └───────────┬───────────┘
                                    ▼
W9                          MT1-09a/b 스모크·진단
                                    ▼
                            GM1 게이트 리포트
```

**병렬 위임 묶음 (한 메시지 다중 Task 호출 단위)**

| 웨이브 | 동시 위임 | 인원 |
|---|---|---|
| W0 | 00a·00b·00c·00d·00f·**00g** (data-verifier ×6) + 00e (kotlin-implementer) + **02a** (python-implementer) + **05e** (python-implementer) | 9 |
| W2 | 01b · 01c · 01d | 3 |
| W3 | 02b · 05a → 05a2 | 2 |
| W3.5 | 03a · 03b · 03c | 3 |
| W4 | 05b · 05c · 05d | 3 |
| W4.5 | 05b2 (IndicatorRuntime — 05b·03a 합류) | 1 |
| W6 | 04a · 04b · 04c · 04d · 04e | 5 |
| W8 | 07a+07b+07c · 08a+08b+08c | 2 |

02a(Python 계약 스냅샷)와 05e(파리티 벡터 생성기)는 **Android 스캐폴드와 완전히 독립**이므로
가장 먼저 착수해 Kotlin 측 대기 시간을 없앤다. 이것이 이 계획의 임계 경로 단축 지점이다.

**임계 경로**: `00e → 01a → 01c → 05a → 05a2 → 03a → 06b`(원장·캐치업) 와
`05a → 05b/c/d → 05f`(파리티), `00c → 01d → 04c → 06`. 세 경로가 W7에서 합류한다.
따라서 **00c·00e가 지연되면 전체가 지연된다** — W0 최우선.
**05a2(Visibility)가 03a의 신규 선행**이 된 것이 라운드 2의 구조 변경이다(§2.8) — as-of 리졸버가
`visibleAt`을 쓰므로 Room보다 먼저 존재해야 한다.

**00g는 임계 경로가 아니지만 마감이 있다**: 결과가 `MT1-06a` 착수 전에 나와야 확정 시각이 고정된다.
연속 3거래일 폴링이 필요하므로 **W0에서 반드시 시작**한다(뒤로 미루면 그때부터 3일이 더 든다).

---

## 5. 실측 선행 과업 — 무엇이 무엇을 블록하는가

| 실측 | 확정해야 하는 사실 | 블록 대상 | 실패 시 대체 경로 |
|---|---|---|---|
| **00e** 툴체인 | AGP/Kotlin/Gradle 호환 조합, snakeyaml-engine Android 호환 | **MT1-01 전부 → 사실상 전 계획** | 호환 조합을 못 찾으면 Kotlin 버전을 낮춰 `:krx` 소스를 조정(최후) |
| **00c** kotlin_krx | 로그인 필요 여부 · VKOSPI 가부 · **거래대금 필드 존재** · 순매수 단위 | 01d·04c·**U-3** | 거래대금 부재 시 `kospi_volume_distribution`(가중 1.5)이 상시 결측 → 분모 제외로 무해하나 kr_flow_price 발화 표면 축소를 GATE_GM1에 기록 |
| **00b** ECOS | `721Y001` item_code 2종 (K-04, **M0 미해결 잔존**) | 04d | 확정 실패 시 `krx_credit_spread_delta` 상시 결측 → credit 축이 hy_oas 단독이 됨. **G-4와 겹치면 credit 축이 1지표로 축소**되어 `distinct_axes` 충족이 크게 불리 → GATE_GM1 필수 기록 |
| **00a** 야후·Stooq | 엔드포인트 형태·crumb·Stooq 심볼 매핑 가능 여부 | 04a | 폴백 불가 심볼은 "폴백 없음"을 사실로 기록하고 결측 배지로 처리(K-18) |
| **00d** CDS | 정적 추출 가능성 | 04f·**U-4** | 기본 권고 = (b) 미수집 확정 |
| **00g** 확정 시각 | 17:00에 지수 종가·투자자 순매수·VKOSPI·KRW=X가 **실제로 확정되는가** + range 조회 상한 | **AD-A7·§9·C-1·C-6·MT1-06a·U-11** | 17:00 이후 확정 항목이 있으면 확정 시각 상향 + 골든 재확인(판정 규칙은 MT1-00g 본문에 사전 고정) |
| **00f** KIS | 인증 방식·엔드포인트 | 04e | 실패 시 어댑터는 비활성 상태로 남고 프리뷰는 야후·KRX만 사용(sources.yaml `enabled:false`와 정합) |

**규율**: 실측 없이 "될 것이다"로 구현을 시작하지 않는다. 실측 결과는 재현 절차와 함께 저널에 남기고,
sources.yaml 반영이 필요하면 §11의 변경 제안으로 상신한다(Worker의 직접 수정 금지).

---

## 6. 관점 A가 특히 경계하는 실패 모드

1. **"일단 되게" 빌드 파일.** 문자열 좌표 하드코딩·`mavenLocal()`·`+` 버전이 한 줄만 들어가도 재현성이 무너지고,
   6개월 뒤 GM3 회귀에서 다른 결과가 나온다. → `failOnDynamicVersions()` 두 줄로 기계 강제(§2.2).
2. **assets 사본의 부활.** "테스트가 느려서" 수동 복사를 되살리는 회귀. → `verifyNoCheckedInAssets`(§2.4).
3. **엔진에 스며드는 Android.** 로그 한 줄 때문에 `android.util.Log`를 import하면 파리티 테스트가
   Robolectric에 묶인다. → 모듈 분리 + AT-3.
4. **파리티 경계의 조용한 후퇴.** "이 부분은 어차피 같으니까" 하며 비교 범위를 줄이는 것. → L1·L2·L3 3층과
   퇴화 입력 증인 테스트 의무(§2.7, §3 MT1-05).
5. **carry-forward의 확정 경로 누출.** 리팩터링 한 번에 규약이 깨진다. → 타입 강제(AD-A5)가 1차, 테스트가 2차.
6. **`:krx` 포크의 조용한 수정.** PROVENANCE에 안 적고 고치면 재수입 때 소실된다. → `verifyKrxProvenance`.
7. **시간 필터를 "언제 수집했는가"로 잡는 것.** 직관적이지만 캐치업을 죽이고 look-ahead는 못 막는다
   (라운드 1이 실제로 저지른 오류). 판정은 언제나 **"그때 알 수 있었는가"**(`visible_at`)로 한다 →
   §2.8 + AT-7·AT-8 + 증인 W-L1~W-L4.
10. **사고를 대체값으로 덮는 것.** 확정 수집이 실패했을 때 프리뷰 값으로 "채우는" 설계는 시스템이
    조용히 틀린 값을 확신하게 만들고, append-only에서는 **불가역**이다. 없으면 없다고 두면
    이미 있는 결측 경로(D-02 분모 제외 / D-25 §3 동결)가 정직하게 처리한다 → §2.12 하드 필터.
9. **입력 "한 값"과 입력 "한 계열"을 혼동하는 것.** engine_ref의 transform은 전부 계열→계열이고
   rolling은 `min_periods=window`다. 조회 계약을 "최신 1행"으로 잡으면 정적으로는 컴파일되고
   테스트도 통과할 수 있지만(픽스처가 짧으면 양쪽 다 NaN) **프로덕션에서만 전 지표가 사라진다.**
   → §2.11 + 증인 W-L5(창을 실제로 채우는지 단언).
8. **순수 함수의 "프로덕션 호출 모델"을 규정하지 않는 것.** `engine_ref`의 함수들은 상태가 없으므로
   **누가 어떤 입력을 모아 부르는가**가 곧 시스템 거동이다. 파리티는 함수를 증명할 뿐 호출 모델을
   증명하지 않는다 — 라운드 1(`visible_at` 산출처 부재)과 라운드 2(상태기계 호출·지속 모델 부재)가
   **같은 유형의 결함**이었다. → **일반 규율**: `:engine`의 공개 함수마다 "프로덕션에서 누가·무엇을 모아·
   언제 호출하는가"를 계획이 한 문단으로 명시하고, 그 호출 모델을 깨면 실패하는 증인 테스트를 둔다
   (W-V1~V4 / W-L1~L4 / W-S1~S4가 각각 그것이다).

---

## 7. 리스크 × K-xx 매핑과 완화

| ID | 리스크 | K-xx | 영향 | 완화 (계획 내 위치) |
|---|---|---|---|---|
| **R-01** | 야후 REST 변경·차단 | K-01·K-18 | vol_global·global_price·rates_fx 다수 결측 | Stooq 폴백(04a) + 지표별 결측 흡수(04g) + 결측 배지. 폴백 불가 심볼은 실측으로 사실 확정(00a) |
| **R-02** | KRX 로그인 정책 변경·세션 만료·차단 | K-03 | kr_flow_price 축 전체 결측 | 04c 세션 재로그인 경로 + rate limit ≥1s + 휴장 사전 스킵 + 자격증명 Keystore(K-17). 실패는 결측으로 흡수(전체 틱 실패 금지) |
| **R-03** | assets 드리프트 | K-16 | 앱이 다른 임계로 판정 — **가장 조용한 사고** | 생성 assets(AD-A3) + 2층 해시(AD-A4) + 수동 사본 금지 가드 |
| **R-04** | Kotlin/Python 계산 불일치 | K-07 | 국면 판정 자체가 무의미해짐 | 3층 파리티 + 지뢰 3종 선제 명시(§2.7) + 증인 테스트 |
| **R-05** | WorkManager 비정시·OEM 절전 킬 | K-14·K-15 | 확정 틱 누락 | 자기 재예약 + 앱 시작 시 `ensureScheduled` + 캐치업 + 이력 노출 + 온보딩 안내 |
| **R-06** | 캐치업이 look-ahead를 유발 | K-11 | 리플레이·백테스트 신뢰 붕괴 | as-of 리졸버의 **`visible_at <= evaluatedAt`** 필터 + `evaluatedAt = D 17:00` 고정 + 증인 W-L1~W-L4(§3 MT1-03a) |
| **R-13** ★ | **`visible_at` 산출 규칙이 하니스와 어긋난다** — 계열 kind 오분류, `after` vs `on_or_after` 혼동, 2계열 max 누락, 확정 시각 불일치 | K-11·K-05 | 스테일·가시성이 통째로 어긋나 프로덕션과 파리티가 다른 세계에서 돈다. **가장 조용한 사고 2호** | §2.8 규칙표 + 증인 W-V1~W-V3 + **L2 파리티의 `visibleAt` 동일성 단언**(Python 산출값과 대조) + AT-7 |
| **R-20** ★ | **프리뷰 적재가 확정 원장을 오염** — 확정 수집 실패일에 장중 부분봉이 종가로 동결 | K-11·K-03 | append-only + `tick_input` 동결이라 **불가역**, 252일 워밍업 창에 영구 잔류해 이후 전 z-score 오염 | §2.12 (b-0) 전수표 + `lane` 하드 필터 + AT-10 + **증인 W-P1**(필터 제거 시 실패). "확정 우선 정렬"은 실패일에 폴백하므로 비채택 |
| **R-21** ★ | **carry-forward의 계층 단절** — 원계열 관측값을 severity 자리에 투입 | K-07 | 원 VIX≈15를 z 임계 `{1.5,2.0,3.0}`에 넣으면 **z-score 6지표가 상시 severity 3** → 프리뷰가 항구적 고국면을 표시 | ③의 원천을 `tick_input.severities_json`으로 전환(반환이 곧 severity) + **증인 W-P2·W-P7** + (b-0) 표의 "반환 계층" 열이 계층 혼동을 표에서 차단 |
| **R-18** ★ | **파이프라인 순서 역전** — 가시성을 원관측에 붙이고 부분 계열을 변환 | K-11·K-07 | 롤링 창 절단·`min_periods` 미달로 **거의 모든 지표가 NaN → 전 지표 결측 → 국면 영구 GREEN**. 라운드 2~3 설계에 실재했던 결함 | §2.11 3단계 계약 + **증인 W-L5**(범위 조회를 최신 1행으로 되돌리면 실패) + L3 end-to-end 파리티 |
| **R-19** ★ | `lookup`을 `bisectLeft`/`<`로 구현 | K-07 | mobile에서 KRX·FX 가시 시각이 **확정 틱 시각과 정확히 같으므로**, 등호를 빼면 KRX 4지표 + KRW=X가 **매 틱 통째로 소실** | §2.11 지뢰 7 + **증인 W-K1** + L2의 `row_date` 일치 단언 |
| **R-15** ★ | **국면 연속성 소실** — 확정 틱이 `run([오늘틱])`을 부르면 매일 GREEN 재시작. 라운드 2 계획이 호출 모델을 규정하지 않아 실재했던 결함 | K-11 | 경보 시스템의 존재 이유가 사라진다(전이가 영원히 안 일어남) | §2.10 전량 fold + `tick_input` 4필드 동결 + **증인 W-S1**(단일 틱 호출로 바꾸면 실패) + AT-9 |
| **R-16** ★ | 프로파일 파라미터 변경 시 fold가 **과거 타임라인을 재산출**해 국면 이력이 바뀐 것처럼 보인다 | — | 사용자 혼란(데이터 손상은 아님) | `tick_input.registry_version` 기록 + 변경 감지 시 실행 이력에 "레지스트리 변경으로 타임라인 재산출" 항목. 증분 저장(안 I)은 구·신 파라미터를 **조용히 섞으므로** 더 나쁘다(§2.10) |
| **R-17** ★ | stale 등호를 `>=`로 구현 | K-07 | 경계 틱에서 지표 통째 소실 → composite·distinct_axes 어긋남 | §2.8 등호 규약 명문화 + **증인 W-V4**(정확 일치=유효 / +1ms=결측 양방향) |
| **R-14** ★ | kotlin_krx 빈 리스트가 파싱 실패와 휴장을 뭉갠다 | K-03 | 조용한 실패가 "휴장"으로 굳어 캐치업이 영영 되돌아오지 않는다 | 04c 흡수 규율 3조(사전 휴장 판정 / `EmptyOnTradingDay` 분류 / 경험적 달력은 성공 응답만) |
| **R-07** | **프로덕션 분모 ≠ 백테스트 분모** — ECOS 수집 성공 시 coverage·composite 기준선이 보정된 적 없는 값이 됨 | — | 국면 임계의 실효 의미가 바뀜 | GATE_GM1에 명시 기록 + **C1 재보정 대상에 이관**(§10 U-6) |
| **R-08** | VKOSPI 입력 이원화(모바일 실측치 vs 서버 폴백) | K-02 | D-23 §23.5 "동일 SSOT" 보장 약화, INT 일치율 ≥90% 위협 | **§10 U-3 권고: 수집만 하고 판정 입력은 통일** |
| **R-09** | `:krx` 포크 드리프트 | — | 업스트림 수정 유실 | PROVENANCE + 매니페스트 + 재수입 절차서(01d) |
| **R-10** | 계측 테스트가 CI에 없어 패키징 회귀 지연 발견 | K-16 | APK만 틀린 상태 | L-A(JVM)가 상시 방어, L-B는 GM1·릴리스 시점 필수 실행으로 절차 고정(§14) |
| **R-11** | cp949 콘솔로 산출물·로그 판독 실패 | — | 절차 사고(검증 불가) | 인코딩 규율(§2.2) + 테스트 출력 UTF-8 강제 |
| **R-12** | API 키·KRX 자격증명 유출(로그·백업·진단 파일) | K-17 | 계정 침해 | Keystore/EncryptedSharedPreferences + `allowBackup` 정책 + 진단 산출물 키 제외 단언(09b) |

---

## 8. 브리프 §5 13문 답변 색인

| # | 질문 | 답변 위치 |
|---|---|---|
| 1 | Gradle 구성·모듈·카탈로그·소스셋·`check` 포함 범위 | §2.1, §2.2 (표) |
| 2 | kotlin_krx 통합 방식과 로그인 정책 대응 절차 | §2.3 (AD-A2), MT1-00c, §10 U-1 |
| 3 | syncConfigs 대상·해시 테스트 소스셋·드리프트 차단 | §2.4 (AD-A3·AD-A4) |
| 4 | contracts 미러: 위치·형식·생성기·왕복·동결 일치 | §3 MT1-02 (동결 일치 3정의 + 함정 5) |
| 5 | Room append-only: 스키마·물리 강제·as-of·CSV/Drive | §3 MT1-03 (+ **§2.8** visible_at 필터) |
| 6 | collectors 6건: 실측·폴백·결측·픽스처·병렬 그래프 | §3 MT1-04, §4, §5 |
| 7 | Kotlin 엔진 모듈 대응표·D-26 범위·Double/KST 규율 | §3 MT1-05 (대응표) |
| 8 | BT-05 실행 형태·픽스처 주입·검증 코드 위치 | §2.7 (AD-A6, 3층 + 지뢰 3) |
| 9 | 확정 틱 시각 결정 논증과 스케줄 정합 | §9 |
| 10 | 캐치업 멱등·이중 실행 방지·실행 이력 | §3 MT1-06 (AD-A8) |
| 11 | 프리뷰 carry-forward 격리·coverage 위치·억제 UX | §2.6 (AD-A5), §3 MT1-07, §10 U-2 |
| 12 | 노티 3채널·홈의 M1/M2 경계 | §3 MT1-08 (경계표) |
| 13 | 실기기 스모크 절차와 GM1 증빙 | §3 MT1-09 |
| ★ | **스테일 기준 시각(visible_at)의 프로덕션 산출** | **§2.8** (AD-A10) |
| ★ | **커버리지 측정·강제 배선** | **§2.9** |
| ★ | **국면 지속 모델(프로덕션 상태기계 호출·상태 보존)** | **§2.10** (AD-A11) |
| ★ | **transform 입력 시계열 조회 계약(원계열 범위·워밍업·가시성 순서)** | **§2.11** (AD-A12) |
| ★ | **레인 분리(프리뷰 적재 격리) · 프리뷰 `evaluated_at` 정의** | **§2.12** (AD-A13) |

---

## 9. 확정 틱 시각 결정 논증 (AD-A7 — 브리프 §5-9)

**결론: 17:00 KST. TASK_mobile_m1·ARCHITECTURE_SPLIT의 16:20은 문서 정정 대상이다.**

| 근거 | 내용 |
|---|---|
| **물리 하한** | KRX 정규장 마감 15:30. 그 이전 값은 "그날의 확정치"가 아니다 |
| **SSOT 자기정합** | `configs/statemachine.yaml`의 `schedules.collection.daily_kr = "50 16 * * 1-5"`(16:50 KST). **16:20 확정 틱은 SSOT가 선언한 KR 수집이 끝나기 30분 전에 도는 셈** — 같은 파일 안에서 모순이다 |
| **서버와의 시각 통일** | 같은 파일 `schedules.evaluation.kr_close = "0 17 * * 1-5"`(17:00). 모바일 확정 틱을 17:00에 두면 **SSOT의 서로 다른 숫자가 하나로 준다** — INT(D-21) 단계의 "동일 일자 확정 국면 일치율 ≥90%" 비교가 시각 차이 잡음 없이 성립한다 |
| **골든 동결값과의 일치** | `backtest/replay.yaml`의 `profiles.mobile_daily.confirm_time_kst: "17:00"`(BT-03 선정). `golden_mobile.yaml`의 동결 타임라인이 이 값 위에서 만들어졌다. **프로덕션이 16:20을 쓰면 골든이 검증하는 틱과 앱이 도는 틱의 정의가 갈린다** |
| **미국 지표 정합** | `daily_us` 수집 크론 07:20 KST. 전일 미국 종가는 KST 새벽 확정 → 17:00이면 여유 확보. 하니스 가시성 규칙(`us_market`은 T 다음 거래일 확정 틱에서 최초 가시)과도 일치 |
| **K-14 현실** | WorkManager는 정시를 보장하지 않는다. 따라서 17:00은 **하한선**이며 실제 의미는 "17:00 이후 최초 실행". 16:20을 두면 지연 여유가 없어 수집 미완 상태에서 확정할 위험이 커진다 |
| **K-06 유지** | 크론은 KST 고정, 미국 서머타임은 데이터 as-of 정렬로 흡수. ET로 바꾸지 않는다 |

**함께 처리해야 할 구조 문제**: `confirm_time_kst`가 현재 **하니스 전용 파일**(`backtest/replay.yaml`)에만 있다.
앱이 그 파일을 읽는 것은 부적절하고(assets 동기화 대상이 아니다), 코드에 상수로 넣으면 CLAUDE.md §1 위반이다.
→ §11 **C-1** 변경 제안(`configs/statemachine.yaml`의 `profiles.mobile_daily.confirm_time_kst` 신설)으로 해소한다.
`tests/test_configs_schema.py`는 `required_keys.issubset(...)` 부분집합 검사이고
`engine_ref.registry.load_statemachine`은 지정 키만 읽으므로 **키 추가의 회귀 영향은 없음**을 확인했다.

---

## 10. 미해결 결정 목록 (Advisor·사용자 상신)

| ID | 결정 사항 | 선택지 | **권고** |
|---|---|---|---|
| **U-1** | kotlin_krx 통합 방식 — 자산 소유자(사용자)의 향후 개발 계획에 달렸다 | (a) 벤더링 (b) git submodule + 컴포지트 빌드 | **(a) 벤더링.** 업스트림이 "ALL PHASES COMPLETE"이고 Android 적응(자격증명·rate limit·테스트 분리)이 필수다. 사용자가 kotlin_krx를 계속 독립 발전시킬 계획이면 (b)로 전환 — 그 경우 Kotlin 플러그인 버전 정렬 검증이 선행 |
| **U-2** | 프리뷰 `coverage`의 정의 — carry-forward 이월분을 계상하는가 | (a) raw(이월 제외) (b) effective(이월 포함) (c) 둘 다 표시, 억제는 raw | **(c) 둘 다 산출·표시, 억제 판정은 raw.** (b)만 쓰면 억제가 사실상 발동하지 않아 D-23 §23.3-3이 무력화되고 TASK 완료기준 ③의 67.7%가 재현되지 않는다. **(라운드 2 정정)** 라운드 1이 45.2를 "이월 후 값"으로 서술한 것은 D-23 §23.2 오독이다 — 45.2는 **서버 동시각(전체 분모 31.0 유지 + KR severity 0)** 값이다. 이월이 45.2에 도달하는 것은 이월값 severity가 0일 때의 **귀결**이지 원문의 정의가 아니다(§3 MT1-07 ④/④-b) |
| **U-3** | **VKOSPI 입력 이원화** — kotlin_krx `getVkospi`(MDCSTAT01201)가 존재해, 서버가 폴백(realized_vol)을 쓰는 지표를 모바일은 실측할 수 있다 | (a) 모바일도 폴백 사용(정합 우선) (b) 모바일만 실측 사용(품질 우선) (c) **실측치를 Room에 수집·기록하되 v1 판정 입력은 폴백으로 통일**, C1에서 서버가 동등 경로 확보 시 양측 동시 전환 | **(c).** append-only 원장이라 지금 수집해 두면 나중에 백필 없이 전환 가능하다(옵션 가치 보존). (b)는 SSOT 의미론을 프로바이더별로 갈라 D-23 §23.5 "동일 SSOT" 보장과 INT 일치율 ≥90%를 동시에 위협하고, BT-05 파리티 범위도 갈라야 한다 |
| **U-4** | G-4 kr_cds_5y_delta | (a) 모바일 수집 구현 (b) 미수집 확정 + "미수집" 배지 | **(b).** `optional:true`라 분모 제외로 composite 무왜곡. 스크래핑은 HTML 파서 의존성 + 차단 위험을 더한다. 단 MT1-00d 실측이 "정적 GET + 정규식 1개, 3일 연속 성공"을 보이면 (a) 재검토 |
| **U-5** | BT-05 실행 위치 | (a) JVM `:engine:test` (b) 계측 `connectedCheck` | **(a) JVM + 계측 스모크 1건.** 엔진이 Android API를 쓰지 않아 계측이 더하는 증명이 없고, CI 회귀 가능성을 잃는다. BACKTEST_PLAN §BT-05가 JVM 타깃을 이미 허용 |
| **U-6** | ECOS 수집 성공 시 프로덕션 분모(coverage)가 백테스트 보정 기준과 달라진다(R-07) | (a) 무시 (b) GATE_GM1 기록 + C1 재보정 이관 (c) M1에서 재보정 | **(b).** (c)는 근사-PIT 위에서 또 한 번 보정하는 것이라 R-03(과적합) 재발. C1 실측 lake에서 처리 |
| **U-7** | Drive 백업의 깊이 | (a) SAF 폴더 + Drive 앱 동기화(코드 0) (b) Drive REST 직접 연동 | **(a).** TASK 문언이 "훅"이다. (b)는 로그인·권한·쿼터를 M1에 끌어들인다. 필요하면 M2/M3에서 사용자 결정으로 추가 |
| **U-8** | Gradle 의존성 체크섬 검증(`verification-metadata.xml`) | (a) M1 도입 (b) M3 이월 | **(b) M3 이월.** AAA §2.3이 요구하는 것은 버전 핀이고, 그것은 카탈로그+동적버전 금지로 충족된다. 체크섬 메타는 신규 의존마다 갱신 비용이 붙는다 |
| **U-9** | 아키텍처 테스트 도구 | (a) Konsist(신규 의존 1) (b) 자체 소스 스캐너 | **(a).** 규칙당 3~5줄 vs 자체 구현 60줄 + 정규식 취약. AAA가 증거를 코드로 요구하므로 더 정확한 쪽 |
| **U-11** ★ | `catchup_max_trading_days` 최종값 (라운드 1의 30은 근거 없이 적은 값이라 **철회**) | (a) 20 잠정 확정 (b) MT1-00g의 range 조회 상한 실측 후 확정 (c) 상한 없이 축약 규칙만 | **(b).** 잠정 20으로 구현하되, MT1-00g가 측정한 collector별 range 상한과 원장 보유 이력(252 거래일)을 근거로 GM1 전에 확정. 도출 근거는 §3 MT1-06b 5항 |
| **U-12** ★ | 확정 틱 시각이 MT1-00g 실측에서 17:00보다 늦게 나올 경우의 처리 | (a) 상향 + 골든 재확인 (b) 17:00 유지 + 미확정 데이터 결측 허용 | **(a).** (b)는 매일 KR 지표를 결측시켜 kr_flow_price 축을 죽인다. 상향 시 `replay.yaml`과의 값 불일치가 생기므로 **골든 재산출 필요 여부를 backtest-analyst가 실행으로 판정**(예상으로 대체 금지) |
| **U-14** ★ | **[병합 결정 M-43]** 프리뷰/확정 판별자 방식 | (가) `source` 표식 + 확정 우선 정렬 (나) 프리뷰 전용 테이블 (다) **`lane` 컬럼 + 하드 필터** | **(다).** (가)는 **확정 수집 실패일에 그대로 폴백**해 문제의 그 케이스를 못 막는다(선호 ≠ 배제). (나)는 방어 1개를 얻고 스키마·트리거·내보내기·진단 유지 지점 4개를 늘린다. (다)는 컬럼 1 + SQL 조건 1이며 결측 처리는 엔진에 이미 있다(§2.12 (a)) |
| **U-15** ★ | **[병합 결정 M-39]** 프리뷰 나이 산식 | (가) `evaluatedAt=now` + 일 단위 `visibleAt` → **실경과** (나) `evaluatedAt`을 확정 시각으로 스냅 → 24h 배수 | **(가).** 주논거: ① 정직한 as_of 표기(D-17 §2 — 13:00에 본 전일 종가는 20시간 전 값이지 "1일 전"이 아니다) ② 스냅하면 그날 확정 시각에 가시화될 값이 프리뷰에서 미리 보인다(§2.11 지뢰 7의 등호). 보조: `daily_kr 30h`만 24h 배수가 아니라 스냅 시 24h와 구별되지 않는다. **(라운드 5 정정)** 라운드 4의 "48h·96h도 배수가 아님 / BT-03 선정값 소멸"은 사실 오류로 철회(§2.12 (c)) |
| **U-13** ★ | 설치 시 **초기 부트스트랩 fold** — 원장의 과거 구간으로 `tick_input`을 소급 생성해 첫날부터 실제 국면을 표시할 것인가 | (a) 비채택(첫 확정 틱이 시퀀스 1번째, GREEN 출발) (b) 252 거래일 소급 fold | **(a) M1 비채택.** `promote_sustain_ticks=1` + `skip_levels=true`라 **승격은 1틱에 수렴**하고 강등만 지연되므로 초기 오차가 항상 **안전한 방향(낮게 시작)**이다. (b)는 252틱×15지표 소급 산출 + 그 구간의 `visible_at` 재구성을 요구해 비용 대비 이득이 1일치 지연뿐. 필요하면 M2에서 추가 |
| **U-10** | golden_mobile.yaml의 `registry_version: 0.1.0` 스탬프가 현행 0.3.1-rc와 불일치(값 자체는 MT0-07/08에서 무회귀 확인됨) | (a) 방치 (b) 스탬프 정정 제안 | **(b) 정정 제안**(§11 C-4). MASTER_PLAN §5는 "골든 기대값 파일과 항상 짝으로 갱신"을 규정한다. Kotlin L0 대조가 이 파일을 직접 읽으므로 출처 표기가 어긋난 채로 두면 GM1 증빙의 신뢰가 흔들린다 |

---

## 11. SSOT 변경 제안 (직접 수정 금지 — Advisor 승인 후 별도 서브태스크로 반영)

| ID | 대상 | 제안 | 근거 | 영향 범위(실측 확인 결과) |
|---|---|---|---|---|
| **C-1** | `configs/statemachine.yaml` | `profiles.mobile_daily`에 `confirm_time_kst: "17:00"` 추가 | 앱이 확정 틱 시각을 SSOT에서 읽어야 한다(CLAUDE.md §1 하드코딩 금지). 현재 값은 하니스 전용 `backtest/replay.yaml`에만 존재 | `tests/test_configs_schema.py`는 부분집합 검사, `load_statemachine`은 지정 키만 읽음 → **회귀 없음**. `replay.yaml`은 그대로 두고 "두 값 일치" 검증 테스트를 추가해 드리프트 차단(하니스 코드 무변경) |
| **C-2** | `configs/sources.yaml` | ① `pykrx` 항목의 K-02 서술에 **모바일 경로(kotlin_krx MDCSTAT01201) 실측 결과** 부기 ② `stooq` 항목에 실측 심볼 매핑표 ③ `kis` 실측 결과 ④ KRX 로그인 정책 대응 결과 ⑤ `ecos.stats[721Y001]` 주석의 "구현 시 검증" → 실측 완료 기록. **(라운드 2 정정)** 라운드 1이 여기에 있다고 적었던 `item_codes: VERIFY`는 이 파일에 없다 — 실위치는 C-8이다 | K-01·K-02·K-03·K-18 실측의 정본 기록 위치 | 문서성 필드 — 코드 영향 없음. `test_sources_new_providers_present` 통과 유지 확인 필요 |
| **C-8** ★신설 | `configs/indicators.yaml` | `krx_credit_spread_delta.source.item_codes: { corp_aa3y: VERIFY, ktb_3y: VERIFY }`의 **VERIFY 2건을 MT1-00b 실측값으로 교체** (K-04의 과업 정의 "실측 확인 후 교체까지가 과업") | K-04. 이 교체 없이는 credit 축 2번째 지표(가중 2.0)가 영구 결측 | 값 교체. `engine_ref`는 `source` dict를 그대로 실어 나를 뿐 item_codes를 해석하지 않음 → **엔진 회귀 없음**. `test_configs_schema`는 이 키를 읽지 않음. 단 **골든 무회귀 재확인은 실행**한다(픽스처는 ecos 미수집이라 무변화가 예상되나 예상을 근거로 쓰지 않는다) |
| **C-11** ★라운드4 | `configs/indicators.yaml` | `engine:` 블록에 신설: `warmup_calendar_days: 550` | **반려 N-1.** Stage 1의 원계열 조회 범위(§2.11)를 코드 리터럴로 두면 CLAUDE.md §1 위반이고, 하니스 `backtest/windows.yaml` `padding_days: 550`과 **값이 갈리면 BT-05 L3가 실패**한다. 두 값의 일치 검증 테스트를 함께 둔다 | `engine`의 형제 키 추가. `test_indicators_stale_profiles_structure`는 `engine.stale_profiles`만, `engine_ref.registry`는 `engine.modifiers`·`engine.stale_profiles`만 읽음 → **회귀 0**. 하니스는 계속 `windows.yaml`을 읽으므로 **하니스 코드 무변경** |
| **C-9** ★신설 | `configs/indicators.yaml` | `engine:` 블록에 신설: `preview_policy: { min_coverage: 0.80 }` | **반려 A-3.** D-23 §23.3-3의 80%가 SSOT 어디에도 없다. 코드 상수로 두면 CLAUDE.md §1 위반이고, 임계는 configs에서만 온다는 규율의 예외를 만들 이유가 없다 | `engine`의 **형제 키 추가**. `test_indicators_stale_profiles_structure`는 `d["engine"]["stale_profiles"]`만 읽고, `engine_ref.registry`는 `engine.modifiers`·`engine.stale_profiles`만 읽는다 → **회귀 0**(실측 확인). 서버는 프리뷰가 없어 이 키를 무시한다 |
| **C-3** | `contracts/snapshots/` (신규 디렉토리) | 스키마·example 동결 파일 4종 신설. **기존 `contracts/*.py`는 무수정** | 브리프 §2-9 "공유 스냅샷 파일 기준 양측 왕복 검증" | 신규 파일만 — 기존 계약 무영향 |
| **C-4** | `backtest/golden_mobile.yaml` | 머리 주석의 `registry_version: 0.1.0` → 현행(0.3.1-rc)로 정정 + MT0-07/08 무회귀 확인 이력 부기. **틱 값은 절대 건드리지 않는다** | MASTER_PLAN §5 짝 갱신 규정, GM1 증빙 신뢰 | 메타데이터만. `test_golden.py`는 이 키를 읽지 않음(확인) — **회귀 없음** |
| **C-5** | **`configs/statemachine.yaml`** `profiles.mobile_daily` (**라운드 3 재지정** — 반려 A-11) | `catchup_max_trading_days: <MT1-00g 실측 후 확정, 잠정 20>` | 캐치업 상한을 코드 상수로 두면 SSOT 규율 위반. **위치 정정**: 이것은 **틱 카운트 파라미터**이므로 `promote_sustain_ticks`·`demote_below_ticks`·`min_dwell_ticks`·`reentry_cooldown_ticks`와 같은 블록에 있어야 한다. 라운드 2의 `sources.yaml` `mobile:` 블록 신설안은 provider·delivery 스키마와 이질적이라 **철회**(B 제안 7·C P-11과 위치 정렬). **(라운드 2 정정 유지)** 값 30은 근거 없이 적었으므로 철회, 도출 근거는 §3 MT1-06b 5항, 확정은 U-11. "K-03 rate limit"이 근거가 아님(range 수집)도 명기 | `profiles.mobile_daily`의 형제 키 추가. `test_statemachine_profiles_structure`는 `required_keys.issubset(...)` **부분집합 검사**, `load_statemachine`은 지정 4키만 읽음 → **회귀 0**(실측 확인). 앱은 이 값을 SSOT에서 로드 |
| **C-6** | `docs/ARCHITECTURE_SPLIT.md` §1 · `TASK_mobile_m1.md` MT1-06 | 확정 틱 "16:20" → **"17:00"** 정정, 근거 각주(§9) | AD-A7. 문서와 configs·골든이 어긋난 상태를 남기지 않는다 | 문서만 |
| **C-7** | `docs/ARCHITECTURE_SPLIT.md` §1 데이터 소스 매핑표 | "KOSPI/KOSDAQ/VKOSPI/**수급/업종**"의 **업종**은 현행 레지스트리에 대응 지표가 없다(K-13 대상 지표 부재) → M1 수집 범위 밖임을 명시 | 범위 오해로 불필요한 구현이 들어가는 것을 막는다(YAGNI) | 문서만 |

**코드 정합 관찰 (SSOT 아님 — 별도 제안, 본 계획 범위 밖이므로 Advisor 판단)**

| ID | 대상 | 관찰 | 제안 |
|---|---|---|---|
| **O-A1** | `engine_ref/registry.py:317` `is_stale(as_of, evaluated_at, ...)` | 파라미터명이 `as_of`지만 정본 호출자(`run_replay.is_stale_check`)가 넣는 값은 **`visible_at`**이며, 그 docstring이 as_of(달력일 자정) 사용이 오판을 낳음을 실측으로 명시한다. 이름과 의미가 어긋난 상태다 | docstring 1줄 부기 또는 파라미터명 `visible_at`으로 정정(호출자 없음 확인 후). **M1 착수 전 처리 권고** — Kotlin 이식자가 이름을 보고 as_of를 넘기면 §2.8이 통째로 무너진다. 단 `engine_ref`는 골든의 계산 정본이므로 **동작 무변경 리팩터 + 골든 재확인**을 조건으로 |

---

## 12. 위임 규율 (브리프에 복사해 넣을 항목)

| 서브태스크군 | 에이전트 | 모델(D-20 §20.2) |
|---|---|---|
| 실측 (MT1-00a~d,f) | `data-verifier` | claude-sonnet-5 |
| Gradle·Kotlin·Android 구현 전부 | `kotlin-implementer` | claude-sonnet-5 |
| Python 산출물 (02a, 05e) | `python-implementer` | claude-sonnet-5 |
| 파리티 결과 분석·리포트 (05f 후단) | `backtest-analyst` | claude-sonnet-5 |
| M1 UI(기능판) | `kotlin-implementer` (**ui-craftsman은 M2**) | claude-sonnet-5 |
| 기계 검증 | `qa-verifier` | claude-sonnet-5 |
| 품질 판정 | `aaa-critic` | claude-opus-5 (effort xhigh) |

**모든 브리프에 반드시 포함할 것** (서브에이전트는 대화 이력을 못 본다):
대상 절대 경로 · 근거 결정(D-xx) · 해당 K-xx 원문 · 완료 테스트 명령 · **REVIEW_M0 신설 규율 4건**
(① 파생 수치의 **퇴화 입력 증인 테스트** 의무 ② 결측 귀속 서술의 형제 계열 증거 의무
③ 완료 보고에 `git status` 원문 첨부 ④ qa-verifier의 보고-저장소 일치 선행 확인).

---

## 13. 다른 관점과의 경계 (병합 시 우선순위)

| 영역 | 본 계획의 입장 | 우선권 |
|---|---|---|
| 확정 틱 시각 논증(§9) | 아키텍처·SSOT 정합 근거로 17:00 | **B(데이터·정합성)** 계획의 as-of·가시성 논증이 더 정밀하면 그쪽 |
| coverage 정의(U-2) | raw 기준 억제 권고 | **B** — 수치 재현(66.7/45.2)의 정본 |
| 파리티 층 구성(§2.7) | L1·L2·L3 3층 | **A**(모듈·실행 경계) · 벡터의 **내용**(어떤 창·어떤 경계값)은 **B** |
| 노티 문구·온보딩·실패 UX | 최소 골격만 제시 | **C(UX·운영)** |
| 실기기 스모크 운영 절차 | 증빙 수집 **수단**(진단 내보내기)을 설계 | 절차 **내용**은 **C** |
| 모듈 구조·빌드·의존성·타입 강제 | 본 계획이 정본 | **A** |

---

## 14. GM1 게이트 실행 체크리스트 (그대로 복사해 실행 가능)

```bash
# ── 공통 회귀 (Python, 전 phase 유지) ───────────────────────────────
uv run ruff check .
uv run pytest -q
uv run pytest backtest/test_golden.py -q            # D-08 2케이스 × 2프로파일
uv run pytest tests/test_contract_snapshots.py -q   # MT1-02a 신설
uv run pytest backtest/test_parity_vectors.py -q    # MT1-05e 신설 (벡터 결정론)

# ── Kotlin (디바이스 불요, CI 기본 경로) ────────────────────────────
cd mobile
./gradlew check                                     # §2.2 표의 전 항목(커버리지 임계 포함)
./gradlew :engine:test --tests "*Parity*"           # BT-05 L1·L2·L3 + L0 골든 + visible_at 동일성
./gradlew :engine:test --tests "*Visibility*" --tests "*KnownSeries*"   # W-V1~W-V4, W-K1
./gradlew :engine:test --tests "*IndicatorRuntime*" # §2.11 Stage 1~3 조립, W-W1(워밍업 충분성)
./gradlew :app:testDebugUnitTest --tests "*Lake*"   # 증인 W-L1~W-L4 (캐치업/look-ahead 양방향)
./gradlew :app:testDebugUnitTest --tests "*ConfirmTick*"    # 증인 W-S1~W-S4 (국면 연속성·fold 결정성)
./gradlew :app:testDebugUnitTest --tests "*CarryForward*"   # 증인 W-P2·W-P4·W-P6·W-P7 (이월 원천·나이·0행)
./gradlew :krx:verifyKrxProvenance                  # 벤더링 출처 무결
./gradlew :app:testDebugUnitTest --tests "*Architecture*"   # AT-1~AT-8
./gradlew :engine:jacocoTestCoverageVerification :app:jacocoTestCoverageVerification   # §2.9

# ── 계측 (실기기 필요) ──────────────────────────────────────────────
./gradlew connectedDebugAndroidTest                 # assets SHA-256(L-B) + 엔진 로딩 스모크

# ── 실기기 1일 스모크 (사용자 수행, docs/runbooks/M1_SMOKE.md) ──────
#   확정 틱 1회(17:00 이후) + 프리뷰 3회 정상 + 진단 JSON 제출
```

**GM1 통과 조건** (TASK 완료 기준 + 본 계획의 보강):
① 위 명령 전부 green ② BT-05 파리티 리포트에 `max|Δcomposite|` 실측치와 타임라인 diff 0 기재
③ 해시·스냅샷 테스트 green(JVM·계측 양쪽) ④ 실기기 스모크 증빙(진단 JSON + 스크린샷)
⑤ aaa-critic 전 서브태스크 PASS ⑥ `docs/gates/GATE_GM1.md` 작성 — **U-3·U-4·R-07·00b/00c 실측 결과와
축소된 발화 표면**, **00g의 확정 시각 실측치와 최종 채택 시각**, **캐치업 상한 확정값(U-11)**을 반드시 기록
⑦ 사용자 승인.

---

## 15. 예상 커밋 단위

`m1-XX: <영문 요약>` 형식, 서브태스크 1개 = 커밋 1개(CLAUDE.md §2).

```
m1-00: record M1 pre-implementation verification results (yahoo/fred/ecos/krx/cds/toolchain)
m1-00g: record KR close finalization timing measurement (confirm tick time)
m1-01a: scaffold mobile gradle project (3 modules, version catalog, jdk17 toolchain)
m1-01b: wire ktlint + detekt + jacoco thresholds into gradle check
m1-01c: syncConfigs generated assets + sha256 manifest + jvm/instrumented hash tests
m1-01d: vendor kotlin_krx as :krx module with provenance manifest
m1-02a: add contract schema/example snapshots + python roundtrip tests
m1-02b: mirror contracts as kotlinx.serialization classes + frozen-snapshot tests
m1-05a2: port visibility + known-series lookup as production modules (pit/)
m1-05b2: indicator runtime (raw range query -> transform -> visibility lookup)
m1-03a: room append-only observation lake (lane split, warmup range query, look-ahead guard)
m1-03b: daily csv export + SAF backup hook
m1-03c: frozen tick_input table (statemachine fold source of truth)
m1-04g: collector contract + orchestrator (partial-failure absorption, rate limit)
m1-04a: yahoo collector with stooq fallback
m1-04b: fred collector (T+1 as-of)
m1-04c: krx collector via :krx (session, rate limit, holiday skip)
m1-04d: ecos collector (verified item codes)
m1-04e: kis adapter (disabled by default, preview hook)
m1-04f: kr_cds decision applied (uncollected badge / collector)
m1-05a: kotlin registry loader + transform spec parser
m1-05b: kotlin transforms + L1 parity vectors
m1-05c: kotlin scoring + modifiers
m1-05d: kotlin statemachine (D-25 semantics + D-26 pairing)
m1-05e: python parity vector generator (deterministic)
m1-05f: BT-05 parity suite (L2 pipeline, L3 end-to-end, golden_mobile match)
m1-06a: daily confirm tick worker (self-rescheduling, unique work, full-fold phase derivation)
m1-06b: idempotent catch-up (evaluatedAt = D confirm time, bounded, notification suppression)
m1-06c: tick run history table
m1-07a: preview pipeline (lane=1 ingest, evaluatedAt=now, type-separated carry-forward)
m1-07b: coverage computation + suppression rule (D-23)
m1-07c: architecture tests (carry-forward isolation, naive datetime ban)
m1-08a: three notification channels
m1-08b: functional home + run history + settings screens
m1-08c: minimal onboarding (key entry, battery optimization guidance)
m1-09a: M1 device smoke runbook
m1-09b: in-app diagnostics export (no secrets)
```

---

## 16. 이 계획의 자기 한계 (정직성 조항)

1. **버전 숫자를 확정하지 않았다.** AGP/Kotlin/Gradle/라이브러리 버전은 MT1-00e 실측 후 카탈로그에 기록한다.
   계획서가 버전을 박으면 그 순간부터 근거 없는 낙관이 된다.
2. **kotlin_krx의 실제 동작을 실측하지 않았다.** 소스·문서를 읽고 API 존재를 확인했을 뿐이며,
   로그인 정책·VKOSPI·거래대금 필드는 전부 MT1-00c의 실측 대상이다. U-3의 권고는 "getVkospi가 실제로 동작한다면"이라는
   조건부다 — 동작하지 않으면 U-3는 자동으로 (a)로 귀결된다.
2-b. **확정 틱 17:00의 물리 전제도 실측하지 않았다**(반려 A-6). §9의 논증은 SSOT 자기정합·골든 동결값·
   수집 크론에 근거한 것이지 "17:00에 데이터가 실제로 확정된다"는 관측이 아니다. MT1-00g가
   그 관측을 만들고, 결과가 다르면 AD-A7·C-1·C-6의 값이 바뀐다(U-12).
2-b3. **워밍업 550일이 실제로 원장에 존재하는지는 수집 설계에 달렸다.** §2.11 Stage 1은 그 범위를
   *조회*할 뿐이며, 최초 설치 시 550일치 원계열을 채우는 것은 MT1-04 오케스트레이터의 초기 백필
   책임이다(range 조회 필수 요구사항과 동일 근거). 백필 실패 시 z-score 계열이 NaN이 되어
   결측 처리되므로 **부팅 직후 며칠은 커버리지가 낮을 수 있다** — 이 사실을 UI 배지로 노출한다(MT1-08b).
2-b2. **전량 fold의 성능은 계산으로만 논증했다**(252틱/년 × 10년 = 2,520틱). 실기기 측정은 MT1-09 스모크에서
   확정 틱 소요 시간으로 관측한다. 상한을 넘으면 업그레이드 경로는 §2.10의 `ponytail:` 주석대로
   스냅샷+재개이며, 그것은 **엔진 API 변경을 수반**하므로 골든 재확인이 딸려온다.
2-c. **`catchup_max_trading_days`의 값은 미확정이다.** 라운드 1의 30은 철회했고, 잠정 20의 근거는
   §3 MT1-06b 5항에 적었으나 range 조회 상한 실측(MT1-00g) 전까지는 가설이다(D-04 규율, U-11).
3. **파리티 벡터의 창 선택·경계값 구성은 B 관점의 몫이다.** 본 계획은 3층 구조와 실행 위치만 정했다.
4. **UI·실패 경로 UX는 골격만 제시했다.** AAA §2.2 전수 점검은 C 관점 계획이 정본이다.
5. **공수를 시간으로 추정하지 않았다.** 웨이브·병렬 구조와 임계 경로(§4)만 제시한다 —
   Worker 처리량을 모르는 상태의 시간 추정은 숫자를 가장한 추측이다.
