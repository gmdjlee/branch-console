# MT1-00f — SQLite 쿼리 플랜·인덱스 실측 (M1 W0 잔여, 성능 확인용)

- 작성: 2026-08-07, data-verifier Worker
- 대상: `docs/plans/M1_PLAN_D.md` §2.1·§2.2.2(원장 스키마·SQL 리터럴) + `M1_PLAN_FINAL.md` M-43(lane
  판별자 채택)·M-44(윈도 함수 미사용 확정, MT1-00f는 **인덱스 플랜·성능 확인용으로만** 유지)를 근거로,
  채택된 쿼리 형태가 제안 인덱스에서 풀스캔 없이 도는지 `EXPLAIN QUERY PLAN`으로 실측했다.
- **판정(정합성) 범위 밖**: M-44가 이미 "상관 서브쿼리 확정, 윈도 함수 미사용"을 결정했다 — 이 과업은
  그 결정을 재론하지 않고, 결정된 SQL이 인덱스를 실제로 타는지만 확인한다.

## 0. 결론 (요지)

1. **풀스캔 없음.** 확정·프리뷰 경로 모두 `SEARCH ... USING INDEX ux_obs_cell_rev`로 잡힌다.
   `tick_input` 최근 커밋 조회도 PK 인덱스로 잡힌다. 3건 전부 인덱스 사용 확인 — **보정 불필요**.
2. **부수 발견(권고, 강제 아님)**: 채택된 SQL은 `lane` 필터를 `(:includePreviewLane = 1 OR o.lane = 0)`
   파라미터 형태로 걸기 때문에, 제안된 2개 인덱스 중 `ix_obs_scan(series_id, field, lane, as_of)`은
   **이 쿼리 형태에서 한 번도 선택되지 않는다** — `ix_obs_scan`을 완전히 드롭해도 플랜이 바이트 단위로
   동일하다(§2 실측). `lane`을 파라미터가 아니라 리터럴로 하드코딩한 별도 쿼리(확정/프리뷰 2개로 분리)로
   바꾸면 `ix_obs_scan`이 선택되고 호출당 평균 시간이 ≈33% 줄어들지만(1.30ms → 0.87ms, §4), 틱당
   ≈25쌍 기준 절대 차이는 ≈11ms — 일 1회+수시 프리뷰 워크로드에서 무의미한 수준이라 **분리를
   권고하지 않는다**. `ix_obs_scan`을 유지할지 드롭할지는 MT1-03 구현자 재량(둘 다 근거 있음, §5).
3. **로컬 sqlite3 버전은 3.50.4** — Android 10(minSdk 29) 번들 SQLite(~3.22 계열로 알려짐)와
   **다르다**. 이 환경에서 실기기 버전은 실측 불가(§6) — 이는 이 과업의 구조적 한계이며, M-44가
   이미 "윈도 함수는 실측 결과와 무관하게 미사용"을 확정해 뒀으므로 **정합성 결론에는 영향 없음**.
   MT1-03 완료 기준에 `sqlite_version()` 1회 로깅을 추가하자는 제안만 남긴다(§6, 제안일 뿐 미적용).
4. SSOT(`configs/*.yaml`) **무변경** — `configs/indicators.yaml`의 `warmup_padding_days`는 읽기만 했다.

## 1. 검증 대상 SQL·스키마 확정 (문서 상충 해소)

브리프가 지정한 두 절은 서로 다른 판별자를 쓴다: `M1_PLAN_D.md §2.2.2`는 `lane INTEGER`
(0=확정/1=프리뷰), `M1_PLAN_B.md §5.4.1`은 `origin TEXT`(`'confirm'|'preview'|'backfill'`)로 같은
"as_of BETWEEN + 셀당 최신 revision 상관 서브쿼리" **형태**를 표현한다. `M1_PLAN_FINAL.md` M-43이
`lane`을 채택했고, FINAL §2 매핑표가 "조회 계약 → `:app lake/AsOfQuery.kt` (SQL §2.2.2)"로 D의 SQL을
실제 구현 대상으로 지목한다. 따라서 이번 실측은 **D §2.2.2의 SQL 리터럴 그대로**(lane 판별자)를
대상으로 했다 — B §5.4.1은 "as_of BETWEEN + 상관 서브쿼리로 최신 revision 선택"이라는 **동형의
쿼리 shape**를 원장 판별자만 다르게(origin) 표현한 것이므로, 형태 검증 결론은 그대로 두 계획
모두에 적용된다.

**스키마** (D §2.1, 브리프 지정과 동일):
```sql
CREATE TABLE observation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  series_id TEXT NOT NULL, field TEXT NOT NULL, as_of INTEGER NOT NULL,
  value REAL NOT NULL, observed_at INTEGER NOT NULL,
  revision INTEGER NOT NULL, lane INTEGER NOT NULL, source TEXT NOT NULL,
  UNIQUE(series_id, field, as_of, lane, revision)
);
CREATE UNIQUE INDEX ux_obs_cell_rev ON observation(series_id, field, as_of, lane, revision);
CREATE INDEX        ix_obs_scan     ON observation(series_id, field, lane, as_of);
```

**쿼리** (D §2.2.2 리터럴, `:includePreviewLane`을 0/1로 바인딩해 확정·프리뷰 양쪽을 검증):
```sql
SELECT o.as_of, o.value
  FROM observation o
 WHERE o.series_id = :sid AND o.field = :field
   AND o.as_of <= :cutoff AND o.as_of >= :window_start
   AND (:includePreviewLane = 1 OR o.lane = 0)
   AND o.id = (SELECT o2.id FROM observation o2
                WHERE o2.series_id = o.series_id AND o2.field = o.field
                  AND o2.as_of = o.as_of AND (:includePreviewLane = 1 OR o2.lane = 0)
                ORDER BY o2.lane ASC, o2.revision DESC, o2.id DESC LIMIT 1)
 ORDER BY o.as_of ASC;
```

`tick_input` 최근 커밋 조회는 M1_PLAN_C.md 1001-1013행 스키마를 간이화(`target_date TEXT PRIMARY KEY`)해
`SELECT ... FROM tick_input WHERE composite IS NOT NULL ORDER BY target_date DESC LIMIT 1`로 구성했다
(M-43b-i "이월 원천 = composite IS NOT NULL" 필터 반영).

## 2. 합성 데이터·실측 스크립트

`scripts/verify_sqlite_query_plans.py` (신규, 자가검증 포함). 계열 20 × 필드 2 × 2500일 =
**기본 100,000행** + 셀의 5%에 당일 revision 보정 행(≈5,000) + 최근 30일 구간에 lane=1 프리뷰
동시 존재 행(1,200) + "오늘"(lane=1만 존재, 확정 미도착) 행(40) = **총 106,273행**, `tick_input`
2,500행(97일마다 1행은 `composite IS NULL`로 M-34 동결 행 흉내).

```
$ uv run python scripts/verify_sqlite_query_plans.py
sqlite3.sqlite_version = 3.50.4
observation rows = 106,273  tick_input rows = 2,500
warmup_padding_days (SSOT configs/indicators.yaml) = 550
```

## 3. EXPLAIN QUERY PLAN 실측 (원문)

**① 확정 경로** (`:includePreviewLane = 0`):
```
SEARCH o USING INDEX ux_obs_cell_rev (series_id=? AND field=? AND as_of>? AND as_of<?)
CORRELATED SCALAR SUBQUERY 1
SEARCH o2 USING COVERING INDEX ux_obs_cell_rev (series_id=? AND field=? AND as_of=?)
USE TEMP B-TREE FOR LAST 2 TERMS OF ORDER BY
```
풀스캔 아님. "TEMP B-TREE FOR LAST 2 TERMS"는 서브쿼리의 `revision DESC, id DESC` 정렬용인데,
`id`가 인덱스에 없어 정렬이 필요하지만 정렬 대상 집합이 **같은 (series_id,field,as_of) 셀 안의
행뿐**(레인 0/1 × revision 소수 개 — 이 데이터에서 최대 3행)이라 비용은 무시 가능.

**② 프리뷰 경로** (`:includePreviewLane = 1`): **플랜이 ①과 바이트 단위로 동일.** 이유는 §5.

**③ `tick_input` 최근 커밋**:
```
SCAN tick_input USING INDEX sqlite_autoindex_tick_input_1
```
`target_date TEXT PRIMARY KEY`가 만든 암묵 인덱스로 `ORDER BY target_date DESC LIMIT 1`이
그대로 인덱스 역순 스캔 1건으로 끝난다. 풀스캔 아님.

**ANALYZE 전/후 비교**: `ANALYZE` 실행 후 ①을 재실행해도 플랜 불변 — 인덱스가 2개뿐이고 열
접두사 매칭이 이미 유일한 합리적 선택이라 통계 유무가 결정을 바꾸지 않았다(작은 데이터셋
특유의 결과일 수 있으니 실기기에서 재확인은 무해하나 필수는 아님).

## 4. 타이밍 (참고 수치 — 이 개발 머신, Android 실기기 아님)

40쌍(series×field) 전체 순회, 창 550일(`configs/indicators.yaml warmup_padding_days`) 기준:

| 쿼리 | 평균 ms/call |
|---|---|
| 확정 경로(①, OR-파라미터 원문) | 1.30~1.37 |
| 프리뷰 경로(②, OR-파라미터 원문) | 1.33~1.37 |
| `tick_input` 최근 커밋(③) | 0.003~0.004 |

틱당 참고 추정(확정 경로 × 활성 지표가 쓰는 ≈25 (series,field)쌍, D §2.2.2 산정): **≈33~34ms**.
D 문서의 "틱당 ≈9,500행 스캔, 무시 가능"과 방향이 일치한다(로컬 CPython sqlite3 바인딩 기준 —
Android Room/실기기 I/O 특성과는 절대값이 다를 수 있으나, 인덱스가 잡히는 이상 자릿수가
바뀔 근거는 없다).

## 5. 부수 발견 — `ix_obs_scan`이 이 쿼리 형태에서 선택되지 않음

`ix_obs_scan(series_id, field, lane, as_of)`를 **드롭하고 재실행**해도 ①의 플랜이 동일했다:
```
SEARCH o USING INDEX ux_obs_cell_rev (series_id=? AND field=? AND as_of>? AND as_of<?)
CORRELATED SCALAR SUBQUERY 1
SEARCH o2 USING COVERING INDEX ux_obs_cell_rev (series_id=? AND field=? AND as_of=?)
USE TEMP B-TREE FOR LAST 2 TERMS OF ORDER BY
```
원인: `(:includePreviewLane = 1 OR o.lane = 0)`는 파라미터를 포함한 OR식이라 SQLite 플래너가
prepare 시점에 `lane`을 등호 조건으로 인덱스에 밀어넣지 못한다. 그 결과 `lane`이 선두 열인
`ix_obs_scan`은 이 특정 SQL 리터럴에서 쓰일 길이 없다 — `series_id, field, as_of` 접두사만으로도
`ux_obs_cell_rev`가 이미 충분해 실질적 풀스캔 위험은 없지만, **인덱스 설계 의도(§2.2.2의
"비용은 ux_obs_cell_rev 인덱스로 흡수" 서술)와 다르게 `ix_obs_scan`이 여분이 됐다는 뜻**이다.

**대조 실험** — `lane`을 파라미터 대신 리터럴로 고정한 별도 쿼리(확정 전용, `AND o.lane = 0`
하드코딩)로 바꾸면:
```
SEARCH o USING INDEX ix_obs_scan (series_id=? AND field=? AND lane=? AND as_of>? AND as_of<?)
CORRELATED SCALAR SUBQUERY 1
SEARCH o2 USING COVERING INDEX ux_obs_cell_rev (series_id=? AND field=? AND as_of=? AND lane=?)
```
이번엔 `ix_obs_scan`이 4조건 전부(등호 3 + 범위 1)로 완전히 맞아 선택된다. 타이밍도 개선:
평균 0.87ms/call(리터럴 폼) vs 1.30ms/call(OR-파라미터 폼, 3회 반복 중 최솟값 기준) — ≈33% 단축.
틱당(≈25쌍) 환산 차이는 ≈11ms.

**권고(강제 아님)**: 절대 시간이 양쪽 다 밀리초 이하이고 확정 틱은 일 1회 수준 워크로드라
성능상 분리를 요구할 근거가 없다. MT1-03 구현자는 다음 중 택1(둘 다 §6 완료 기준을 깨지 않음):
(a) 현행 "Room `@Query` 1개, 파라미터로 확정/프리뷰 분기" 유지 + `ix_obs_scan` 드롭(미사용
인덱스 유지 비용 제거) — 가장 단순, 또는 (b) `ix_obs_scan` 유지 + 향후 다른 리터럴-lane 쿼리
(예: 감사용 `readAsAt`)가 생기면 그때 쓰이게 둔다. **본 실측은 어느 쪽도 강제하지 않는다** —
풀스캔이 없다는 §3의 결론만이 이 과업의 필수 산출물이고, 이 절은 참고 정보다.

## 6. 실기기 SQLite 버전 — 실측 불가 한계 + 제안

이 환경(Windows 개발 머신, `uv run python`)의 `sqlite3.sqlite_version = 3.50.4`는 CPython에
정적 링크된 SQLite이며 **Android 10(API 29) 시스템 SQLite와 무관**하다. M1_PLAN_D §2.2.2가
전제한 "minSdk 29 번들 SQLite ≈ 3.22, 윈도 함수(3.25+) 미보장"은 이 스크립트로 검증할 수 있는
대상이 아니다 — 실기기·에뮬레이터에서만 확인 가능하다.

**영향 없음**: M-44가 "윈도 함수 미사용은 실측 결과와 무관하게 확정"이라고 이미 못박아서,
이 한계가 §3의 결론(상관 서브쿼리가 인덱스를 탄다)을 흔들지 않는다.

**제안(제안만, 미적용)**: MT1-03c(조회 계약 구현) 완료 기준에 `SELECT sqlite_version()`을
Robolectric/계측 테스트에서 **1회 로깅**하는 항목을 추가할 것을 제안한다. 강제하지 않는 이유:
이미 M-44로 판정이 결정 사항에서 제외됐으므로 이 로그는 순수 참고 정보(향후 실기기 버전이
낮아 상관 서브쿼리조차 이례적으로 느릴 경우의 사후 진단용)이지, MT1-00f의 완료 기준이 아니다.

## 7. 검증

```
$ uv run ruff check scripts/verify_sqlite_query_plans.py    # All checks passed
$ uv run ruff format --check scripts/verify_sqlite_query_plans.py   # 통과(1회 재포맷 후)
$ uv run python scripts/verify_sqlite_query_plans.py
...
self-check: PASS
```

자가검증 4건(스크립트 내 `_self_check`, 플랜 형태가 아니라 **쿼리 결과의 정합성**을 확인):
① 확정 경로는 lane=1만 있는("오늘", 확정 미도착) 셀을 결과에서 제외 ② 프리뷰 경로는 그 셀을
포함 ③ 당일 revision 보정이 있던 셀에서 확정 경로가 최신 revision 값을 선택(구 revision=0 값
아님) ④ `tick_input` 조회가 `composite IS NULL`(동결) 행을 반환하지 않음. 4건 전부 PASS.

## 8. 확정 반영

- `configs/*.yaml` — **무변경**(`configs/indicators.yaml`은 읽기 전용 참조, 이 과업이 반영할
  새 설정값이 없음 — 인덱스·쿼리 형태는 이미 M1_PLAN_FINAL.md M-43/M-44로 확정된 사항의
  실측 확인이었다).
- MT1-03 입력으로 남기는 것: §5의 `ix_obs_scan` 미사용 관찰(권고, 택1), §6의 `sqlite_version()`
  로깅 제안(제안, 미적용).

## 9. 생성/변경 파일 목록

- `scripts/verify_sqlite_query_plans.py` (신규 — 검증 스크립트, 자가검증 포함, 반복 실행 가능)
- `docs/journal/2026-08-07_MT1-00f_sqlite_plan.md` (본 문서, 신규)
