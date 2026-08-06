# M1 실행 계획 — 관점 D (런타임 데이터 경로)

- 작성일: 2026-08-06 · 작성: plan-architect D · 절차: AAA_QUALITY_STANDARD §3
- 지위: **관점 D(앱 실운영의 데이터 흐름 사슬)를 전담하되 그 자체로 실행 가능한 전체 계획**.
  A(아키텍처)·B(데이터 정합성)·C(UX·운영) 영역도 §10에 최소선으로 명시했다.
- 신설 경위: REVIEW_M1 라운드 3에서 3연속 FAIL이 **"프로덕션 런타임 데이터 경로의 미정의"**(R1 visible_at
  산출처 → R2 상태기계 지속 모델 → R3 transform 입력 조회 계약)로 구조 재분류되었고, 사용자 결정(2026-08-06)
  으로 이 사슬을 소유하는 4번째 관점이 신설되었다. **본 계획의 본체는 §2다.**
- 범위 규율: MT1-01~08 유지 + 세분화(축소 없음). `configs/`·`contracts/`·`prompts/` 및 기존 코드는
  **일절 수정하지 않았다** — 필요한 변경은 §8 "변경 제안"으로만 기록한다.
- 입력 정본: `docs/plans/M1_COUNCIL_BRIEF.md` §1 허용 목록 + (§6-D 추가 허가) `M1_PLAN_A/B/C.md`,
  `docs/reviews/REVIEW_M1.md`. 그 밖의 저장소 탐색은 하지 않았다.

---

## 0. 관점 D 핵심 결론 (먼저 읽을 7줄)

1. **사슬은 7단계이고, 세 실행 경로(확정 틱·캐치업·프리뷰)의 분기점은 정확히 3개뿐이다** — ② 조회의
   `lane` 필터, ⑤ 가시성의 `evaluatedAt`, ⑦ fold 실행 여부. 나머지 전 단계는 **같은 함수 같은 코드**다(§2.8).
   분기가 3개로 고정되면 "프리뷰가 확정 틱을 오염시킨다"는 사고 표면이 열거 가능해진다.
2. **N-1의 정답은 전치(commutation) 정리다.** causal transform `T`와 **prefix 필터** `P_c`(as_of ≤ c)에 대해
   `P_c(T(X)) = T(P_c(X))|_{as_of ≤ c}`이므로 "cutoff까지 **전량** 조회 → transform → 출력에 가시성 색인"은
   정본(`run_replay.py:289-317`)과 값이 같다. 반면 **최신 1행 조회**·**스테일 행 제거 후 transform**·
   **결측 보간 후 transform**은 전부 prefix 필터가 아니라 등식이 깨진다(§2.4.1).
   따라서 D-23 §23.3-4의 "확정 틱 carry-forward 금지"는 정책이기 이전에 **transform 등식의 전제**다.
3. **carry-forward는 원계열이 아니라 지표 severity 계층에 적용된다.** 이월값을 raw 시계열에 주입하면
   (2)의 등식이 깨져 프리뷰 transform 출력이 확정 틱과 다른 세계가 된다. 이월 원천은 원장이 아니라
   **직전 `tick_input` 동결본**이며, 그래서 "Room에 새 레코드로 쓰지 않는다"(D-23 §23.3-1)가 자동 성립한다(§2.6.3).
4. **가시성 3규칙은 하나의 규칙 + 파라미터 1개로 정확히 환원된다.**
   `visDay(as_of) = firstGridDayOnOrAfter(as_of + L)`, `L` = 0(krx·fx) / 1(us_market) / `source.lag_days`(fred·ecos).
   `_first_grid_day_after(T) ≡ _first_grid_day_on_or_after(T+1일)`이 bisect 수준에서 동일하다(§2.5.1 증명).
   이식 대상이 3분기에서 1함수로 줄고, 프로덕션 신규 계열(ECOS·CDS)의 규칙 결정도 `L` 하나로 끝난다.
5. **프리뷰 시계(M-39)는 "확정 틱 규칙의 일반화"로 풀린다** — 가시 판정을 **일(day) 단위**로,
   스테일 나이를 `max(0, evalAt − visibleAt)`으로 두면 확정 틱(evalAt = D 17:00)에서는 **정본과 비트 동일**
   (증명 §2.5.4)이고, 프리뷰(evalAt = now)에서는 "오늘 알 수 있는 것은 오늘 보인다"가 성립하며,
   **죽은 계열은 as_of 기준으로 계속 늙어 stale이 된다**(C안 observed_at 분기의 치명 결함을 구조적으로 회피).
6. **원장 granularity는 `(series_id, field, as_of, lane, revision)`이다.** `^GSPC`는 2지표가 공유하고
   `KRW=X`는 4필드, `KRX:1001`은 5필드다 — 지표 키로는 표현 불가(C-11). `lane`(0=확정, 1=프리뷰)은
   **확정 틱이 프리뷰 장중 스냅샷을 종가로 오인하는 유일한 사고 경로를 SQL 한 줄로 차단**한다(§2.1·2.2).
7. **웜업은 가정이 아니라 계측 대상이다.** `requiredRows(지표)`를 transform 문자열에서 도출(리터럴 0)하고
   조회 창 안의 실제 행 수와 대조해 `WARMUP_INSUFFICIENT`를 **결측과 구분**한다. 이 구분이 없으면
   설치 첫날 "전 지표 결측"이 정상 동작처럼 보이고 저커버리지 국면이 원장에 커밋된다(§2.3.2).

---

## 1. 계획 전제 — 브리프 §2 확정 사실의 반영 지점

| 브리프 §2 | 본 계획의 반영 |
|---|---|
| 1. registry **0.3.1-rc**를 assets에 굽는다 | §2.6 `thresholds.extreme` 소비, §2.7 `or_any_extreme` 입력, MT1-05a |
| 2. D-26 짝지음·`or_any_extreme`는 프로덕션 경로 | §2.7 fold가 프로덕션에서 `_escape_blocks_exit`를 실제로 태운다(증인 W-S4), BT-05 범위 |
| 3. 확정 틱 시각 재확인(AD-3b) | MT1-06a(§3), §2.5.3 — `confirmTime`은 **가시성 함수의 인자**이므로 SSOT 확정이 사슬 전체의 선행 조건 |
| 4. G-4 CDS 모바일 경로 없음 | MT1-00d·04f, §2.5.2 `L` 표에 scrape_wgb 행 포함(수집 채택 시), §7 D-D11 |
| 5. KRX = kotlin_krx, 야후 ^KS11 폴백 비채택 | MT1-06b 그리드 공급자 = `KrxIndex.getBusinessDays`(실측: KOSPI OHLCV 범위 조회 = **경험적 달력**, 하니스 `trading_days()`와 동일 원리). KR 지수 야후 폴백 **제안 없음** |
| 6. M1은 LLM 미호출 | 사슬 어디에도 LLM 경로 없음. ⑥ 산출은 `TriggerBlock`까지만 조립(M2 소비자) |
| 7. 뉴스 2지표 `enabled:false`, kr_cds `optional:true` | §2.6.2 분모 = enabled 15지표 31.0, optional도 결측이면 동일하게 분모 제외 |
| 8. D-23 커버리지 규율 = MT1-07 완료 기준 4항 | §2.6.3(carry-forward 계층)·§2.5.4(프리뷰 시계)·MT1-07c/d |
| 9. contracts 스냅샷은 Python 측도 신설 | MT1-02a(python-implementer). ⑥ 산출 `TriggerBlock` 1건을 왕복 테스트에 투입(§3) |
| 10. REVIEW_M0 신설 규율 4건 | §9.2 브리프 공통 규약. 특히 ①(퇴화 입력 증인)을 **단계마다 1건 이상** 의무화(§2.9) |
| 11. 모델 배정 D-20 §20.2 | §3 위임 열 |
| 12. Windows·cp949·계측은 실기기 | §4 명령의 JVM/계측 분리, 판정은 exit code |

**A·B·C 수렴 사실과의 정합**(라운드 3 판정 기준 — 본 계획은 전건 준수한다):
visible_at 파생·미저장 / 전량 fold + `tick_input`의 `Tick` 4필드 1:1 / coverage는 raw 억제 /
kotlin_krx 벤더링 / stale 등호 = 초과만 stale / worst-of-inputs / 캐치업 `evaluatedAt` = D 17:00 /
캐치업 상한 20 / 원장 granularity `(series_id, field, as_of)`.
**명시적 이의는 0건**이다. §2.5.4(프리뷰 시계)와 §2.2(lane)는 수렴 사실을 뒤집지 않고 **미정의 영역을 채우는 신설**이다.

---

## 2. 런타임 데이터 경로 사슬 — 정본 1:1 이식 명세 (본체)

### 2.0 사슬 개관과 정본 매핑

```
① 원장 스키마      observation(series_id, field, as_of, value, observed_at, revision, lane, source)
        │           정본: backtest/fixture_schema.py L30(FIXTURE_COLUMNS) · L44-85(validate_fixture)
        ▼
② 조회 계약        SeriesWindow(series_id, field) = [as_of ≥ cutoff−padding .. as_of ≤ cutoff], lane 필터,
        │           셀당 최신 revision 1행.  정본: run_replay.py L377-385(series_values) + L222-232(cutoff 역산)
        ▼
③ 시계열 구성      as_of(date) 오름차순 Double 배열. 다필드 동일 인덱스, 2계열 union 정렬, prev_close = shift(1)
        │           정본: run_replay.py L377-385 · L501-509(_align_to_ffill) · L578(close.shift(1))
        ▼
④ transform        원계열 전체에 causal 변환 1회. rolling은 min_periods=window, ddof=1
        │           정본: engine_ref/transforms.py L24-102 + run_replay.py L434-606(_BUILDERS)
        ▼
⑤ 가시성/스테일    출력 시계열 각 행에 visible_at 색인 → 틱별 lookup → stale 판정
        │           정본: run_replay.py L222-232 · L235-252 · L255-274 · L289-317 · L320-327 · L352-369
        ▼                 + engine_ref/registry.py L305-314(stale_window) · L317-323(is_stale, 등호 규약)
⑥ Tick 조립        severity → modifier → composite/coverage/distinct_axes/any_crit/any_extreme
        │           정본: run_replay.py L621-716(resolve_severity) · L784-829(틱 루프)
        ▼                 + engine_ref/scoring.py L34-151 · modifiers.py L12-50
⑦ fold             동결 tick_input 전량 → statemachine.run → 타임라인 마지막 원소 커밋
                    정본: engine_ref/statemachine.py L53-60(Tick) · L106-196(run) · L63-88 · L95-103
```

**단계당 1줄 요약(정본 대응)**

| # | 단계 | 정본 file:line | 이식 산출물(모듈) |
|---|---|---|---|
| ① | 원장 스키마 | `backtest/fixture_schema.py:30,44-85` | `:app` `lake/ObservationEntity.kt`·`LakeDao.kt` |
| ② | 조회 계약 | `backtest/run_replay.py:377-385` + `:222-232` | `:app` `lake/AsOfQuery.kt` (SQL §2.2.2) |
| ③ | 시계열 구성 | `backtest/run_replay.py:377-385,501-509,578` | `:engine` `pit/SeriesWindow.kt` |
| ④ | transform | `engine_ref/transforms.py:24-102` + `run_replay.py:434-606` | `:engine` `Transforms.kt`·`IndicatorRuntime.kt` |
| ⑤ | 가시성/스테일 | `backtest/run_replay.py:222-274,289-327,352-369` + `registry.py:305-323` | `:engine` `pit/Visibility.kt`·`StalePolicy.kt` |
| ⑥ | Tick 조립 | `backtest/run_replay.py:621-716,784-829` + `scoring.py`·`modifiers.py` | `:engine` `Scoring.kt`·`Modifiers.kt`·`TickAssembler.kt` |
| ⑦ | fold | `engine_ref/statemachine.py:53-60,106-196` | `:engine` `StateMachine.kt` + `:app` `tick/confirm/FoldCommit.kt` |

> 모듈 경로는 **관점 A의 3모듈 레이아웃**(`:engine`/`:krx`/`:app`)을 가정해 적었다. X-1(레이아웃 3원화)은
> 병합 결정 사항이며, 다른 레이아웃이 채택되면 **본 계획에서 바뀌는 것은 명령·경로의 접두사뿐이고
> 사슬 명세는 불변**이다(§4 말미 대응표).

### 2.1 단계 ① — 원장 스키마 (M-40의 답)

**granularity = `(series_id, field, as_of)` + `lane` + `revision`.** A·B와 동일 판정이며(픽스처 롱포맷 일치),
`lane`·`revision`을 더해 "같은 셀의 서로 다른 관측"을 표현한다.

```sql
CREATE TABLE observation (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  series_id   TEXT    NOT NULL,   -- '^VIX' | 'KRW=X' | 'KRX:1001' | 'BAMLH0A0HYM2' | 'ECOS:721Y001:<item>' ...
  field       TEXT    NOT NULL,   -- 'close'|'open'|'high'|'low'|'trading_value'|'net_buy_value'|'value'
  as_of       INTEGER NOT NULL,   -- UTC epoch millis = 관측일의 UTC 자정 (fixture_schema.to_utc_midnight 규약)
  value       REAL    NOT NULL,   -- Double 고정(K-07). NULL 행 없음 — 결측은 "행 부재"로만 표현
  observed_at INTEGER NOT NULL,   -- UTC epoch millis, 수신 시각. ★ 감사·revision 순서 전용, 판정 미사용
  revision    INTEGER NOT NULL,   -- 같은 (series_id, field, as_of, lane)에서 값이 바뀐 횟수
  lane        INTEGER NOT NULL,   -- 0 = confirmed(확정·캐치업 수집), 1 = preview(온디맨드 수집)
  source      TEXT    NOT NULL    -- 'yahoo'|'stooq'|'fred'|'krx'|'ecos'|'kis'
);
CREATE UNIQUE INDEX ux_obs_cell_rev ON observation(series_id, field, as_of, lane, revision);
CREATE INDEX        ix_obs_scan     ON observation(series_id, field, lane, as_of);
-- UPDATE/DELETE는 BEFORE 트리거 RAISE(ABORT) + DAO 미구현으로 이중 차단 (MT1-03b)
```

**왜 지표 키가 아니라 계열 키인가**(C-11 재발 방지 — 실측 근거):

| 사실 | 근거(정본) |
|---|---|
| `^GSPC` 한 계열을 **2지표**가 쓴다(`spx_drawdown_momentum`, `global_corr_break`) | `run_replay.py:485,513` |
| `KRW=X`는 **4필드**(open/high/low/close), `KRX:1001`은 **5필드**를 쓴다 | `fixture_schema.py:362-367` |
| `kospi_volume_distribution` 하나가 `KRX:1001`의 close + trading_value **2필드**를 쓴다 | `run_replay.py:560-571` |
| `vix_term_structure`·`global_corr_break`·`krx_credit_spread_delta`는 **2계열** 입력 | `indicators.yaml:44,139,73-79` |

지표 키(`indicator_id, as_of`)로는 위 4행을 하나도 표현할 수 없다. 계열 키는 픽스처 롱포맷과 1:1이므로
MT1-04h(수집기↔픽스처 대조)와 BT-05 L0가 **같은 자료구조 위에서** 성립한다.

**`lane`의 필요성(신설 — 사고 경로 1개 봉쇄).** D-17 §3이 프리뷰 수집치의 lake append를 요구한다.
프리뷰가 13:00에 `KRX:1001 close as_of=D`(장중 부분봉)를 쓰면, **그날 확정 수집이 실패한 경우** 확정 틱의
as-of 조회가 그 장중값을 종가로 집어간다. lane이 있으면 확정 경로는 `lane = 0`만 읽으므로 이 사고가 원천 봉쇄되고,
그 지표는 as_of=D−1 **실관측 종가**로 자연 폴백된다(스테일 30h 창 안 → fresh). 정책·경고가 아니라 SQL 조건 1개다.

**중복 억제(멱등)는 lane별로 판정한다**: "같은 `(series_id, field, as_of, lane)`의 최신 revision 값과
동일하면 append하지 않는다". lane을 키에서 빼면 프리뷰가 쓴 값과 확정 수집값이 우연히 같을 때
**확정 lane에 행이 생기지 않는** 결함이 난다(퇴화 증인 W-L3).

### 2.2 단계 ② — 조회 계약 (N-1의 답, SQL 수준)

#### 2.2.1 무엇을 조회하는가

지표 하나를 계산하려면 **그 지표의 입력 계열 전부에 대해, cutoff까지의 원계열 창 전체**가 필요하다.
"as-of 최신 1행"은 `zscore(252)`·`drawdown_from_high(60)`·`rolling_corr(20)+rolling_mean_corr(120)`에
줄 수 있는 입력이 아니다(라운드 3 N-1 판정).

| 파라미터 | 값 | 산출 |
|---|---|---|
| `seriesId, field` | 지표 → 계열·필드 매핑표(§2.3.1) | `run_replay._BUILDERS`의 `series_values(...)` 인자 그대로 |
| `asOfCutoff` | §2.2.3 역산표 | 계열 kind + 평가일 |
| `windowStart` | `asOfCutoff − warmup_padding_days`(550 달력일) | §8 제안 1(SSOT 신설). 충분성은 §2.3.2가 **실측 검증** |
| `lane` | 확정·캐치업 = `0` / 프리뷰 = `0 ∪ 1`(lane 0 우선) | §2.1 |

#### 2.2.2 SQL (Room `@Query` 1개 — 세 경로 공용)

```sql
SELECT o.as_of, o.value
  FROM observation o
 WHERE o.series_id = :seriesId
   AND o.field     = :field
   AND o.as_of    <= :asOfCutoffMillis
   AND o.as_of    >= :windowStartMillis
   AND (:includePreviewLane = 1 OR o.lane = 0)
   AND o.id = (SELECT o2.id FROM observation o2
                WHERE o2.series_id = o.series_id
                  AND o2.field     = o.field
                  AND o2.as_of     = o.as_of
                  AND (:includePreviewLane = 1 OR o2.lane = 0)
                ORDER BY o2.lane ASC, o2.revision DESC, o2.id DESC
                LIMIT 1)
 ORDER BY o.as_of ASC;
```

- **셀당 1행 선택 규칙**: `lane 오름차순(확정 우선) → revision 내림차순(최신 개정치) → id 내림차순(결정적 tie-break)`.
  `id`까지 넣어야 동시 삽입에서도 결정론이 보장된다.
- **프리뷰 적재 행의 확정 틱 배제(4안 공통 필수 항목 — SQL 수준 명시)**: 확정 틱·캐치업은
  `:includePreviewLane = 0`으로 호출하므로 `lane = 1` 행이 **바깥 WHERE 절과 상관 서브쿼리 양쪽에서**
  제외된다(둘 다에 걸어야 한다 — 서브쿼리에만 걸면 바깥이 프리뷰 행을 통과시키고, 바깥에만 걸면
  서브쿼리가 프리뷰 행의 `id`를 골라 결과가 공집합이 된다). 즉 프리뷰가 lake에 무엇을 append하든
  확정 틱의 입력 집합은 불변이다 — 정책·경고가 아니라 조건 2개다. 증인 W-Q3.
- **윈도우 함수(`ROW_NUMBER() OVER`)를 쓰지 않는다.** minSdk 29(Android 10)의 번들 SQLite는 3.22 계열로
  알려져 있어 윈도우 함수(3.25+)가 없을 수 있다. **가정하지 않고 MT1-00f에서 실측**하며(§5), 실측 결과와
  무관하게 상관 서브쿼리 형태를 채택한다(양쪽에서 동작하는 유일한 형태 — 비용은 `ux_obs_cell_rev` 인덱스로 흡수).
- 결과 행 수는 계열당 ≈ 380(550 달력일)로, 활성 15지표가 쓰는 (계열,필드) 쌍 ≈ 25개 → 틱당 ≈ 9,500행.
  단말에서 무시할 수 있는 크기이며, 이 상한이 있기 때문에 "전량 조회"가 실행 가능하다.

#### 2.2.3 cutoff 역산 — 왜 `visible_at ≤ evaluatedAt`을 as_of 상한으로 바꿔도 되는가

**보조정리 1**: 평가일 `D`가 그리드일이고 `evaluatedAt = kstToUtc(D, confirmTime)`, `visibleAt =
kstToUtc(visDay, confirmTime)`이면 `visibleAt ≤ evaluatedAt ⟺ visDay ≤ D`.
**보조정리 2**: `visDay(as_of) = firstGridDayOnOrAfter(as_of + L)`은 as_of에 대해 단조 비감소이고,
`firstGridDayOnOrAfter(x) ≤ D ⟺ x ≤ D`(D가 그리드일이므로).
∴ `visibleAt ≤ evaluatedAt ⟺ as_of ≤ D − L`.

| calendar kind | `L` | as_of 상한 | 정본 |
|---|---|---|---|
| `krx`·`fx` | 0 | `as_of ≤ D` | `run_replay.py:232` |
| `us_market` | 1일 | `as_of ≤ D − 1일` (= `as_of < D`) | `run_replay.py:228` |
| `fred`(·`ecos`) | `source.lag_days` | `as_of ≤ D − lag` | `run_replay.py:230-231` |
| 2계열 이상 | 각자 | **각 상한의 최솟값**(max→min 반전, worst-of-inputs) | `run_replay.py:255-274` |

이 역산은 C안이 라운드 3에서 "수학적으로 정확" 판정을 받은 그 규칙이며, 본 계획은 위 보조정리 2개로
**전제(D가 그리드일 · evaluatedAt이 confirmTime 고정)까지 명시**한다. 프리뷰는 이 전제를 만족하지 않으므로
역산을 쓰지 않고 §2.5.4의 일반화 규칙을 그대로 적용한다(그래서 프리뷰 쿼리의 `asOfCutoff`는
`kstDate(now)`에 대한 같은 표를 쓰되, 판정은 조회 후 ⑤에서 다시 한 번 수행한다 — **조회는 최적화, 판정은 ⑤가 정본**).

> **규율**: cutoff 역산은 **조회 범위를 좁히는 최적화**일 뿐이다. 최종 가시 판정은 항상 ⑤의
> `visibleAt` 함수가 내린다(이중 진실 금지). 증인 W-Q2가 "역산을 끄고 전량 조회해도 결과 동일"을 단언한다.

### 2.3 단계 ③ — 시계열 구성

#### 2.3.1 지표 → (계열, 필드) 매핑 (정본: `_BUILDERS`, 임의 판단 금지)

| 지표 | 조회 (계열, 필드) | 가시성 입력 계열 | 비고 |
|---|---|---|---|
| vix_level_z | (^VIX, close) | ^VIX | |
| vix_term_structure | (^VIX, close), (^VIX3M, close) | ^VIX, ^VIX3M | union 정렬 → 한쪽 결측일은 NaN |
| move_index_z | (^MOVE, close) | ^MOVE | 2026-07-17 이후 절단(K-01/K-18) |
| hy_oas_delta | (BAMLH0A0HYM2, value) | BAMLH0A0HYM2 | + 같은 계열을 **레벨 보조 조회**로 재사용 |
| ust_2s10s_move | (T10Y2Y, value) | T10Y2Y | |
| dxy_z | (DX-Y.NYB, close) | DX-Y.NYB | |
| spx_drawdown_momentum | (^GSPC, close) | ^GSPC | **KnownSeries 2개**(dd/neg_z) — 각각 독립 lookup·독립 stale |
| global_corr_break | (^GSPC, close), (KRX:1001, close) | ^GSPC, KRX:1001 | 출력 인덱스 = KRX 날짜, 가시성은 **^GSPC 규칙이 지배**(L=1) |
| vkospi_z | (KRX:VKOSPI, close) 있으면 그것, 없으면 (KRX:1001, close) | 선택된 계열 | 폴백은 **데이터로 판정**(관측 0건이면 폴백) |
| kospi_drawdown | (KRX:1001, close) | KRX:1001 | |
| foreign_net_sell_kospi | (KRX:investor_foreign_kospi, net_buy_value) | 동 | |
| kospi_volume_distribution | (KRX:1001, close), (KRX:1001, trading_value) | KRX:1001 | 2필드·1계열 |
| usdkrw_z | (KRW=X, close/high/low) | KRW=X | high/low/prev_close는 **보조 조회** |
| krx_credit_spread_delta | (ECOS:corp_aa3y, value), (ECOS:ktb_3y, value) | 두 계열 | K-04 실측 후 확정, `lag_days: 1` |
| kr_cds_5y_delta | (G-4 결정) | — | (b) 채택 시 상시 결측(정상) |

#### 2.3.2 웜업 충족 — 결측과 구분되는 3번째 상태

`requiredRows(지표)`를 **transform 문자열에서 도출**한다(코드 리터럴 0 — CLAUDE.md §1):

```
requiredRows = 1
             + Σ(transform의 최상위·중첩 window= / lookback= 정수)      # parse_call_kwargs 재사용
             + Σ(식별자 접미사 _Nd 의 N)                                 # pct_change_5d → 5, _20d → 20
                                                                        # (parse_fallback_window의 정규식 재사용)
```

보수적 상한이다(과대평가는 안전 — 값에 영향 없이 충족 기준만 높인다). 산술을 그대로 노출해 감사 가능하게 둔다:

| 지표 | 도출 | `requiredRows` | 정확한 필요 행 수(참고) |
|---|---|---|---|
| spx_drawdown_momentum | 1 + 60 + 252 + 5 | **318**(최대) | 257 |
| vkospi_z(폴백 경로) | 1 + 252 + 20(`_20d`) | 273 | 272 |
| dxy_z | 1 + 252 + 5(`pct_change_5d`) | 258 | 257 |
| foreign_net_sell_kospi | 1 + 252 + 5 | 258 | 256 |
| usdkrw_z | 1 + 252 + 1(`pct_change_1d`) | 254 | 253 |
| vix_level_z · move_index_z | 1 + 252 | 253 | 252 |
| **global_corr_break** | **1 + 20 + 120** | **141** | 140 |
| kospi_drawdown · kospi_volume_distribution | 1 + 60 | 61 | 60 |
| hy_oas_delta · ust_2s10s_move · krx_credit_spread_delta · kr_cds_5y_delta | 1 + 5 | 6 | 6 |
| vix_term_structure | 1 | 1 | 1 |

`warmup_padding_days = 550`은 KR 기준 ≈376 거래일 / US 기준 ≈383 거래일을 담아 최대 318을 **20% 이상 여유로** 덮는다.
(라운드 4 정정: `global_corr_break`를 142로 오기했다 — 도출식 `1+20+120 = 141`이 옳다. 정확한 필요 행 수 140보다
여전히 크므로 보수 상한 방침과 판정 결과는 불변이다.)

- 조회 창 안의 실제 행 수 `< requiredRows`면 그 지표의 상태는 `WARMUP_INSUFFICIENT`다.
  값 계산은 결국 NaN이므로 severity는 `null`(= 결측과 동일한 엔진 거동, 의미론 변경 0)이지만,
  **진단·UI·`tick_run` 기록에서는 `MISSING`과 구분**한다. 구분이 없으면 설치 첫날의 "전 지표 NaN"이
  D-25 §3 동결로 조용히 흡수되어 정상 동작처럼 보인다(R-B12와 같은 실패, 본 계획은 이를 **관측 가능**하게 만든다).
- **부트스트랩 게이트(§7 D-D4)**: 활성·수집 가능 지표 전부가 웜업 충족일 때 첫 확정 틱을 생성한다.
  그 전까지는 `tick_input`에 행을 만들지 않는다 — 전량 fold의 1번째 원소가 저품질 틱이 되는 것을 막는다.

#### 2.3.3 정렬·보조 조회 3규칙 (패리티 함정)

1. **다필드 동일 인덱스**: `KRW=X`의 close/high/low는 같은 as_of 집합을 갖는다고 가정하지 않는다.
   보조값은 **주 계열 값의 `row_date`로 조회**하고, 없으면 modifier 미적용(정본 `run_replay.py:698-709`).
2. **`prev_close`는 달력 전일이 아니라 직전 관측 행**이다(`close.shift(1)`, `run_replay.py:578`).
   연휴·휴장 구간에서 달력 전일로 구현하면 `usdkrw_intraday_force`의 분모가 달라진다.
3. **`_align_to_ffill`은 `global_corr_break` 전용 재량 규칙**이다(`run_replay.py:501-509`):
   `union(SPX index, KOSPI index)`로 reindex → ffill → KOSPI index로 재reindex. **causal**(과거만 참조)이며,
   다른 지표에 적용하면 안 된다. 이식 시 함수 이름·주석까지 그대로 옮기고 적용 지점을 1곳으로 고정한다.

### 2.4 단계 ④ — transform (원계열 전체, causal)

#### 2.4.1 전치 정리 — N-1이 요구한 근거

**정의**: `T`가 causal ⟺ 출력의 `t`번째 원소가 입력의 `≤ t` 원소에만 의존.
`engine_ref.transforms`의 rolling 계열은 전부 이 성질을 가지며 `tests/test_engine_ref.py:178
test_prefix_stability_no_lookahead`가 실행 가능한 증인이다.

**정리**: prefix 필터 `P_c(X) = {x ∈ X : as_of(x) ≤ c}`에 대해 `P_c(T(X)) = T(P_c(X))`.
∴ **"cutoff까지 전량 조회 → transform"과 "전 기간 transform → cutoff 이하 조회"는 같은 값**을 낸다.
이것이 프로덕션이 정본(`run_replay`가 창 전체를 한 번 transform)과 값이 같은 이유이며, 동시에
프로덕션이 **유한한 조회 창**으로 그 값을 얻을 수 있는 이유다(rolling은 고정 길이 창이므로
`requiredRows` 이상만 있으면 tail 값은 무한 히스토리와 **비트 동일**하다 — 근사가 아니다).

**등식이 깨지는 3가지 변형**(전부 prefix 필터가 아님 — 라운드 3 판정의 정확한 일반화):

| 변형 | 결과 |
|---|---|
| as-of **최신 1행** 조회 후 transform | rolling 입력 부족 → 항상 NaN. 사슬 전체가 무동작 |
| **스테일 행 제거 후** transform | 중간이 뚫린 계열 → rolling 창 구성이 달라짐. 스테일은 ⑤에서 **판정만** 하고 ④ 입력을 건드리지 않는다 |
| **결측 보간(carry-forward) 후** transform | 인공값이 rolling 통계에 섞임 → 확정 틱과 프리뷰가 다른 transform 세계. **D-23 §23.3-4의 구조적 근거**(§2.6.3) |

#### 2.4.2 이식 정밀도 체크리스트 (`engine_ref/transforms.py` 1:1)

| 항목 | 규약 | 정본 |
|---|---|---|
| 표준편차 | **ddof=1(표본)** — pandas `rolling().std()` 기본 | `transforms.py:28` |
| 창 하한 | `min_periods=window` → 앞 `window−1`개 NaN | `transforms.py:27-28,58,71,77,81,102` |
| NaN 계수 | 창 내 **non-NaN 개수**가 window 이상일 때만 값 산출(pandas 규약) | 동 |
| `gated` | `where(mask, 0.0)` — **mask=False면 z가 NaN이어도 0.0** ★ | `transforms.py:91` |
| gate 마스크 | `daily_return`이 NaN → 비교 False → 0.0 (첫 행이 결측이 아니라 severity 0) | `transforms.py:86` |
| `pct_change` | ×100 (%) | `transforms.py:44,48` |
| `drawdown_from_high` | `(rolling_high − x)/rolling_high × 100`, 양수 = 하락 | `transforms.py:59` |
| `ratio` | 인덱스 union 정렬, 한쪽 결측 → NaN | `transforms.py:34` |
| `realized_vol` | `std(r/100) × sqrt(252) × 100`, window는 폴백 ID에서 파싱 | `transforms.py:101-102` |
| 자료형 | 전부 Double(float64), 반올림은 표시 계층만(K-07) | 모듈 docstring |

`gated`의 NaN→0.0은 **`kospi_volume_distribution`이 웜업 중에도 severity 0을 내는** 비직관적 거동을 만든다.
퇴화 증인 W-T4가 이를 고정한다(고치는 것이 아니라 정본과 같게 유지하는 것이 목적).

#### 2.4.3 `_BUILDERS` 이식 규율

`run_replay.py:434-606`의 13개 빌더는 **indicators.yaml의 transform 문자열 해석 결과**다. Kotlin은
같은 13개를 1:1로 갖되, 파라미터는 전부 `parse_call_kwargs`/`parse_gate`/`parse_fallback_window` 대응
파서에서 온다(리터럴 0). 추가로 프로덕션 전용 빌더 2개(`krx_credit_spread_delta`, G-4 채택 시 `kr_cds_5y_delta`)를
만든다 — 픽스처에는 입력 계열이 없으므로 **빈 입력 → 결측**이 자동 성립해 `_ALWAYS_MISSING_INDICATORS`
(`run_replay.py:105`)와 같은 특수 케이스 없이 BT-05가 통과한다. 증인 W-T5가 "빈 입력 → severity null"을 단언한다.

### 2.5 단계 ⑤ — 가시성 / 스테일

#### 2.5.1 3규칙의 1규칙 환원 (증명)

`_first_grid_day_after(g, T)`는 `bisect_right(g, T)`, `_first_grid_day_on_or_after(g, T+1일)`은
`bisect_left(g, T+1일)`이다. 정렬된 날짜 배열에서 둘 다 **"T보다 큰 첫 원소의 인덱스"**로 동일하다
(`run_replay.py:212-219`). ∴

```
visDay(seriesId, asOf, grid) = firstGridDayOnOrAfter(grid, asOf + L(seriesId))
visibleAt(...)               = kstToUtc(visDay, confirmTimeKst)          // 없으면 null
combinedVisibleAt(inputs, asOf) = inputs.map{ visibleAt(it, asOf) }.let{ if (null in it) null else it.max() }
```

`L` 표(프로덕션 — **하니스 `calendar_kind`는 픽스처 범위 휴리스틱이므로 그대로 쓰지 않는다**):

| 판정 근거(indicators.yaml `source`) | 계열 예 | kind | `L` |
|---|---|---|---|
| `provider: pykrx`/KRX, `provider: krx_notice` | KRX:1001, KRX:VKOSPI, KRX:investor_* | krx | 0 |
| `symbol: "KRW=X"` | KRW=X | fx | 0 |
| `provider: yfinance` (KRW=X 제외) | ^VIX, ^VIX3M, ^MOVE, ^GSPC, DX-Y.NYB | us_market | **1일** |
| `provider: fred` | BAMLH0A0HYM2, T10Y2Y | fred | `source.lag_days`(=1) |
| `provider: ecos` | ECOS:corp_aa3y, ECOS:ktb_3y | kr_lagged | `source.lag_days`(=1) |
| `provider: scrape_wgb` | KR_CDS_5Y | krx류 | 0 (G-4 (a) 채택 시) |

> **발견 사항(중요)**: `fixture_schema.calendar_kind`(L157-178)는 **"그 외 전부 us_market"** 폴백을 갖는다.
> ECOS·CDS 계열 ID를 그대로 넣으면 `us_market`으로 오분류되어 `L=1`이 적용된다 — 우연히 lag_days=1과 같아
> **오늘은 값이 맞지만 근거가 틀린** 상태가 된다. 프로덕션 매핑은 위 표대로 `source`에서 파생하고,
> **픽스처 8계열에 대해 두 매핑이 일치함을 단언하는 테스트**(W-V5)를 둔다. 이것이 하니스 휴리스틱을
> 프로덕션에 그대로 복사하지 않는 유일한 지점이다.

#### 2.5.2 출력 시계열에 가시성 색인 → 틱별 lookup

정본 `build_known_series`(L289-317) / `lookup_known`(L320-327)의 의미 그대로:

```
KnownSeries = 오름차순 (rowDate, visibleAt, value) 3열   // NaN 행은 제외(L300-301), visibleAt null 행 제외(L311-312)
lookup(evaluatedAt) = visibleAt ≤ evaluatedAt 인 마지막 원소   // 이진탐색. 없으면 null
```

- **정렬 근거**: `visibleAt`은 `rowDate`에 대해 단조 비감소(§2.2.3 보조정리 2 + max는 단조 보존)이므로
  `rowDate` 정렬이 곧 `visibleAt` 정렬이다(정본 주석 L316). 이진탐색의 전제이며, Kotlin에서도
  **정렬 후 단조성 단언**을 assert로 남긴다(퇴화 증인 W-V6).
- `spx_drawdown_momentum`은 **KnownSeries 2개**(dd, neg_z)를 갖는다. 두 계열은 시작 rowDate가 다르고
  (dd는 60행, neg_z는 258행 후 시작) **각각 독립적으로 lookup·stale 판정**된 뒤 `combine_max`된다
  (`run_replay.py:484-498, 656-669`). 하나로 합치면 초기 구간에서 값이 달라진다.

#### 2.5.3 스테일 판정

```
staleWindow(profile, cadence) = engine.stale_profiles[profile][cadence]
                                 ?: engine.stale_profiles[profile]["daily_kr"]      // registry.py:313
isStale = (evaluatedAt − visibleAt) > staleWindow                                   // ★ 초과만 stale, 등호 fresh
```
- 기준 시각은 **`visible_at`**이다(`run_replay.py:352-369`의 실측 근거 주석). `as_of`(달력 자정)를 쓰면
  결측이 낀 전 틱에서 판정이 갈린다.
- **등호 규약**: `>` — `registry.py:323`·`run_replay.py:369` 둘 다 초과만 stale. mobile_daily의 창은
  30h/48h/96h이고 확정 틱 간격이 정확히 24h의 배수라 **경계에 정확히 걸리는 케이스가 실재**한다(증인 W-V4).
- **cadence 폴백**: `mobile_daily`에 `intraday_30m` 키가 없다(`indicators.yaml:240`) →
  `usdkrw_z`·`vkospi_z`·`kospi_drawdown`(가중 8.0/31.0)이 **30h(daily_kr) 창**을 받는다. yaml 본문에 보이지
  않는 규칙이므로 전용 단언(W-V7)이 필수다.
- `naive datetime` 금지(K-05): 전 계층 UTC aware, 표시만 KST. Kotlin은 `Instant`만 쓰고
  `LocalDateTime` 단독 사용을 금지한다(아키텍처 스캔).

#### 2.5.4 프리뷰 시계 (M-39의 답) — 확정 틱 규칙의 일반화

**문제**: 확정 틱의 가시 판정은 `visibleAt ≤ evaluatedAt`이고 `visibleAt`은 **그날 17:00**이다.
프리뷰를 `evaluatedAt = now`로 그대로 돌리면 화요일 13:00에 **월요일 미국 종가(이미 05:00 KST에 공개됨)가
보이지 않는다** — 프리뷰가 하루 뒤처진다. 반대로 C안처럼 `observed_at`을 시계로 쓰면 **죽은 계열이
영원히 fresh**가 된다(라운드 3 C-13, 중대 결함).

**채택 규칙(AD-D1)** — 판정을 **일(day) 단위**로 완화하고 나이를 0에서 클램프한다:

```
visibleInPreview(series, asOf, now) ⟺ visDay(series, asOf, grid) ≤ kstDate(now)
age(series, asOf, evalAt)           =  max(0, evalAt − kstToUtc(visDay, confirmTimeKst))
isStale                             =  age > staleWindow(mobile_daily, cadence)
```

**정리(확정 틱 무영향)**: 확정 틱은 `evalAt = kstToUtc(D, confirmTime)`이고 `D`는 그리드일이므로
(a) `visDay ≤ kstDate(evalAt) = D ⟺ visibleAt ≤ evalAt` (보조정리 1) — 가시 판정 동일,
(b) `evalAt − visibleAt = kstToUtc(D,ct) − kstToUtc(visDay,ct) ≥ 0` — 클램프가 항상 no-op.
∴ **확정 틱 경로에서 이 일반화는 정본과 비트 동일**이며 BT-05는 영향받지 않는다(증인 W-P1이 두 형태의
동치를 9창 전 틱에서 단언한다).

**프리뷰에서의 거동(전부 D-23과 정합)**

| 상황 | 판정 | 귀결 |
|---|---|---|
| 화 13:00, 월요일 US 종가(as_of=월) | visDay=화 ≤ 화 → 가시, age = max(0, 13:00−17:00) = 0 → fresh | 프리뷰가 최신 US 정보를 반영 |
| 화 13:00, 전일 KR 종가(as_of=월) | visDay=월, age = 20h < 30h → fresh | 국내 침묵이 점수에 남는다(D-23 §23.2 왜곡 완화) |
| **월 13:00, 직전 KR 종가(as_of=금)** | age = 68h > 30h → **stale = 결측** | KR 4지표 결측 → raw coverage 21.0/31.0 = **67.7%** → §23.3-3 억제 발동 |
| 죽은 계열(^MOVE, as_of=2026-07-17에서 절단) | age가 매일 증가 → stale | **영원-fresh 실패 모드 없음**(as_of 기반 시계) |
| 같은 값 재수집 | `observed_at`만 갱신, `as_of` 불변 → age 불변 | 나이 리셋 없음 |
| 캐치업(과거 일자를 오늘 수집) | age = evalAt(D 17:00) − visibleAt ≥ 0 | 음수 나이 붕괴 없음 |
| 프리뷰 lane의 당일 장중 스냅샷(as_of=D) | visDay=D ≤ 오늘 → 가시, age 0 | **프리뷰에서만** 잠정값으로 사용(lane 필터, §2.1) |

**분기 비용**: 코드상 분기는 `visible` 비교를 시각 비교 대신 **일 비교**로 쓰는 한 줄과, `age`의 클램프 한 줄이다.
두 경로가 서로 다른 시계·서로 다른 함수를 갖지 않는다 — X-9(프리뷰 시계 이탈)의 해소안으로 상신한다(§7 D-D2).

#### 2.5.5 프리뷰 나이 산식 — 실경과(본 계획) vs 24h 양자화(C안) 대비 (M-39 병합 결정 자료)

두 안은 **가시 판정에서는 일치**하고(둘 다 일 단위) **나이 산식에서만** 갈린다.

| | 본 계획 (D) | C안 |
|---|---|---|
| 나이 | `max(0, now − kstToUtc(visDay, confirmTime))` — **실경과 시간** | `(kstDate(now) − visDay) × 24h` — **확정 틱 간격의 정수배** |
| 값 domain | 연속 | {0, 24h, 48h, 72h, …} |
| 확정 틱에서 | 정본과 동일 | 정본과 동일(간격이 정확히 24h 배수라 두 안이 일치) |

**확정 틱에서 두 안은 구분되지 않는다** — 그래서 **BT-05는 이 결정을 판정하지 못한다**(패리티로는 선택 불가,
설계 논증으로만 결정된다). 실제 차이는 프리뷰의 억제 시점 하나뿐이다:

| cadence(창) | D안 억제 시작 | C안 억제 시작 | 차이 |
|---|---|---|---|
| `daily_kr` 30h | visDay+1일 **23:00 KST** 이후 | visDay+2일 전체(48h) | 최대 18h 이르게 |
| `daily_us` 48h | visDay+2일 **17:00** 이후 | visDay+3일(72h) | 최대 24h 이르게 |
| `fred_daily` 96h | visDay+4일 **17:00** 이후 | visDay+5일(120h) | 최대 24h 이르게 |

**본 계획이 실경과를 택하는 근거 4**

1. **정본이 실경과다.** `run_replay.py:369`·`registry.py:323` 모두 `(evaluated_at − visible_at) > window`인
   시간 뺄셈이다. 양자화는 정본에 없는 **새 연산**이며, 프리뷰가 정본이 정의하지 않은 영역이라는 이유로
   새 연산을 도입하는 것보다 **정본 식을 그대로 확장**하는 쪽이 이식 표면이 작다(§2.5.4의 클램프 1개가 전부).
2. **SSOT 숫자의 의미가 보존된다.** `daily_kr: 30h`를 양자화하면 실효 창이 48h가 되어 **설정에 적힌 값과
   코드가 적용하는 값이 달라진다**(CLAUDE.md §1의 정신에 어긋난다). 특히 `daily_us 48h`는 BT-03 스윕이
   3값 비교 끝에 선정한 값인데, 프리뷰에서만 72h로 해석되면 그 선정 근거가 무의미해진다.
3. **억제 방향이 보수적이다.** 실경과가 더 이르게 stale → 결측 → carry-forward + raw coverage 하락 →
   D-23 §23.3-3 억제. 프리뷰에서 오래된 값을 "신선"으로 표시하는 것보다, 판정을 보류하는 쪽의 실패 비용이 낮다
   (프리뷰는 잠정 경보를 발신할 수 있는 경로다).
4. **경계가 자정에 걸리지 않는다.** 양자화는 KST 자정에 나이가 24h 점프하므로, 23:59와 00:01에 돌린
   두 프리뷰의 배지가 뒤집힌다. 실경과는 창을 넘는 그 순간에만 한 번 바뀐다.

**C안 논거의 정직한 기록**: "몇 틱 전 데이터인가"를 세는 것이 틱 단위 히스테리시스 사고와 일관되고,
저녁 프리뷰가 갑자기 "국면 판정 불가"로 바뀌는 것이 사용자에게 놀라움일 수 있다. 반론: 그 표시는
"KR 데이터가 30시간 묵었다"는 **사실의 표현**이고, 그 시점은 다음 확정 틱까지 18시간이 남은 때다.
23:00 이후 프리뷰는 다음 개장 전이라 판정 가치도 낮다.

**병합 시 보존해야 할 불변식**: 어느 쪽을 채택하든 **확정 틱 경로의 나이 산식은 정본 실경과 그대로**여야 한다
(양자화를 채택하더라도 프리뷰 한정). 이 조건이 깨지면 BT-05 L2/L4가 결측 낀 틱에서 파손된다. → §7 D-D2.

### 2.6 단계 ⑥ — Tick 조립

#### 2.6.1 지표별 severity 해결 (정본 `resolve_severity` L621-716)

```
kind=simple      : lookup → stale? → null : (classify_severity(v), is_extreme(v))
kind=combine_max : lookup_a / lookup_b 각각 → 각각 stale 판정 → combine_max_severity, is_extreme = false(항상)
kind=hy_oas      : simple + hy_oas_level(row_date 조회) → apply_hy_level_boost(severity)   // is_extreme는 부스트 전 원값 기준
kind=usdkrw      : simple + high/low/prev_close(row_date 조회) → apply_usdkrw_intraday_force
```
**함정 3**:
(i) `is_extreme`는 **modifier 적용 전 원값**으로 계산하고 modifier가 바꾸지 않는다(`L679,686` / `L697,713`).
(ii) `combine_max`는 `is_extreme`를 **정의하지 않고 항상 false**다(`L634` — thresholds가 중첩 구조).
(iii) `usdkrw_intraday_force`는 **결측 기저도 승급**시킨다(`modifiers.py:41-50`) — crit 게이트에서 null→3.
    즉 **결측 지표가 강제로 유효 지표가 되어 분모에 들어온다**. coverage 계산이 이 순서 뒤에 와야 한다.

#### 2.6.2 틱 집계 (정본 `L784-829`)

| 산출 | 규칙 | 정본 |
|---|---|---|
| `severities` | 지표 → int\|null. **순회 순서 = indicators.yaml 선언 순서**(LinkedHashMap 규율) | `scoring.py:120-131` |
| `composite` | `100 × Σ(w·s)/Σ(w·3)`, 결측은 분자·분모 동시 제외, 유효가중 0 → **null** | `scoring.py:120-132` |
| `coverage` | 유효가중 / 전체가중(= enabled 15지표 합 31.0) | `scoring.py:130` |
| `distinct_axes` | severity ≥ 2인 축의 종류 수 | `scoring.py:148-151` |
| `any_crit` | `s ≥ 3`(== 아님 — 옵션 B 일반화) | `run_replay.py:801` |
| `any_extreme` | 지표별 `is_extreme` OR | `run_replay.py:802` |
| `fired_axes` | severity ≥ 2 축 집합(정렬) | `run_replay.py:806-808` |

부동소수 누적 순서를 YAML 선언 순서에 맞추는 것이 `|Δ| = 0`을 얻는 유일한 방법이다(A·B와 동일 판정).

#### 2.6.3 carry-forward는 이 단계에서만 일어난다 (D-23 §23.3-1의 실행 가능한 정의)

```
확정/캐치업 :  severities = observed                                  // 이월 없음(D-23 §23.3-4)
프리뷰      :  observed  = 위 ⑥ 산출(결측 = null)
               carried   = {id : lastFrozenSeverity(id)  |  observed[id] == null}   // 원천 = 최신 tick_input.severities_json
               rawCoverage   = coverage(observed)                     // ★ 억제 판정의 정본
               composite     = compute(observed ⊕ carried).score      // 분모 유지 = D-23 왜곡 방지
               suppressed    = rawCoverage < engine.preview_coverage_min
```
- **이월 원천이 원장이 아니라 `tick_input` 동결본**이라는 점이 핵심이다. 그래서 (a) 이월값은 Room의
  `observation`에 절대 쓰이지 않고(§23.3-1 자동 충족) (b) 이월이 ④ transform 입력에 섞이지 않아
  §2.4.1 등식이 보존되며 (c) 이월값의 `as_of`·스테일 배지 표기 원천이 그 틱의 날짜로 명확해진다.
- `observed`와 `carried`는 **서로 다른 자료구조**로 분리한다 — 하나의 맵을 공유하면 rawCoverage가
  오염되어 §23.3-3이 죽은 조문이 된다(B §10.1.1과 동일 판정, 본 계획은 "억제는 raw로 키잉"을 지지한다).
- 억제 임계 `0.80`은 SSOT에 없다 → §8 제안 2가 MT1-07 착수의 선행 조건이다.

#### 2.6.4 확정 틱의 산출물 동결

`tick_input`에 `Tick` 4필드(`composite` NULL 허용 · `distinct_axes` · `any_crit` · `any_extreme`)와
감사 컬럼(`coverage`, `severities_json`, `visible_at_by_indicator_json`, `evaluated_at`, `registry_version`,
`is_catchup`, `warmup_status_json`)을 append-only로 동결한다. **fold가 읽는 것은 앞의 4열뿐**이며
나머지는 구조적으로 판정에 영향을 줄 수 없다(A §2.10·B §5.6.2(b)와 동일 — 본 계획은 `warmup_status_json`
1열을 추가 요구한다: §2.3.2의 3번째 상태를 사후 감사 가능하게 한다).

### 2.7 단계 ⑦ — fold

수렴 사실 그대로 채택한다(A AD-A11 / B §5.6 / C 9-B-4(a)).

```kotlin
val frozen: List<Tick> = tickInputDao.allOrderedByDate().map { it.toTick() }   // 4열만
val timeline = StateMachine.run(frozen, profile, config)                        // engine_ref.statemachine.run 이식
val phaseToday = timeline.last()
```
- `run()`은 초기 상태 주입구가 없다(`statemachine.py:114-120`) → **국면을 이어받지 않고 매 틱 재산출**한다.
- 프로덕션이 D-26을 실제로 태우는지는 `_escape_blocks_exit`(L95-103) 경로가 fold 안에서 실행되는지로만
  증명된다 → 증인 W-S4(any_extreme 지속 중 exit_ORANGE 차단).
- 캐치업 절단 구간은 **틱 부재**(B) 또는 **`composite=NULL` 동결**(A) 중 어느 쪽이어도 관측 가능 귀결이
  동치라는 라운드 3 판정을 승계한다. 본 계획은 **엔진 무변경**을 요구할 뿐 둘 중 하나를 강제하지 않는다
  (병합 결정 사항 — 단, 어느 쪽이든 `tick_run`에 `gap` 레코드와 UI 배지가 따라야 한다).
- `phase_commit`은 fold 결과의 마지막 원소를 기록하고, **과거 행이 이번 fold의 대응 원소와 일치하는지 검증**한다
  (불일치 = `tick_input` 훼손 → 틱 실패 처리, 조용한 덮어쓰기 금지).

### 2.8 세 경로의 공유·분기 (분기점은 정확히 3개)

| 단계 | 확정 틱 | 캐치업 | 프리뷰 |
|---|---|---|---|
| ① 원장 쓰기 | `lane=0` | `lane=0` | **`lane=1`** |
| ② 조회 | `includePreviewLane=0`, cutoff=f(D) | 동일 | **`includePreviewLane=1`**, cutoff=f(오늘) |
| ③ 시계열 구성 | 동일 함수 | 동일 | 동일 |
| ④ transform | 동일 함수 | 동일 | 동일 |
| ⑤ 가시성/스테일 | `evalAt = D@confirmTime` | 동일(**D의 확정 시각**) | **`evalAt = now`** |
| ⑥ Tick 조립 | 이월 없음 | 이월 없음 | **+ carry-forward, + rawCoverage, + 억제** |
| ⑦ fold·커밋 | 실행 | 실행(일자 오름차순 1일 1커밋) | **미실행**(국면 비커밋, D-17 §1) |

**분기 3개**: `lane` 파라미터 · `evalAt` 인자 · fold 호출 여부(+ ⑥의 이월 데코레이터).
전부 **호출자가 넘기는 값**이고 엔진 내부 분기가 아니다 — 이것이 "프리뷰 전용 코드 경로 분리"(TASK MT1-07 ②)를
타입·모듈로 강제할 수 있는 이유다(A의 `ConfirmInputs`/`PreviewInputs` 타입 분리와 정확히 호환).

### 2.9 단계별 증인 테스트 (퇴화 입력 포함 — REVIEW_M0 규율 ①)

| ID | 단계 | 시나리오 | 기대 |
|---|---|---|---|
| W-L1 | ① | `UPDATE observation …` 원시 SQL | `SQLiteConstraintException`(트리거 ABORT) |
| W-L2 | ① | 같은 셀 같은 값 재삽입 | 행 수 불변(revision 미증가) |
| W-L3 | ① | **lane 1에 값 v 기록 후 lane 0에 같은 v 수집** | lane 0 행이 **생성됨**(lane을 dedup 키에서 빼면 실패하는 퇴화 증인) |
| W-L4 | ① | `^GSPC` 1계열을 2지표가 조회 / `KRW=X` 4필드 | 상호 간섭 0, 각 조회가 정확히 자기 행만 |
| W-Q1 | ② | 확정 틱 D에서 `as_of=D`인 **us_market** 행 존재 | **미선택**(cutoff 역산) |
| W-Q2 | ② | cutoff 역산을 끄고 전량 조회 후 ⑤에서 판정 | **동일 산출**(역산은 최적화라는 단언) |
| W-Q3 | ② | `as_of=D`에 lane 1 행만 존재 | 확정 틱: 미선택 → D−1 값 사용 / 프리뷰: 선택 |
| W-Q4 | ② | 같은 셀 revision 0·1 | revision 1 선택. revision 동률이면 id 큰 쪽 |
| W-Q5 | ② | **빈 원장** | 전 지표 결측 → `composite=null` → D-25 §3 동결(틱 미소비) |
| W-W1 | ③ | 창 안 행 수 = `requiredRows − 1` | `WARMUP_INSUFFICIENT`(≠ MISSING), 부트스트랩 게이트 미개방 |
| W-W2 | ③ | 연휴 낀 `prev_close` | 직전 **관측 행**(달력 전일 아님) |
| W-W3 | ③ | US 휴장·KR 개장일의 `global_corr_break` | ffill 정렬 후 값 산출(NaN 산발 없음) |
| W-T1 | ④ | 전량 입력 vs cutoff 절단 입력 | lookup되는 값 **비트 동일**(전치 정리) |
| W-T2 | ④ | `window−1`행 / `window`행 | NaN / 값(min_periods 경계) |
| W-T3 | ④ | ddof=0으로 변이 | 테스트 **실패**(ddof=1 고정 증인) |
| W-T4 | ④ | 웜업 중 `daily_return ≥ 0`인 날의 `kospi_volume_distribution` | severity **0**(NaN→0.0, 결측 아님) |
| W-T5 | ④ | `krx_credit_spread_delta` 입력 계열 0행 | severity `null`(특수 케이스 코드 없이) |
| W-V1 | ⑤ | `^VIX` as_of=T를 T일 틱에서 조회 | **미가시**(L=1). 규칙을 L=0으로 변이하면 실패 |
| W-V2 | ⑤ | FRED as_of=T(lag 1)를 T일 / T+1 거래일 틱 | 미가시 / 가시 |
| W-V3 | ⑤ | KRX as_of=휴장일 관측 | 다음 거래일 확정 틱에 최초 가시 |
| W-V4 | ⑤ | `evalAt − visibleAt` = 창 정확히 / 창+1ms | **유효** / 결측(등호 fresh) |
| W-V5 | ⑤ | 프로덕션 `L` 매핑 vs `fixture_schema.calendar_kind` | 픽스처 8계열 전건 일치 |
| W-V6 | ⑤ | KnownSeries 정렬 후 단조성 | `visibleAt` 비감소 assert 통과 |
| W-V7 | ⑤ | mobile_daily × `intraday_30m` | 30h(daily_kr 폴백) |
| W-V8 | ⑤ | `global_corr_break` 결합 가시성 | `max(^GSPC, KRX:1001)` = **^GSPC 규칙**. KR 단독 구현이면 하루 선행(look-ahead) |
| W-P1 | ⑤ | 시각비교형 vs 일비교+클램프형 | 9창 전 틱 **동일**(프리뷰 일반화의 확정 틱 무영향 증명) |
| W-P2 | ⑤ | 월요일 장중 프리뷰(KR 마지막 = 금요일) | KR 4지표 stale → rawCoverage 67.7% → 억제 |
| W-P3 | ⑤ | ^MOVE 절단 후 30일 경과 프리뷰 | stale(영원-fresh 아님) |
| W-A1 | ⑥ | 값 == crit 임계 | severity 3(등호 포함) |
| W-A2 | ⑥ | 결측 1지표 | 분자·분모 동시 제외, coverage 감소 |
| W-A3 | ⑥ | `usdkrw` 일중폭 ≥ crit + 기저 결측 | severity 3(결측 승급) + coverage 증가 |
| W-A4 | ⑥ | hy_level_boost 적용 틱 | severity 부스트되나 `any_extreme`는 원값 기준 불변 |
| W-A5 | ⑥ | `spx_drawdown_momentum` 한 성분만 stale | 남은 성분으로 판정(0 대입 아님) |
| W-A6 | ⑥ | 전 지표 결측 | `composite = null`, coverage 0 |
| W-S1 | ⑦ | D1~D5 AMBER 유지 후 D6 실행 | AMBER. `run([오늘틱])`으로 변이하면 GREEN → 실패 |
| W-S2 | ⑦ | 같은 원장 2회 실행 | 타임라인 비트 동일, 행 수 불변 |
| W-S3 | ⑦ | 공백 구간 통과 | 공백 이전 국면·카운터 보존 |
| W-S4 | ⑦ | `any_extreme` 지속 중 exit_ORANGE 조건 | **이탈 차단**(D-26이 프로덕션 fold에서 실제 발화) |

> W-S1 비공허성(라운드 3 A-12): `promote_sustain_ticks = 1`이므로 D6 단독으로도 AMBER가 가능하다.
> **D1~D5는 AMBER 유지 조건, D6은 유지 미충족·이탈 미충족**(예: composite가 exit_AMBER 14 이상 20 미만)으로
> 구성해야 "이어받았다"를 증명한다 — 이 구성 요건을 브리프에 명시한다.

### 2.10 BT-05가 사슬의 어느 단계까지 덮는가

| 단계 | 픽스처 주입 BT-05가 덮는가 | 덮지 못하는 것 | 보완 장치 |
|---|---|---|---|
| ① 원장 | ✗ | Room 스키마·트리거·revision·lane | W-L1~L4 + **MT1-05k(원장→사슬 e2e, Robolectric 1창)** |
| ② 조회 | △ | SQL 형태·인덱스·lane·tie-break | W-Q1~Q5 + MT1-05k |
| ③ 시계열 | ✓ (L0/L1) | — | — |
| ④ transform | ✓ (L1) | — | W-T1~T5(프로덕션 절단 경로) |
| ⑤ 가시성/스테일 | ✓ (L0) | **프리뷰 시계**(픽스처는 1일 1틱) | W-P1~P3 |
| ⑥ Tick 조립 | ✓ (L2/L3) | 프리뷰 이월·억제 | MT1-07c/d |
| ⑦ fold | ✓ (L4/L5) | **캐치업 절단 경로**(라운드 3 C-12 지적) | MT1-06g 결정론 테스트 + W-S3 |

**결론**: BT-05는 ③~⑦의 **수치 정합**을 덮고, ①②와 프리뷰·캐치업 경로는 덮지 못한다.
그 공백을 메우는 것이 MT1-05k(원장→사슬 e2e) 1건과 MT1-06g·MT1-07의 증인들이다.
"패리티가 프로덕션 경로를 그대로 덮는다"는 주장은 **③~⑦에 한해 참**이며, 그 경계를 GATE_GM1에 명시 기록한다.

---

## 3. 서브태스크 분해 · 의존성 · 위임

표기: **P** = 병렬 가능 · **S** = 선행 필요. 모델 배정 D-20 §20.2.

### 3.1 MT1-00 실측 선행 (신설)

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-00a | KRX 실측: 로그인 정책, 데이터셋 4종, **확정 시각 프로파일링**(15:35~18:00), `getBusinessDays` 범위 조회 콜 비용, 백필 콜 예산 | — | P | data-verifier | 저널 + 시각별 표 + 재현 절차 |
| MT1-00b | 야후계·Stooq 실측(7심볼 스키마·as_of 규약·차단) | — | P | data-verifier | 저널 + **픽스처 대조표** |
| MT1-00c | **K-04 ECOS item_code + `lag_days` 실측** | — | P | data-verifier | 저널 + §8 제안 4 |
| MT1-00d | G-4 CDS 접근성 실측 | — | P | data-verifier | 저널 + (a)/(b) 상신문 |
| MT1-00e | KIS 가용성 확인 | — | P | data-verifier | 저널 또는 M2 이연 결정문 |
| **MT1-00f** | **SQLite 실측(D 고유)**: minSdk 29 에뮬레이터/실기기에서 `SELECT sqlite_version()`, §2.2.2 쿼리 실행·`EXPLAIN QUERY PLAN` 인덱스 사용 확인, 1만 행 기준 응답 시간 | 01a | S | data-verifier | 저널에 버전·플랜·소요 기록. 윈도우 함수 가용 여부와 **무관하게** 상관 서브쿼리 채택 확인 |

### 3.2 MT1-01 스캐폴드 + SSOT 동기화

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-01a | Gradle 스캐폴드(카탈로그·ktlint·detekt·minSdk 29·JVM/계측 소스셋 분리) | — | P | kotlin-implementer | `./gradlew check` green |
| MT1-01b | `syncConfigs`(configs 5 + prompts 2 → assets) + 매니페스트 | 01a | S | kotlin-implementer | `./gradlew syncConfigs` 후 파일 수 단언 테스트 green |
| MT1-01c | 빌드 배선(드리프트 구조적 차단) | 01b | S | kotlin-implementer | SSOT 1바이트 수정 → assets 자동 갱신 테스트 green |
| MT1-01d | 해시 검증 **JVM** | 01b | S | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*SsotHash*"` green |
| MT1-01e | 해시 검증 **계측**(TASK 명시 요구 — 축소 불가) | 01d | S | kotlin-implementer | `./gradlew :app:connectedDebugAndroidTest --tests "*SsotHashInstrumented*"` green |
| MT1-01f | 커버리지 게이트(코어 ≥90%/기타 ≥70%) `check` 배선 | 01a | S | kotlin-implementer | `./gradlew koverVerify`(또는 jacoco 대응 태스크) green |
| MT1-01g | kotlin_krx 벤더링 + 출처 매니페스트 | 01a | S | kotlin-implementer | 깨끗한 클론에서 `./gradlew :krx:compileKotlin` green |

### 3.3 MT1-02 계약 미러 + 스냅샷

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-02a | Python 스냅샷 생성기 + 정본 인스턴스 + 왕복 테스트 | — | P | python-implementer | `uv run pytest tests/test_contracts_snapshot.py -q` green |
| MT1-02b | Kotlin 미러 데이터클래스 + 제약 `require` | 02a | S | kotlin-implementer | `./gradlew :engine:test --tests "*Contract*"` green |
| MT1-02c | 왕복·형상 다이제스트 교차 검증 | 02b | S | kotlin-implementer | 양측 다이제스트 동일 |
| **MT1-02d** | **⑥ 산출 → `TriggerBlock` 조립 1건을 왕복 테스트에 투입**(미러가 M1 안에서 한 번은 실행되게) | 02b, 06c | S | kotlin-implementer | `*TriggerBlockRoundTrip*` green |

### 3.4 MT1-03 Room 원장 (사슬 ①②)

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-03a | `observation` 엔티티·인덱스 2종·**@Insert 전용 DAO** | 01a | S | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*LakeSchema*"` green |
| MT1-03b | 물리 강제(BEFORE UPDATE/DELETE 트리거) | 03a | S | kotlin-implementer | W-L1 green |
| MT1-03c | **조회 계약**(§2.2.2 쿼리 + cutoff 역산기 + 웜업 충족 계수) | 03a, 05a | S | kotlin-implementer | W-Q1~Q5·W-W1 green |
| MT1-03d | revision·lane dedup(값 변경 시에만 append) | 03a | S | kotlin-implementer | W-L2·W-L3 green |
| MT1-03e | CSV 내보내기 + Drive 백업 훅 + **키·자격증명 제외 규칙**(K-17) | 03a | S | kotlin-implementer | 왕복 테스트 + 백업 제외 파싱 단언 green |
| MT1-03f | `tick_input`·`phase_commit`·`tick_run` 테이블(§2.6.4) | 03a | S | kotlin-implementer | 스키마 + 중복 삽입 ABORT green |

### 3.5 MT1-04 collectors

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-04a | 야후계 6심볼 + Stooq 폴백 | 00b, 03a | P | kotlin-implementer | 픽스처 파싱·오류·폴백 테스트 green |
| MT1-04b | FRED 2계열(`.` → 결측) | 03a | P | kotlin-implementer | 픽스처 테스트 green |
| MT1-04c | KRX via kotlin_krx(지수·수급·VKOSPI·영업일) + 1req/s | 00a, 01g, 03a | P | kotlin-implementer | 픽스처 + 레이트리밋 테스트 green |
| MT1-04d | ECOS 2 item(실측 코드) | 00c, 03a | P | kotlin-implementer | 픽스처 테스트 green |
| MT1-04e | KIS(옵션, 기본 off) 또는 이연 결정문 | 00e, 03a | P | kotlin-implementer | 플래그 off 경로 미진입 테스트 green |
| MT1-04f | G-4 판정 (a)/(b) + UI 배지 | 00d | S | Advisor 상신 → kotlin-implementer | 결정문 + 선택 경로 테스트 green |
| **MT1-04g** | **초기 백필**(warmup_padding_days 범위, 재개 가능, lane=0) + **웜업 충족 리포트** | 04a~d, 03c | S | kotlin-implementer | 15지표 전건 `requiredRows` 충족 단언 green, 중단 후 재개 멱등 green |
| MT1-04h | 수집기 ↔ 픽스처 대조 하니스 | 04a~d, 05g | S | kotlin-implementer | 중첩 구간 전 행 일치(상대 1e-6) green |

### 3.6 MT1-05 엔진 (사슬 ③~⑦) + BT-05

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-05a | 설정 로더 + transform/modifier 문자열 파서 + **`requiredRows` 도출기**(§2.3.2) | 01b | S | kotlin-implementer | 파서 테스트 green + 엔진 소스 임계 리터럴 0 grep |
| MT1-05b | transforms 이식(§2.4.2 체크리스트) | 05a | S | kotlin-implementer | W-T1~T4 + L1 벡터 green |
| MT1-05c | scoring·modifiers 이식 | 05a | S | kotlin-implementer | W-A1~A6 green |
| MT1-05d | statemachine 이식(D-25 §1~4 + D-26) | 05a | S | kotlin-implementer | 증인 10종(F2 7 + D-26 3) green |
| **MT1-05e** | **가시성·스테일 모듈**(`Visibility.kt`·`StalePolicy.kt`, §2.5 전체) | 05a | S | kotlin-implementer | W-V1~V8 green |
| **MT1-05f** | **시계열 조립기**(`SeriesWindow` → `_BUILDERS` 대응 → `KnownSeries` 색인, §2.3·2.5.2) | 05b, 05e | S | kotlin-implementer | W-W1~W3·W-V6·W-T5 green |
| MT1-05g | Python 패리티 내보내기(`backtest/export_parity.py`: raw·grid·visibility·transforms·expected + 매니페스트) | — | P | python-implementer | `uv run python backtest/export_parity.py --check` exit 0 |
| MT1-05h | **BT-05 러너**(9창 × mobile_daily, 계층 판정 L0~L5) | 05b~g | S | kotlin-implementer → backtest-analyst | `./gradlew :engine:test --tests "*Bt05Parity*"` green + 리포트 |
| MT1-05i | `golden_mobile.yaml` 직접 대조 | 05h | S | kotlin-implementer | `--tests "*GoldenMobile*"` green |
| MT1-05j | 가시성 함수 패리티(결합 가시성 포함) + **W-P1**(프리뷰 일반화 동치) | 05e, 05g | S | kotlin-implementer | `--tests "*VisibilityParity*"` green |
| **MT1-05k** | **원장→사슬 e2e 1창**(raw CSV → Room 적재 → §2.2.2 조회 → ③~⑦ → expected 대조, Robolectric) | 03c, 05h | S | kotlin-implementer | `./gradlew :app:testDebugUnitTest --tests "*LedgerChainE2E*"` green |

### 3.7 MT1-06 확정 틱 + 캐치업

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-06a | **확정 틱 시각 결정 메모**(AD-3b 이행, 사전등록 규칙 + 실측) | 00a | S | backtest-analyst → 사용자 승인 | 저널 + 스케줄 정합 논증 |
| MT1-06b | 거래일 그리드(`getBusinessDays` → 캐시 → 관측 폴백) + 휴장일 무틱 | 04c, 06a | S | kotlin-implementer | 휴장일 무틱·폴백 테스트 green |
| MT1-06c | 확정 틱 파이프라인(§2.8 확정 열 전체) | 03c, 03f, 04g, 05f, 05d, 06b | S | kotlin-implementer | Robolectric 정상 시나리오 green |
| MT1-06d | 멱등·이중 실행 방지 | 06c | S | kotlin-implementer | W-S2 green |
| MT1-06e | 캐치업(오름차순 1일 1커밋, 상한 20, 공백 표기) | 06c | S | kotlin-implementer | 캐치업·상한 초과 시나리오 green |
| MT1-06f | `tick_run` 이력 + 누락 노출(K-15) | 06c | S | kotlin-implementer | 실패·부분 결측 기록 테스트 green |
| MT1-06g | **결정론 테스트**(동일 원장 → 라이브 == 캐치업) | 06c, 06e | S | kotlin-implementer | `--tests "*ConfirmTickDeterminism*"` green |
| **MT1-06h** | **부트스트랩 게이트**(웜업 충족 전 확정 틱 미생성, §2.3.2) | 04g, 06c | S | kotlin-implementer | W-W1 + "미충족 상태에서 tick_input 행 0" 단언 green |

### 3.8 MT1-07 프리뷰

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-07a | 프리뷰 파이프라인(evalAt=now, lane=1 쓰기, PREVIEW 배지·as_of) | 04a~e, 05f | S | kotlin-implementer | 국면 불변 단언(TASK ①) green |
| MT1-07b | carry-forward 경로 격리(타입·모듈 + 아키텍처 테스트) | 05c, 06c | S | kotlin-implementer | 확정 경로 호출 불가(TASK ②) green |
| MT1-07c | rawCoverage + 억제(임계는 assets 로드) | 07a, §8-2 | S | kotlin-implementer | 67.7% 산출·억제(TASK ③) + 이월 후에도 억제 유지 회귀 green |
| MT1-07d | D-23 §23.2 수치 예 재현(66.7 vs 45.2) | 05c | S | kotlin-implementer | 재현(TASK ④) green |
| **MT1-07e** | **프리뷰 시계 증인**(W-P1~P3) | 05e, 07a | S | kotlin-implementer | `--tests "*PreviewClock*"` green |

### 3.9 MT1-08 노티 · 기능판 홈

| ID | 내용 | 의존 | 병렬 | 위임 | 완료 기준 |
|---|---|---|---|---|---|
| MT1-08a | 노티 3채널(채널 ID·중요도 확정 + 트리거 3종 + 억제 연동) | 06c, 07c | S | kotlin-implementer | `--tests "*NotificationTrigger*"` green(억제 상태 미발신·전이 없을 때 미발신 포함) |
| MT1-08b | 기능판 홈(§3.9.1 표시 7종 × 상태 7종 매핑) | 06c, 07c | S | ui-craftsman | `--tests "*HomeState*"` green — 상태 7분기 전건 |
| MT1-08c | 수동 E2E 체크리스트 + 스모크 절차서(§11) + **증빙 기계 판정기** `scripts/check_smoke_evidence.py` | 08a, 08b, 08d | S | Advisor(절차서) / python-implementer(판정기) | `docs/runbooks/M1_SMOKE.md`가 §11.2와 1:1 + `uv run pytest -q tests/test_check_smoke_evidence.py` green(픽스처 diag JSON으로 통과·실패 양방향) |
| **MT1-08d** | **진단 내보내기**(§11.3 — GM1 증빙 수집 수단, 키·자격증명 제외) | 03f, 06f | S | kotlin-implementer | `--tests "*DiagnosticsExport*"` green + **비밀 미포함 단언**(03e 화이트리스트 규칙 재사용) |

#### 3.9.1 M1 범위와 M2 경계 (브리프 §5-12의 답)

**경계 원칙**: M1에는 **나중에 바꾸는 비용이 큰 것**과 **사슬 산출물 → 표시의 매핑 정확성**만 넣는다.
M2는 그 표시의 **시각 언어**를 만든다. AAA §2.4(디자인)·§2.5(상용 체감)는 문언 그대로 **M2~ 전용**이며
M1 판정 대상이 아니다 — 단 §2.2(실패 경로 UX)·§2.3(커버리지 ≥70%)은 M1 대상이고, 홈의 **상태 분기 로직**이
그 측정 범위다(그래서 08b는 Robolectric 상태 매핑 테스트를 낸다 — 렌더링 미학이 아니라 매핑이 대상).

**노티 3채널 — M1에서 확정(변경 비용 근거)**

| 채널 ID | 중요도 | 트리거(정본: 사슬 산출물) | M1 | M2 |
|---|---|---|---|---|
| `phase_transition` | HIGH | `phase_commit`의 오늘 국면 ≠ 직전 커밋 국면(fold 산출) | 트리거·채널 확정 | 문구·국면 색·액션 버튼 |
| `preview_alert` | DEFAULT | 프리뷰 결과가 crit 수준 **AND 억제 아님**(rawCoverage ≥ 임계) | 트리거·억제 연동 | 문구·요약 카드 |
| `tick_failure` | LOW | `tick_run.status ≠ success` 또는 캐치업 `gap` 기록 | 트리거·사유 코드 | 문구·복구 안내 동선 |

채널 ID와 초기 중요도를 M1에 두는 이유는 미학이 아니라 플랫폼 제약이다: **채널은 생성 후 앱이 중요도를
올릴 수 없고, 같은 ID로 삭제·재생성해도 사용자의 이전 설정이 복원**된다. 즉 M2에서 고치려면 새 ID로 갈아타야
하고 그 순간 사용자가 M1에서 조정한 설정이 버려진다. **M1에서 확정 → M2는 표현만 바꾼다**가 유일하게 값싼 순서다.
캐치업 노티는 **가장 최근 1건만** 발신한다(과거 N일 알림 폭탄 금지). 리포트·다이제스트 트리거는 M1에 **없다**(D-17 §4, M2).

**기능판 홈 — 표시 7종 × 상태 7종**

표시(전부 사슬 산출물의 읽기 전용 투영): ① 국면 ② composite ③ **rawCoverage** ④ 상위 발화 지표 3
⑤ 마지막 확정 틱 시각(+`is_catchup`) ⑥ `PREVIEW` 배지 + `as_of` ⑦ **웜업·공백 상태**.
상태 열거형(M2 화면이 그대로 소비할 계약): `NORMAL` / `PARTIAL`(부분 결측·스테일 배지) /
`SUPPRESSED`(rawCoverage < 임계 → 흐림 + "국면 판정 불가") / `WARMUP`(백필 진행률) / `GAP`(N거래일 공백) /
`ERROR`(수집·틱 실패) / `EMPTY`(첫 실행). **이 열거형을 M1에서 고정**하는 것이 M2 디자인 작업의 입력이다.

**M1이 하지 않는 것(명시)**: 차트(Vico)·위젯(Glance)·애니메이션·아이콘/스플래시/빈 상태 일러스트·
다크·라이트 토큰 체계·타이포 스케일·TalkBack 라벨 정비·성능 예산 계측(macrobenchmark). 전부 M2.
M1의 홈은 "기능 검증용"(TASK 원문)이며, **디자인 완성도로 M1을 반려하지 않는다**는 것을 GATE_GM1에 명시한다.

### 3.10 의존성 그래프 · 병렬 레인

```
t0 ─┬ MT1-00a/b/c/d/e (실측 5, 완전 병렬)
    ├ MT1-01a ─┬ 01b ─ 01c ─ 01d ─ 01e
    │          ├ 01f
    │          └ 01g ──────────────┐
    ├ MT1-02a(py) ─ 02b ─ 02c      │
    └ MT1-05g(py) ─────────┐       │
                           │       │
  01a ─ MT1-03a ─┬ 03b     │       │
                 ├ 03d     │       │
                 ├ 03f     │       │
                 └ 03c ←── 05a     │
  01b ─ MT1-05a ─┬ 05b ─┬ 05f ─┬ 05h ─┬ 05i
                 ├ 05c  │      │      └ 05k ← 03c
                 ├ 05d  │      └ 05j ← 05g
                 └ 05e ─┘
  03a ─ MT1-04a/b/c/d/e (병렬) ─ 04g ─ 04h ← 05g
  00a ─ MT1-06a ─ 06b ─ 06c ─┬ 06d/06e/06f ─ 06g
                             ├ 06h ← 04g
                             └ MT1-07a ─┬ 07b/07c/07e
                                        └ MT1-08a/08b/08d ─ 08c ─ 실기기 스모크(§11)
  00f ← 01a  (03c 착수 전 완료 권고)
```

**임계 경로 2개**: `00a → 06a → 06b → 06c → 06e → 08c` (실측 지연이 전체를 민다 — t0 최우선 위임) /
`01b → 05a → 05e·05f → 05h → 05k` (GM1 하드 게이트).
**동시 위임 상한 6건** 권고(공유 파일이 `settings.gradle.kts`·버전 카탈로그에 집중).
1차 묶음(t0): 00a·00b·00c·00d·00e·01a·02a·05g(8건 — 실측 5는 네트워크 대기라 파일 충돌 0).

---

## 4. 완료 기준 — 실행 가능한 명령 모음

```bash
# ── 공통 회귀 (Python, 전 phase 유지) ──────────────────────────────
uv run ruff check . && uv run pytest -q
uv run pytest -q backtest/test_golden.py
uv run python backtest/export_parity.py --check
uv run pytest -q tests/test_contracts_snapshot.py tests/test_check_smoke_evidence.py
uv run python scripts/check_smoke_evidence.py docs/gates/evidence/GM1/   # GM1 증빙 기계 판정(§11.3)

# ── Kotlin (기기 불요, CI 기본) ────────────────────────────────────
./gradlew check                                              # ktlint + detekt + JVM 테스트 + 커버리지 임계
./gradlew :engine:test --tests "*Bt05Parity*"                # BT-05 9창 × mobile_daily (L0~L5)
./gradlew :engine:test --tests "*GoldenMobile*"              # golden_mobile.yaml 동결 대조
./gradlew :engine:test --tests "*VisibilityParity*"          # 가시성(단일·결합) + W-P1
./gradlew :engine:test --tests "*Transforms*"                # W-T1~T5
./gradlew :engine:test --tests "*StateMachine*"              # W-S1~S4 + F2/D-26 증인
./gradlew :app:testDebugUnitTest --tests "*LakeSchema*"      # W-L1~L4
./gradlew :app:testDebugUnitTest --tests "*AsOfQuery*"       # W-Q1~Q5, W-W1
./gradlew :app:testDebugUnitTest --tests "*LedgerChainE2E*"  # ①②까지 포함한 e2e 1창
./gradlew :app:testDebugUnitTest --tests "*ConfirmTickDeterminism*"
./gradlew :app:testDebugUnitTest --tests "*PreviewClock*"    # W-P1~P3
./gradlew :app:testDebugUnitTest --tests "*Coverage*"        # 67.7% 억제 + 66.7/45.2 재현
./gradlew :app:testDebugUnitTest --tests "*NotificationTrigger*"   # 노티 3채널 트리거·억제 연동
./gradlew :app:testDebugUnitTest --tests "*HomeState*"       # 홈 상태 7분기 매핑(§3.9.1)
./gradlew :app:testDebugUnitTest --tests "*DiagnosticsExport*"     # 진단 JSON + 비밀 미포함 단언
./gradlew :app:testDebugUnitTest --tests "*SsotHash*"

# ── 계측 (실기기 — GM1 증빙) ───────────────────────────────────────
./gradlew :app:connectedDebugAndroidTest                     # assets 패키징 해시 + Room + WorkManager
```

- PowerShell에서는 `.\gradlew.bat …`. 콘솔 cp949 — **판정은 exit code로** 하고 로그는 파일 리다이렉트 후 UTF-8로 읽는다.
- **모듈 레이아웃 대응**(X-1 병합 시 접두사만 교체):
  `:engine` ↔ B안 `:engine`/`:contracts` ↔ C안 `:core:engine` / `:app` ↔ B안 `:app`/`:lake`/`:collectors` ↔ C안 `:app`.
  본 계획의 사슬 명세는 어느 레이아웃에서도 불변이다.

---

## 5. 실측 선행 과업 — 무엇이 무엇을 블록하는가

| 실측 | 확정 사실 | 블록 | 실패 시 |
|---|---|---|---|
| MT1-00a KRX | 로그인 정책·데이터셋·**확정 시각**·영업일 범위 조회 비용 | 06a → 06b → 06c(임계 경로), 04c, 04g | 로그인 불가 시 KR 4지표 전면 결측 → M1 재설계 상신 |
| MT1-00b 야후 | 엔드포인트·as_of 규약·Stooq 폴백 | 04a, 04h | Stooq 단독 → 심볼·필드 재검증 |
| MT1-00c ECOS | **item_code + lag_days**(K-04, 현재 `VERIFY`) | 04d, **§2.5.1 `L` 표의 ecos 행** | 미확정 시 가중 2.0 상시 결측(coverage 29.0/31.0) |
| MT1-00d CDS | 모바일 접근성 | 04f, `L` 표의 scrape_wgb 행 | (b) 미수집 확정(권고) |
| MT1-00e KIS | 앱키 보유·토큰 | 04e | M2 이연 |
| **MT1-00f SQLite** | **버전·윈도우 함수 가용·쿼리 플랜·응답 시간** | **03c**(조회 계약 구현) | 인덱스 미사용 시 인덱스 재설계, 상관 서브쿼리는 유지 |

> MT1-00a의 확정 시각 프로파일링은 **거래일에만** 가능하다 — 착수일이 금요일 이후면 임계 경로가 최대 3일 밀린다.

---

## 6. 리스크 × K-xx 매핑

| ID | 리스크 | K-xx | 발현 신호 | 완화 |
|---|---|---|---|---|
| R-D1 | ④ 입력을 "최신 1행"으로 구현 → 전 지표 NaN | K-11 | 첫 틱 composite=null이 상시화 | §2.4.1 전치 정리 + W-T1 + MT1-05f 완료 기준 |
| R-D2 | 스테일 행을 ④ **입력에서** 제거 | K-11 | 결측 낀 창에서만 L1 실패 | ⑤는 판정만 — 아키텍처 테스트로 `SeriesWindow`에 stale 필터 호출 0건 단언 |
| R-D3 | 프리뷰 이월값을 raw 시계열에 주입 | K-11 | 프리뷰 transform이 확정 틱과 상이 | §2.6.3(이월은 severity 계층) + 타입 분리 |
| R-D4 | `visible_at` 컬럼 저장 → 그리드 정정 시 드리프트 | K-11 | 과거 틱 재현 불가 | 파생 고정(수렴 사실) + 스키마에 컬럼 부재를 테스트로 단언 |
| R-D5 | 2계열 결합 가시성 누락 → `global_corr_break` 하루 선행 | K-11 | L0 실패(안 짰으면 L2에서 특정 지표만) | `combinedVisibleAt` **단일 구현**(분기 없음) + W-V8 |
| R-D6 | 프로덕션 신규 계열(ECOS·CDS)이 `calendar_kind` 폴백으로 us_market 오분류 | K-04 | 근거 없이 우연히 맞음 → K-04 확정 후 틀림 | §2.5.1 `L` 표 + W-V5 |
| R-D7 | 확정 틱이 프리뷰 장중값을 종가로 채택 | K-11·K-01 | 수집 실패일에만 발현(재현 어려움) | `lane` 필터(§2.1) + W-Q3 |
| R-D8 | 웜업 미충족이 결측으로 흡수 → 저품질 첫 틱 커밋 | K-11 | 설치 직후 coverage 저조가 정상처럼 보임 | §2.3.2 3번째 상태 + MT1-06h 부트스트랩 게이트 + W-W1 |
| R-D9 | minSdk 29 SQLite에서 윈도우 함수 런타임 실패 | — | 구형 기기에서만 크래시(CI 미검출) | 상관 서브쿼리 고정 + MT1-00f 실측 |
| R-D10 | 셀 선택 tie-break 부재 → 비결정적 값 | K-07 | 같은 원장에서 다른 틱 | `ORDER BY lane, revision DESC, id DESC` + W-Q4 |
| R-D11 | ddof·min_periods 불일치로 임계 경계 severity 뒤집힘 | K-07 | L1 통과·L2 실패 | §2.4.2 + 임계 근접 리포트(1e-6 미만 지점 열거) |
| R-D12 | `gated` NaN→0.0 미이식 | K-07 | 웜업 구간 지표 1종만 상이 | W-T4 |
| R-D13 | `usdkrw_intraday_force`의 결측 승급 누락 → coverage·분모 상이 | — | L3 coverage Δ≠0 | W-A3 |
| R-D14 | 백필이 K-03 예산 초과 → 403 | K-03 | 초기 백필 중 빈 응답 급증 | 범위 조회 우선(`getBusinessDays`·OHLCV range) + 1req/s + 재개 가능 설계(04g) |
| R-D15 | 캐치업이 사후 수집분(개정치)을 써서 근사-PIT | K-11 | 라이브와 값 상이(타임라인은 동일 규칙) | `is_catchup` 표기 + 정직성 고지(BACKTEST_PLAN §5 승계) |
| R-D16 | 그리드 공급 실패 시 잘못된 틱 커밋 | K-03·K-14 | 휴장일 틱 발생 | 안전 기본값 = **틱 생성 보류 + 이력 기록**(append-only에서 오커밋은 되돌릴 수 없다) |
| R-D17 | KRX 자격증명·API 키가 CSV·로그·백업으로 유출 | K-17 | 내보내기 파일에 키 | 백업 제외 규칙 파싱 테스트(03e) + CSV 화이트리스트 |
| R-D18 | assets 구버전 패키징으로 사슬이 다른 레지스트리로 계산 | K-16 | 계측 해시만 실패 | 01c 배선 + 01d/01e 2층 검증 |

---

## 7. 미해결 결정 목록 (Advisor·사용자 상신 — 권고 포함)

| ID | 결정 | 선택지 | **권고** | 필요 시점 |
|---|---|---|---|---|
| **D-D1** | 웜업 조회 창(`warmup_padding_days`)의 값·SSOT 위치 | (a) `indicators.yaml` `engine.warmup_padding_days: 550` 신설 (b) 그리드에서 `requiredRows`만큼 역산 (c) 앱 상수 | **(a)** — `windows.yaml padding_days: 550`이 같은 요구의 기존 정답이고, `stale_profiles`와 동종의 "엔진 공통 규칙"이다. (b)는 다계열 정렬(ffill·union)에서 여유가 0이 되는 경계가 남고, (c)는 CLAUDE.md §1 위반 | MT1-03c 착수 전 |
| **D-D2** | **프리뷰 가시성 시계 + 나이 산식**(M-39) | (a) §2.5.4 일반화(일 단위 가시 + **실경과** 나이, 0 클램프) (b) 확정 틱과 완전 동일(시각 비교) (c) C안 `observed_at` 분기 (d) 일 단위 가시 + **24h 양자화** 나이(C안 라운드 4) | **(a)** — 확정 틱과 비트 동일함이 증명되고(W-P1) 죽은 계열 영원-fresh가 구조적으로 불가능하다. (b)는 프리뷰가 하루 뒤처져 D-17의 목적을 잃는다. **(c)는 라운드 3 중대 결함 판정으로 배제**. (d)와는 **가시 판정이 동일하고 억제 시점만 최대 24h 갈린다** — 정본이 실경과 뺄셈이고 SSOT 창 값(30h/48h/96h)의 의미가 보존된다는 점에서 (a) 권고(대비표·근거 4·C안 논거 기록: **§2.5.5**). **BT-05는 이 결정을 판정하지 못한다**(확정 틱에서 두 안이 일치) — 설계 논증으로만 결정된다 | MT1-07a 착수 전 |
| **D-D3** | **프리뷰 lane 분리** | (a) 확정 경로가 `lane=1` 행을 **완전 배제** (b) 확정 우선·없으면 프리뷰 + 경고 | **(a)** — (b)는 장중 부분봉이 종가로 굳는 경로를 남긴다. (a)에서는 as_of=D−1 실관측 종가로 폴백되고 스테일 정책이 그 값을 관리한다(30h 창 안) | MT1-03a 착수 전 |
| **D-D4** | **부트스트랩 게이트** | (a) 웜업 충족 전 확정 틱 미생성 (b) 첫날부터 커밋(저커버리지 감수) | **(a)** — (b)는 fold 시퀀스의 1번째 원소가 저품질 틱이 되고, "설치 직후 전 지표 결측"이 D-25 §3 동결로 흡수돼 관측되지 않는다. (a)의 대가는 초기 대기(백필 완료까지)뿐이며 온보딩에 진행률로 노출한다 | MT1-06c 착수 전 |
| **D-D5** | 프로덕션 `L`(publication lag) 매핑의 위치 | (a) 엔진 코드 표 + `lag_days`는 config에서(§2.5.1) + 픽스처 합치 테스트 (b) `indicators.yaml source.publication_lag_days` 신설 | **(a)** — `calendar_kind`가 이미 코드(정본 `fixture_schema.py`)에 있고 이는 임계·가중이 아니라 **달력의 물리적 사실**이다. (b)는 SSOT 표면을 넓히나 하니스와 이중 진실이 된다. 단 W-V5로 두 표의 일치를 상시 강제 | MT1-05e 착수 전 |
| **D-D6** | minSdk 29 SQLite 제약 | (a) 상관 서브쿼리 고정(윈도우 함수 미사용) (b) 실측 후 윈도우 함수 사용 | **(a)** — 실측 결과와 무관하게 하위 호환. MT1-00f는 **인덱스 사용·성능 확인용**이지 (b)로 가는 문이 아니다 | MT1-03c 착수 전 |
| **D-D7** | 확정 틱 시각(AD-3b) | (a) 17:00 (b) 16:20 (c) 실측이 지시하는 더 늦은 시각 | **(a)** — 3안 수렴. `confirmTime`은 §2.5의 가시성 함수 인자이므로 사슬 전체가 이 값에 의존한다. 실측이 (c)를 지시하면 (c) | MT1-06 착수 전 |
| **D-D8** | 억제 임계 0.80 SSOT 위치 | (a) `indicators.yaml engine.preview_coverage_min` (b) `statemachine.yaml` (c) 앱 상수 | **(a)** — A·B와 동일. 본 계획은 §8 제안 2로 지지 | **MT1-07 착수 전(필수)** |
| **D-D9** | 캐치업 절단 표현 | (a) 틱 부재(B) (b) `composite=NULL` 동결(A) | **둘 중 하나로 통일**(관측 가능 귀결 동치 — 라운드 3 판정). D는 **엔진 무변경**만 요구하며 병합 시 (b)가 `tick_input` 스키마와 더 잘 맞는다는 점만 부기 | MT1-06e 착수 전 |
| **D-D10** | VKOSPI 실수집 여부 | (a) 폴백 통일 (b) 실 VKOSPI 사용 (c) 수집·저장하되 v1 판정은 폴백 | **(c)** — B D-B7과 동일. 사슬 관점 부기: (b)는 §2.3.1 매핑표의 계열이 바뀌어 BT-05 픽스처(폴백 기반)와 프로덕션이 **다른 지표 정의**를 갖게 된다 | MT1-04c 착수 전 |
| **D-D11** | `krx_credit_spread_delta`의 계열 표현 | (a) ECOS item 2계열(`ECOS:corp_aa3y`·`ECOS:ktb_3y`) + 결합 가시성 (b) 앱에서 스프레드를 미리 계산해 1계열로 저장 | **(a)** — (b)는 원장에 파생값을 넣어 append-only 원장의 "관측만 기록" 규율을 깬다. K-04 실측(MT1-00c) 선행 | MT1-04d 착수 전 |
| **D-D12** | `tick_input`에 `warmup_status_json` 추가 | (a) 추가 (b) `tick_run`에만 기록 | **(a)** — 동결본만으로 그 틱의 결측 사유를 재구성할 수 있어야 한다(사후 감사). 컬럼 1개, fold 무영향 | MT1-03f 착수 전 |
| **D-D13** | **캐치업의 실기기 검증을 GM1 스모크 필수로 둘 것인가**(§11.2 S-5) | (a) 권고 항목(비필수) — Robolectric MT1-06e·06g가 이미 커버 (b) 게이트 필수(2 거래일 소요) | **(a)** — 브리프의 게이트 문언은 "확정 틱 1회 + 프리뷰 3회"이고, 캐치업은 기기 특성이 아니라 **사슬 로직**이라 JVM/Robolectric에서 더 강하게 증명된다. 실기기 고유 위험(K-14 WorkManager 비정시·K-15 OEM 킬)은 M3 7일 소크의 대상이다. (b)를 택하면 사용자 수행 시간이 2배가 되고 게이트가 거래일 달력에 묶인다 | GM1 리포트 작성 전 |

---

## 8. SSOT·문서 변경 제안 (직접 수정 금지 — 제안만)

| # | 대상 | 제안 | 근거·영향 |
|---|---|---|---|
| **1** ★ | `configs/indicators.yaml` `engine:` | `warmup_padding_days: 550` **신설** | §2.2.1·D-D1. `engine_ref`는 이 키를 읽지 않는다 → Python 거동 변화 0, 골든 무회귀 0. `tests/test_configs_schema.py`에 존재·범위 가드 1건 추가 제안 동반 |
| **2** ★ | `configs/indicators.yaml` `engine:` | `preview_coverage_min: 0.80` **신설**(D-23 §23.3-3) | A·B와 동일 제안. MT1-07 착수의 필수 선행. 서버에는 프리뷰가 없어 소비자 0 |
| **3** | `configs/statemachine.yaml` `profiles.mobile_daily` | `catchup_max_ticks: 20` **신설** | 수렴값. `load_statemachine`은 명시 키만 읽으므로 추가 키 무시 → Python 거동 변화 0 |
| **4** | `configs/indicators.yaml` `krx_credit_spread_delta.source.item_codes` | `VERIFY` → 실측 코드값 | K-04. MT1-00c 완료 후 Advisor 허가 범위에서만. **본 계획은 수정하지 않는다** |
| **5** | `configs/sources.yaml` | MT1-00a/b/d/f 실측 결과 부기(KRX 로그인 사실·Stooq 매핑·CDS 판정·SQLite 버전) | K-02·K-04와 동일한 "실측을 sources.yaml에 기록" 규율의 연장 |
| **6** | `TASK_mobile_m1.md` MT1-06 / `docs/ARCHITECTURE_SPLIT.md` §1·D-15 | "16:20 KST" → 확정값 + AD-3b 이행 부기 | 문서-문서 불일치 해소. SSOT(configs) 무변경 |
| **7** | `backtest/golden_mobile.yaml` 머리 주석 | `registry_version: 0.1.0` 스탬프 출처 정정 부기 | 패리티 리포트 독자 오도 방지(값 변경 아님) |

> ★ 2건(1·2)은 해당 서브태스크 착수의 **선행 조건**이다. 편집은 Advisor가 TASK 허가 범위를 브리프에
> 명시한 뒤 별도 서브태스크로 수행하고, 편집 후 `uv run pytest -q` + `backtest/test_golden.py` 재실행이 의무다.

---

## 9. 커밋 단위 · 위임 규약

### 9.1 커밋 (`m1-xx:` 프리픽스, 영어)

```
m1-00: measurement journals (krx / yahoo / ecos / cds / kis / sqlite)
m1-01: android scaffold, syncConfigs, ssot hash (jvm + instrumented), coverage gates, krx vendoring
m1-02: contract snapshots (python generator + kotlin mirror + round-trip)
m1-03a: room observation ledger (series_id, field, as_of, lane, revision)
m1-03b: physical append-only enforcement (update/delete triggers)
m1-03c: as-of query contract (per-kind cutoff, lane filter, warm-up sufficiency)
m1-03d: revision/lane dedup on append
m1-03e: csv export + backup exclusion rules
m1-03f: tick_input / phase_commit / tick_run tables
m1-04a..f: collectors (yahoo+stooq, fred, krx, ecos, kis, kr_cds decision)
m1-04g: initial backfill with warm-up sufficiency report
m1-04h: collector-fixture cross-check
m1-05a: config loader + transform parser + requiredRows derivation
m1-05b: transforms (ddof=1, min_periods, gated NaN semantics)
m1-05c: scoring + modifiers
m1-05d: statemachine (D-25 s1-4, D-26 pairing)
m1-05e: visibility + stale policy (single rule, publication lag table)
m1-05f: series window -> transform -> known-series indexer
m1-05g: python parity export
m1-05h: BT-05 parity runner (9 windows x mobile_daily)
m1-05i: golden_mobile frozen timeline check
m1-05j: visibility parity + preview-clock equivalence witness
m1-05k: ledger-to-chain end-to-end (1 window, robolectric)
m1-06a: confirm-time decision memo (AD-3b closure)
m1-06b: trading-day grid + daily worker
m1-06c: confirm tick pipeline (freeze inputs, full fold commit)
m1-06d: idempotency + duplicate-run guard
m1-06e: catch-up (ordered per-trading-day commits, cap 20)
m1-06f: tick_run history + missed-tick surfacing
m1-06g: confirm tick determinism (live == catch-up)
m1-06h: bootstrap gate (no ticks before warm-up)
m1-07a..e: preview pipeline, carry-forward isolation, raw coverage suppression,
           D-23 numeric reproduction, preview-clock witnesses
m1-08a..d: notification channels (ids/importance fixed), functional home state mapping,
           smoke runbook, diagnostics export (secrets excluded)
```

### 9.2 Worker 브리프 공통 규약 (REVIEW_M0 신설 규율 편입)

모든 M1 브리프에 다음을 복사한다.
1. 완료 보고에 **`git status --porcelain` 원문**과 게이트 명령 출력 마지막 줄을 포함할 것.
2. 파생 수치를 보고하면 **퇴화 입력 증인 테스트**를 함께 낼 것(§2.9에서 담당 단계의 W-xx 지정).
3. 결측 원인 귀속을 서술하면 **같은 kind 형제 계열의 당일 관측 증거**를 첨부할 것.
4. qa-verifier는 판정 전에 **보고-저장소 일치**를 먼저 확인할 것.
5. `git diff --stat configs/ contracts/ prompts/`가 **비어 있음**을 보고에 포함할 것(허가 서브태스크 제외).
6. (D 고유) 사슬 단계를 건드리는 서브태스크는 **정본 file:line을 브리프에 인용**하고, 구현 후
   그 인용이 여전히 유효한지(줄 이동 여부) 확인해 보고할 것.

---

## 10. 타 관점 최소 요구 (비워두지 않기)

**A — 아키텍처**: `:engine`은 Android 비의존(BT-05가 JVM에서 돌아야 한다 — D의 하드 요구).
kotlin_krx는 **벤더링**(수렴). 설정 로딩은 `ConfigSource` 추상화 1개. carry-forward는 타입 분리
(`ConfirmInputs`/`PreviewInputs`)로 컴파일 차단 + 아키텍처 테스트 이중화. `:engine`에 `SeriesWindow`·
`Visibility`·`StalePolicy`가 들어가야 사슬 ③~⑦이 디바이스 없이 검증된다.

**B — 데이터 정합성**: 본 계획의 §2가 B §5와 같은 결론에 도달한다(가시 시각 함수 1 + as_of 컷오프 1 +
전량 fold 1). D가 추가하는 것은 **그 셋을 SQL·자료구조 수준으로 내린 계약**(§2.1·2.2)과 **전치 정리**(§2.4.1),
**프리뷰 시계 일반화**(§2.5.4), **웜업 3번째 상태**(§2.3.2)다. BT-05 범위는 B의 9창 + 합성 증인을 지지한다.

**C — UX·운영**: 사슬이 UI에 요구하는 상시 표기 5종 —
`as_of` / rawCoverage / 스테일 배지 / `PREVIEW` 배지 / **웜업·공백 상태**(백필 진행률, "N거래일 공백").
실패 경로: 웜업 미충족(진행률·예상 완료), 부분 결측(지표별 배지), 프리뷰 억제(흐림 + "국면 판정 불가"),
캐치업 진행, 그리드 조회 실패(틱 보류 고지). "빈 화면·무한 스피너·조용한 실패" 금지.
실기기 스모크는 **백필 완료 확인**을 1번 항목으로 둔다(그 전 스모크는 사슬을 검증하지 못한다).

---

## 11. 실기기 1일 스모크와 GM1 증빙 (브리프 §5-13의 답)

### 11.1 무엇을 증명하는 절차인가

JVM·Robolectric이 증명할 수 없는 것만 실기기로 옮긴다: **실제 네트워크로 수집한 값이 사슬을 통과해
원장에 남고, WorkManager가 실제로 깨어나 노티를 띄우고, assets가 APK에 제대로 실린다.**
사슬의 수치 정합(③~⑦)은 이미 BT-05가, ①②는 MT1-05k가 덮으므로 스모크는 **재검증하지 않는다**.
게이트 문언(확정 틱 1회 + 프리뷰 3회)을 넘는 항목은 전부 권고로 표기한다(사용자 부담 최소화, C-7 교훈).

### 11.2 절차 (`docs/runbooks/M1_SMOKE.md` — MT1-08c 산출)

| # | 단계 | 수행 | 소요 | 통과 판정 | 증빙 |
|---|---|---|---|---|---|
| S-0 | 설치·온보딩: 사이드로드, 키 4종 입력(FRED·ECOS·KRX ID/PW·(KIS)), **OEM 절전 예외 등록**(K-15), 기기 시간대 KST 확인 | **사용자** | 5분 | 온보딩 완료·키 저장 확인(값 미표시) | 스크린샷 1 |
| S-1 | 초기 백필 → **웜업 충족 리포트** | 앱(대기) | 자동 | 활성 15지표 전건 `requiredRows` 충족, `WARMUP_INSUFFICIENT` 0건. **충족 전에는 `tick_input` 행 0**(부트스트랩 게이트) | diag JSON ①, 스크린샷 2(진행률) |
| S-2 | **확정 틱 1회**(확정 시각 이후 자동 실행) | 앱 / 사용자는 노티 수신만 확인 | 5분 | `tick_input` +1행 · `phase_commit` +1행 · `tick_run.status = success` · 노티 1건(전이 있을 때만 — 전이 없으면 미발신이 정상) | diag JSON ②, 노티 스크린샷 |
| S-3 | **프리뷰 3회 — 서로 다른 시계 조건에 배치**: (a) 장중 ≈13:00 (b) 확정 틱 직후 ≈+10분 (c) 저녁 ≈21:00 | **사용자**(각 1탭) | 각 2분 | 3회 모두 **국면 불변**(`phase_commit` 행수 불변) · `PREVIEW` 배지 + `as_of` 표시 · rawCoverage 표시 · (a)에서 KR 스테일이면 억제 표시 | diag JSON ③④⑤, 스크린샷 3장 |
| S-4 | 같은 날짜 확정 틱 수동 재실행(설정 화면) | 사용자 1탭 | 1분 | 상태·행수·노티 **전부 불변**(멱등) | diag JSON ⑥ |
| S-5 | *(권고, 게이트 조건 밖 — §7 D-D13)* 앱 강제 종료 후 다음 거래일 캐치업 | 사용자 | 다음날 5분 | 누락 거래일 1건이 오름차순 커밋, `is_catchup = true` | diag JSON ⑦ |
| S-6 | 계측 테스트(USB 연결) | 사용자(연결) + 에이전트(실행) | 5분 | `./gradlew :app:connectedDebugAndroidTest` green — assets 패키징 SHA-256 포함 | 실행 로그 |

**사용자 실작업 합계 ≈ 25분**(당일 분산: 아침 5 + 13:00 2 + 확정 후 7 + 21:00 2 + 연결 5).
S-3의 **시계 조건 분산이 D 관점의 하드 요구**다 — 같은 조건 3회는 §2.5.4의 프리뷰 시계를 검증하지 못한다.

### 11.3 증빙 수집 — 진단 내보내기 1개로 통일 (MT1-08d)

스크린샷·구두 보고 대신 **기계 판독 가능한 단일 산출물**을 만든다. 설정 화면의 "진단 내보내기" →
`branchconsole-diag-<yyyyMMddHHmm>.json`(SAF 공유):

| 블록 | 내용 | 어느 판정에 쓰이나 |
|---|---|---|
| `app` | 버전, `registry_version`, **assets 매니페스트 SHA-256** | K-16 드리프트 없음(계측 테스트와 교차) |
| `tick_input[]` | 전 행 — `Tick` 4필드 + coverage + `warmup_status` + `is_catchup` | S-1·S-2·S-4·S-5, fold 재현 가능성 |
| `phase_commit[]` | 전 행 | S-2·S-3(국면 불변) |
| `tick_run[]` | 최근 50행 — status·사유 코드·소요·재시도·`gap` | S-2·S-4, K-14·K-15 발현 여부 |
| `indicators[]` | 지표별 `value, as_of, visible_at, stale, severity, status ∈ {OK, MISSING, STALE, WARMUP_INSUFFICIENT}` | S-1·S-3(§2.3.2의 3번째 상태가 실기기에서 관측되는지) |
| `preview[]` | 프리뷰 3회 각각의 `evaluated_at, rawCoverage, suppressed, carried[]` | S-3, §2.5.4 시계 검증 |

- **키·자격증명은 화이트리스트 방식으로 제외**한다(K-17). MT1-03e의 백업 제외 규칙과 **같은 화이트리스트를
  재사용**하고, "diag JSON에 비밀이 없음"을 단언하는 테스트를 08d 완료 기준에 둔다(새 규칙을 만들지 않는다).
- 수집 위치: `docs/gates/evidence/GM1/` — `diag-*.json`, `shot-*.png`, `connected-test.log`.
  GATE_GM1.md는 각 파일을 **파일명 + SHA-256**으로 인용한다(사후 대체 방지).
- 체크리스트(`M1_SMOKE.md`)에는 단계별 **완료 시각 기입란**을 둔다. 사용자 수행 항목(S-0, S-2 확인,
  S-3 트리거, S-4, S-6 연결)은 표에 굵게 표시하고, 게이트 리포트에 그대로 전재한다.
- **판정 자동화**: `scripts/check_smoke_evidence.py`(신설, Python) 1개가 diag JSON을 읽어 S-1~S-4의
  통과 조건을 기계 판정한다 — 사람이 JSON을 눈으로 읽고 "정상"이라고 쓰는 경로를 없앤다(MT0-06 절차 사건의 교훈).
  완료: `uv run python scripts/check_smoke_evidence.py docs/gates/evidence/GM1/` exit 0
  (판정기 자체는 `tests/test_check_smoke_evidence.py`가 **통과·실패 양방향 픽스처**로 고정한다 — 항상 0을 내는
  판정기는 판정기가 아니다).

---

## 12. 브리프 §5 13문 답변 색인

| # | 질문 | 답변 위치 | 한 줄 요지 |
|---|---|---|---|
| 1 | mobile/ Gradle 구성·`check` 포함 범위 | §3.2 MT1-01a/f, §4, §10-A | A안 3모듈(`:engine`/`:krx`/`:app`) 가정, 카탈로그 단일 선언, minSdk 29, JVM/계측 소스셋 분리. `check` = ktlint + detekt + JVM 테스트 + 커버리지 임계(계측 미포함) |
| 2 | kotlin_krx 통합 방식 + 로그인 정책 대응 | §3.2 MT1-01g, §5 MT1-00a, §10-A | **벤더링**(수렴 사실) + 출처 매니페스트. 로그인 정책은 MT1-00a 실측이 04c·06a를 블록 |
| 3 | syncConfigs 대상·해시 소스셋·드리프트 차단 | §3.2 MT1-01b~e, §8 | configs 5 + prompts 2. **JVM(파일↔파일) + 계측(APK 자산) 2층**(TASK 명시 요구 — 축소 없음), 빌드 배선으로 수동 복사 불가 |
| 4 | contracts 미러·스냅샷·왕복 | §3.3 MT1-02a~d | Python 생성기 신설이 선행. Kotlin 왕복 + 형상 다이제스트 교차. **⑥ 산출 `TriggerBlock` 1건을 실제로 투입**(02d)해 미러가 M1 안에서 한 번은 실행되게 |
| 5 | Room append-only 스키마·물리 강제·as-of 쿼리·CSV/Drive | **§2.1·§2.2**, §3.4 | granularity `(series_id, field, as_of)` + `lane` + `revision`. 트리거 + DAO 미구현 2중 강제. as-of 쿼리는 §2.2.2 SQL 1개(윈도우 함수 미사용). CSV·Drive 훅은 03e(키 제외 규칙 포함) |
| 6 | collectors 6건 실측·폴백·결측·픽스처·병렬 | §3.5, §5, §6 R-D14 | a~f 병렬 + **04g 백필(웜업 충족 리포트)**·04h 픽스처 대조 신설. 실패는 지표별 결측, 테스트는 픽스처 전용 |
| 7 | Kotlin 엔진·상태기계 대응 범위 | **§2.4.2·§2.5·§2.6·§2.7**, §3.6 | 단계별 정본 file:line 1:1. D-26·`or_any_extreme` 포함(프로덕션 경로), Double·UTC aware 고정 |
| 8 | BT-05 실행 형태·주입·판정 위치 | **§2.10**, §3.6 MT1-05g~k | JVM(`:engine:test`), Python이 raw·grid·visibility·transforms·expected 내보내기. **덮는 범위는 ③~⑦**이고 ①②는 MT1-05k가 보완 |
| 9 | 확정 틱 시각 결정·스케줄 정합 | §7 D-D7, §3.7 MT1-06a, §1 | 17:00 권고(3안 수렴). `confirmTime`은 §2.5 가시성 함수의 **인자**라 사슬 전체가 이 값에 의존 |
| 10 | 캐치업 멱등·이중 실행·이력 | **§2.8**, §3.7 MT1-06d/e/g | `tick_date` UNIQUE 2개 + 고유 작업. 캐치업은 `evalAt = D 확정 시각`으로 라이브와 **동일 산출**(06g가 증명), `tick_run`이 이력 |
| 11 | 프리뷰 carry-forward 분리·coverage 위치·억제 UX | **§2.6.3·§2.5.4**, §3.8 | 이월은 **severity 계층**(원천 = `tick_input` 동결본)이라 원장 미오염 + transform 등식 보존. 억제는 **rawCoverage**로 키잉 |
| 12 | 노티 3채널·홈의 M1/M2 경계 | **§3.9.1** | M1 = 채널 ID·중요도(설치 후 변경 불가) + 트리거 + **상태 열거형 7종**. M2 = 시각 언어. AAA §2.4·§2.5는 M1 판정 대상 아님 |
| 13 | 실기기 스모크 절차·사용자 항목·GM1 증빙 | **§11** | S-0~S-6(사용자 실작업 ≈25분), 프리뷰 3회는 **서로 다른 시계 조건**. 증빙은 **진단 JSON 1종**으로 통일 + `check_smoke_evidence.py`가 기계 판정 |

---

## 13. 이 계획의 자기 한계 (정직성 조항)

1. **§2.5.4(프리뷰 일반화)는 설계 제안이지 실측 결과가 아니다.** 확정 틱과의 동치는 증명했고 W-P1이
   실행 가능한 증인이지만, "화요일 13:00에 월요일 미국 종가가 실제로 앱에 들어와 있는가"는
   MT1-00b 실측(야후 응답의 as_of 규약)에 의존한다.
2. **`requiredRows` 도출식은 보수적 상한**이다. 정확한 값은 표현식 트리를 파싱해야 나오지만, 과대평가는
   충족 기준만 높일 뿐 계산값에 영향이 없으므로 M1에서 트리 파서를 만들지 않는다(YAGNI).
   550일 창이 상한 318을 20% 여유로 덮는다는 계산은 §2.3.2에 남겼다 — 여유가 사라지면 그때 정밀화한다.
3. **minSdk 29의 SQLite 버전은 문서 기억이지 실측이 아니다.** 그래서 MT1-00f를 신설했고, 실측 결과와
   무관하게 안전한 형태(상관 서브쿼리)를 채택했다.
4. **캐치업의 값 자체는 근사-PIT다.** 사슬은 라이브와 동일한 규칙으로 돌지만 원장에 담긴 값이
   사후 수집분(개정치 포함 가능)이다 — `is_catchup` 표기로 정직성을 확보할 뿐 해소하지 못한다(C1 소관).
5. **BT-05는 ①②를 덮지 못한다**(§2.10). MT1-05k 1창 e2e가 그 공백을 메우지만, 9창 전체를 Room 경유로
   돌리지는 않는다 — 비용 대비 증명력이 낮다고 판단했고, 그 경계를 GATE_GM1에 기록해야 한다.
6. **모듈 레이아웃(X-1)·assets 물리 위치(X-2)는 본 계획이 결정하지 않는다.** A안 레이아웃을 가정해
   명령을 적었을 뿐이며, 병합 시 접두사 교체로 흡수된다.
7. **§11 스모크의 소요 시간(≈25분)과 S-3의 시계 조건 배치는 설계 추정이다.** 실제 사용자 부담은 1회
   수행 후에야 확정되며, 특히 S-3(a) 장중 프리뷰가 "KR 스테일 → 억제"를 보이는지는 그날이 월요일인지에
   따라 갈린다(§2.5.4 표). 절차서에는 **어느 요일에 수행했는지 기록란**을 두어 관측을 해석 가능하게 한다.
8. **§2.5.5의 결정(D-D2 (a) vs (d))은 패리티로 판정 불가**하다 — 확정 틱에서 두 안이 일치하므로
   BT-05가 어느 쪽도 반증하지 못한다. 본 계획은 근거 4개를 제시했을 뿐이고, 최종 채택은 병합 결정이다.
