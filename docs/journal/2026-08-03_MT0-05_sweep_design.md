# MT0-05 / BT-03 보정 스윕 — 설계 (Stage A)

- 작성일: 2026-08-03 · 소속: M0 / MT0-05 · 역할: backtest-analyst Worker 산출물
- 개정: 2026-08-03 라운드 4 (라운드 3 CONDITIONAL 해소 — MR3-1·MR3-2, AD-6 반영. 이전 라운드 해소분 유지)
- 지위: **설계 문서**다. `run_sweep.py` 구현·스윕 실행·`configs/` 반영은 하지 않았다(Stage B).
- 근거: `docs/BACKTEST_PLAN.md` §BT-03·§3·§5·§6 / `docs/P0_DESIGN_DECISIONS.md`
  D-04·D-08·D-12·D-13·D-16·D-25(§2 부기 O3-1) / `docs/reviews/REVIEW_M0.md` MT0-04(F-4·O-4·O-7)

> **근사-PIT — C1에서 실측 확정.**
> 이 문서의 모든 수치는 `backtest/fixtures/*.parquet`(BT-01 소급 수집)에 대한 리플레이
> 산출이다. 소급 수집치는 개정치를 포함할 수 있어 진짜 PIT이 아니고(§5.1), server
> 프로파일의 30분 재현은 **모든 창이 일봉 근사**다(§5.2). 여기서 선정되는 0.3.0-rc는
> 1차 보정 가설이며 "모든 임계값은 가설"(D-04) 규율이 그대로 적용된다.
>
> **K-11(look-ahead)**: 이 스윕은 홀드아웃 2창을 제외한 7창에 대한 **in-sample 보정**이다.
> 7창의 성능 수치는 그 창들로 값을 고른 뒤 같은 창에서 측정한 것이므로 낙관 편향을 갖는다.
> 편향 없는 판정은 BT-04의 홀드아웃 검증이며 스윕 절차는 그 두 창을 읽지 않는다(R-03).
>
> **K-07(float64)**: 그리드 수치는 전부 `sweep.yaml`에 십진 리터럴로 명시하고 코드에서
> 재계산하지 않는다. z-임계는 소수 둘째 자리까지, 스테일 창은 정수+단위 문자열("48h").
> composite 비교는 반올림 없는 float64 원값(골든 대조 rel 1e-9), 반올림은 표시 계층에서만.

---

## 0. Advisor 결정 (AD-1~6) — 이 설계가 서 있는 전제

라운드 1 판정 후 Advisor가 확정한 결정이다. 본 문서의 §3·§6·§9는 이 결정의 이행이다.

| ID | 결정 | 반영 |
|---|---|---|
| **AD-1** | **제한적 0.3.0-rc 경로.** BT-03은 0.3.0-rc를 산출한다. (i) 보정은 "실측 신호가 있는 차원 + 물리·SSOT 논증 차원"의 **최소 변경**으로 한정 (ii) 게이트 = 골든 하드 + detection 5/5(w2026만 명시적 축소) + mobile 오탐·플래핑 전면 유지 (iii) server 플래핑은 원인 귀속 분석이 "일봉 근사 아티팩트"를 입증하면 '본 하니스 판정 불가·C1 이관'으로 분류하고 mobile 게이트만으로 랭크 — 입증 실패 시 이 경로는 성립하지 않으며 0.3.0-rc 미산출 대안을 제시하고 중단 (iv) §6 미충족 항목은 판정표에 FAIL 그대로 표기 + GM0 안건. 게이트를 느슨하게 해 "선정 성공"으로 보이게 만드는 처리 금지 | §6.1·§6.3, §9.2 |
| **AD-2** | **R-5 불허.** mobile `stale_profiles`에 `intraday_30m` 키 신설 금지(거동 0 영향 + BT-05 Kotlin 패리티 형상 부담). ④ mobile 그리드는 실측 신호가 확인된 `daily_us` 축(3값)으로 축소하고, `daily_kr`·`fred_daily`·`intraday_30m`의 무효 사실은 그리드가 아니라 **실측 기록**으로 남긴다 | §3④ |
| **AD-3** | **확정 틱 17:00 권고 채택 방향.** 단 (a) "하니스는 확정 틱 시각에 완전 무감 — 09:00조차 동일 산출"임을 명시하고 이 결정이 하니스가 검증하지 못하는 물리·스케줄 논증임을 밝힐 것 (b) `replay.yaml` 실제 변경은 Stage B에서 하되 "M1 모바일 실제 확정 틱 설계와 동시 재확인" 부기를 조건으로 | §3③, §11.1 |
| **AD-4** | **`reentry_cooldown` 그리드 유지.** `statemachine.yaml` 주석 의무("BT-03에서 결정")를 이행하되, mobile 플래핑 게이트가 실질 필터임을 감안해 설계 | §3③, §10 |
| **AD-5** | **경로 ⓑ — server 플래핑 재귀속.** 라운드 2의 "일봉 근사 아티팩트 입증" 서술을 **철회**하고 원인을 **상태기계 설정 모순**(`or_any_crit` 진입 영구 충족 ⊕ `exit_AMBER` 영구 충족)으로 재귀속. 게이트 처리는 **F-2 패턴(명시적 축소)** — "판정 불가·C1 이관" 문구 폐기, 판정표엔 FAIL 그대로 + 원인 귀속 + GM0 안건. `or_any_crit ⊕ exit_AMBER` 충돌을 GM0 안건으로 신설(수정 방향만 스케치, 구현·configs 수정 금지) | §9.2, §13 |

| **AD-6** | **R-03 해석 확정.** 홀드아웃 창의 산출물은 **선정·랭킹·그리드 값 결정의 입력으로 사용 금지**. 다만 설계 단계의 리스크 진단 서술에서 인용은 허용하되 반드시 **"진단 관측(선정 미반영)"** 라벨을 붙인다. 금지 대상은 "읽는 행위"가 아니라 "선정에 되먹이는 행위"다 | `sweep.yaml` `meta`, §9.2(c), §10 R-1 |

**AD-1(iii)의 조건부 판정 결과 — 라운드 3 정정.** 라운드 2에서 "아티팩트 입증 성공"으로
보고했던 판정은 **반증됐다**(§9.2). 제한적 0.3.0-rc 경로 자체는 AD-5의 재귀속 위에서
그대로 성립한다 — server 플래핑을 게이트에서 빼는 근거가 "하니스 판정 불가"에서
"스윕 무감 + 원인이 스윕 대상 밖"으로 바뀌었을 뿐이다.

---

## 1. 요약 (결론 먼저)

| # | 항목 | 결론 |
|---|---|---|
| 1 | 스윕 규모 | 전수 11,760조합(약 7.6시간) → **단계적 154회 평가 / 약 4.6분** |
| 2 | ① usdkrw 임계 | 35조합. 골든 가용 영역 `1.194 < watch ≤ 2.204 < warn` 실측 확정. 통과 24 / 탈락 11 |
| 3 | ② F-04 | **비활성 유지가 BT-03 공식 결론** — 픽스처 9/9 결손 + 수집 경로 부재 + 배선 미구현 + 데이터가 있어도 §6 미충족. 실행 0회 |
| 4 | ③ mobile_daily | 112조합(dedupe 후). `promote_sustain=1`이 골든상 유일 가용값. 확정 틱 시각은 하니스 **완전 무감** → 스케줄 정합으로 17:00 |
| 5 | ④ 스테일 창 | **mobile `daily_us` 3값만**(AD-2). 나머지 3축은 극단값에서도 무효 — 실측 기록만. server는 동결·C1 이관 |
| 6 | w2026 갭 | **스윕 대상 ①~④로 구조적 불가.** 실제 그리드 내 72설정×2프로파일 전수에서 최고 composite 35.15 < 40, ORANGE 0건 |
| 7 | server 플래핑 | 원인은 **상태기계 설정 모순**(`or_any_crit` ⊕ `exit_AMBER` 동시 영구 충족 → 한계진동). 상수 입력 100틱만으로 server 15건·mobile 49건 재현. ① 35조합 전수에서 최솟값=최댓값=20 **불변**(스윕 무감) → 게이트에서 명시적 축소, 판정표엔 FAIL 유지 + **GM0 안건**(§13) |
| 8 | 최대 리스크 | O-4 과민화 — 홀드아웃은 이미 §6 초과 상태이고 in-sample 오탐 항은 **완전 축퇴**(전 후보 (0,0)) |
| 9 | GM0 상신 | 2건 — w2026 해상도 갭(§4.4) + `or_any_crit ⊕ exit_AMBER` 충돌(§13) |

---

## 2. 기준선 실측 (registry 0.2.0)

`backtest/results/metrics.json`을 재생성해 18행 payload가 **바이트 동일**함을 확인했다
(스탬프 2필드만 갱신 — §12.3). 앵커는 **러닝피크 기준 최대 낙폭일**이다(F-6, §6.1).

| 창 | 홀드 | 성격 | 최대낙폭일 | 창내 최대낙폭 | 프로파일 | 첫 ORANGE | 리드(거래일) | AMBER틱 | ORANGE+틱 | 전이 | 최고 composite |
|---|---|---|---|---|---|---|---|---|---|---|---|
| w2011_us_downgrade | | 양성 | 2011-08-22 | 21.32% | server | 2011-08-05 | 10 | 101 | 394 | 7 | 81.63 |
| | | | | | mobile | 2011-08-03 | 12 | 5 | 29 | 6 | 81.63 |
| w2015_cny_deval | H | 양성 | 2015-08-24 | 12.36% | server | 2015-08-24 | 0 | 137 | 244 | 25 | 76.19 |
| | | | | | mobile | 2015-08-24 | 0 | 26 | 17 | 4 | 76.19 |
| w2018_q4_tightening | | 양성 | 2018-10-29 | 15.26% | server | 2018-10-11 | 12 | 448 | 183 | 14 | 65.38 |
| | | | | | mobile | 2018-10-11 | 12 | 32 | 25 | 5 | 60.54 |
| w2020_covid | | 양성 | 2020-03-19 | 35.71% | server | 2020-02-25 | 17 | 138 | 484 | 13 | 87.76 |
| | | | | | mobile | 2020-02-25 | 17 | 18 | 36 | 7 | 87.76 |
| w2022_tightening | | 양성 | 2022-09-30 | 14.92% | server | 2022-09-26 | 4 | 669 | 116 | 10 | 53.74 |
| | | | | | mobile | 2022-09-27 | 3 | 59 | 13 | 7 | 53.74 |
| w2024_carry_unwind | | 양성(골든) | 2024-08-05 | 12.10% | server | 2024-08-05 | 0 | 1 | 79 | 4 | 72.65 |
| | | | | | mobile | 2024-08-05 | 0 | 1 | 5 | 2 | 66.06 |
| **w2026_structural** | | 양성 | **2026-07-30** | **38.63%** | server | **없음** | — | 160 | **0** | 20 | **35.15** |
| | | | | | mobile | **없음** | — | 42 | **0** | 6 | **35.15** |
| w2024_05_calm | | 음성(골든) | — | 2.38% | server | 없음 | — | 0 | 0 | 0 | 0.00 |
| | | | | | mobile | 없음 | — | 0 | 0 | 0 | 0.00 |
| w2023_11_rally | H | 음성 | — | 3.94% | server | 없음 | — | 0 | 0 | 0 | 28.48 |
| | | | | | mobile | 없음 | — | **18** | 0 | 2 | 28.48 |

**기준선의 §6 판정(비홀드아웃 7창)**

| §6 항목 | 기준 | server | mobile | 판정 |
|---|---|---|---|---|
| 탐지율 | 양성 6/6 | 5/6 | 5/6 | **FAIL** (w2026 — 범위 밖 §4) |
| 리드타임 중앙값 | ≥ 1 영업일 | 10 | 12 | PASS |
| 리드타임 w2026 | 07-28 이전 | 미도달 | 미도달 | **FAIL** (범위 밖 §4) |
| 오탐 | ORANGE+ 0건, AMBER ≤ 3틱 | 0 / 0 | 0 / 0 | PASS |
| 플래핑 | 양성 ≤ 6, 음성 ≤ 2 | 최대 20 | 최대 7 | **FAIL** (원인: 설정 모순 §9.2 — server는 범위 밖 축소, mobile은 게이트 유지) |
| 골든 무회귀 | 2케이스 × 2프로파일 | 통과 | 통과 | PASS |

---

## 3. 스윕 4차원 — 그리드 설계 근거

**값 자체는 `backtest/sweep.yaml`에만 있다. 이 절은 근거만 서술하고 값을 복제하지 않는다**
(F-13: 라운드 1의 "사본을 두지 않는다" 선언과 실제 값 복제의 모순을 값 삭제로 해소).

### ① usdkrw_z 임계 (D-12)

- `direction: abs`(D-12/F-03)·weight·transform은 **스윕 대상 밖**. BT-03 ①의 문언은
  "양방향 임계"이고 방향 전환은 D-12로 확정된 결정이다.
- **골든 가용 영역을 그리드 전수 실측으로 확정: `1.194 < watch ≤ 2.204 < warn`.**
  - 상한 2.204 = `w2024_carry_unwind` 2024-08-01 틱의 |z|. 이 틱이 severity 1을 유지해야
    mobile 동결 타임라인의 composite 9.6970이 보존된다. `watch > 2.204`면 6.0606,
    `warn ≤ 2.204`면 13.3333이 되어 둘 다 골든 위반.
  - 하한 1.194 = `w2024_05_calm` 창의 |z| 최대. 그 이하면 음성 골든 창이 발화한다.
  - crit는 골든 2창에 |z| ≥ 3.0 틱이 없어 골든에 구속되지 않는다 → 7창 성능으로만 갈린다.
- 그리드는 가용 영역 밖 값을 **의도적으로 포함**한다(대조군 — 경계가 실제로 탈락하는지
  매 실행에서 재확인하는 증인).
- 7창 |z| 분포(창별 max / #≥1.5 / #≥2.0 / #≥2.5 / #≥3.5): w2011 3.661/7/6/2/1,
  w2018 2.170/7/2/0/0, w2020 5.640/19/14/8/3, w2022 4.550/22/12/3/1,
  w2024_carry 2.204/2/1/0/0, w2026 2.826/8/3/1/0, w2024_05_calm 1.194/0/0/0/0.

### ② F-04 구조지표 2종 (D-13) — 실행하지 않음

§7에서 논증한다. 그리드 구조는 C1 재사용을 위해 보존하되 `execute: false`.

### ③ mobile_daily 프로파일

- **`promote_sustain_ticks = 1`이 골든상 유일 가용값**(§8 실측). 2인 56조합이 전건 탈락한다:
  `w2024_carry_unwind` 2024-08-02 틱이 AMBER → GREEN으로 무너지고, 첫 ORANGE도
  08-05 → 08-06으로 밀려 D-08 원문까지 함께 위반한다.
- **`reentry_cooldown_ticks`는 `statemachine.yaml` 미정의**(주석 "BT-03에서 결정", 엔진
  기본값 0). AD-4대로 그리드를 유지한다.
- **mobile 플래핑 게이트의 실효 노브 — 실측 (MR2-1 정정).** 라운드 2의 "cooldown 3에서
  처음 충족 / cooldown이 유일한 실효 노브"는 **실측과 다르다.** 골든 통과 56조합 중
  §6 mobile 플래핑(양성 ≤ 6, 음성 ≤ 2)을 통과하는 것은 **12조합**이며, 실효 노브는
  cooldown 단독이 아니라 **`min_dwell`·`demote_below`·`cooldown`의 조합**이다.

  | 통과 `(demote_below, min_dwell, reentry_cooldown)` | 비고 |
  |---|---|
  | (2, 5, 3) · (3, 5, 2) · (3, 5, 3) · (4, 2, 3) · (4, 5, 2) · (4, 5, 3) | × `confirm_time` 2값 = **12조합** |

  - `dwell` 집합 **{2, 5}**, `cooldown` 집합 **{2, 3}** — **`cooldown = 2`에서도 이미 통과**한다
    (4조합). "cooldown ≥ 3만 통과"는 거짓이다.
  - **통과 6조합 중 5개가 `min_dwell = 5 > demote_below`**(O3-1 비무효 영역)를 요구한다.
    즉 **`min_dwell > demote_below` 조합을 그리드에 의도적으로 포함한 설계 판단이 실측으로
    정당화됐다** — O3-1의 무효 구간만 남겼다면 통과 조합의 대부분을 잃었을 것이다.
  - server 플래핑은 AD-5로 게이트에서 범위 밖 축소(§9.2)되므로 이 축들이 선정의 중심이다.
- **O3-1(D-25 §2 부기) 반영**: `min_dwell ≤ demote_below` 구간은 거동 동일 → `sweep.yaml`
  `dedupe.collapse_inert_min_dwell`로 대표값 하나로 접는다(144 → 112). 동시에 dwell 게이트를
  **실제로 시험하기 위해** `min_dwell > demote_below` 조합을 의도적으로 포함했다. 이 조합은
  실측상 거동이 다르다 — (promote 1, demote 3, cooldown 2)에서 dwell 4 → 전이 7, dwell 5 → 전이 6.
- **확정 틱 시각(O-7 / AD-3)**: 하니스는 이 값에 **완전 무감**이다. 09:00·14:00·15:40·
  16:20·17:00·18:00·23:50의 **9창 전 틱 산출이 모두 비트 동일**했다(서명 `cc37b134f1da`).
  일봉 근사에서 틱 간격이 어느 값이든 정확히 24h라 스테일 판정도 불변이기 때문이다.
  즉 §6 수치로는 두 값을 구분할 수 없고 **이 결정은 하니스가 검증하지 못하는 물리·스케줄
  논증**이다:
  - **하한 1(O-7 물리 하한)**: KRX 정규장 마감 15:30 KST 이후여야 그날의 확정치다.
    (MT0-04 O-7이 "14:00으로 당겨도 골든이 통과한다"고 지적한 구멍 — 위 무감 측정이 그
    지적을 09:00까지 확장해 재확인했다. 하니스는 이 구멍을 막지 못하므로 제약을
    `sweep.yaml` `constraint`에 명시해 막는다.)
  - **하한 2(스케줄 정합)**: `schedules.collection.daily_kr` = **16:50 KST**. 16:20은 그날의
    KR 수집이 끝나기 30분 전에 확정 틱을 도는 셈이라 SSOT 스케줄과 모순된다.
  - → **17:00**. 수집 이후이고 server `kr_close` 평가 틱(17:00)과 같은 시각이라 SSOT의
    서로 다른 숫자가 하나 줄어든다. 16:20은 BACKTEST_PLAN이 명시한 비교 대상이므로
    그리드에 남기되 **하한 미달로 평가 후 기각**되는 값이다(대조군이 아니라 기각 대상).

### ④ 스테일 창 (AD-2 / F-1)

라운드 1의 "`daily_kr`이 최고 레버리지 노브" 서술은 **오류였고 실측으로 반증됐다.**
축별로 극단값까지 흔들어 9창 산출 서명을 비교한 결과:

| 축 | 시험값 | 서로 다른 산출 | 판정 |
|---|---|---|---|
| `daily_kr` | 1h / 30h / 48h / 72h / 240h | **1종** | **완전 무효** |
| `intraday_30m` | 키부재 / 1m / 8h / 30h | **1종** | **완전 무효** → 키 신설 금지(AD-2) |
| `fred_daily` | 1h / 96h / 120h / 168h / 480h | 2종 (1h만 다름) | 의미 구간에서 무효 |
| `daily_us` | 1h / 48h / 72h / 96h / 240h | **5종** | **유일한 유효 축** |

**무효의 원인**(추측이 아니라 구조적 설명): mobile 틱은 하루 1회이고, 거래일 그리드 자체가
KRX 관측일의 합집합(`run_replay.trading_days`)이다. 따라서 **모든 그리드일에 그날의 KR
관측이 존재해 나이가 항상 0**이고, `daily_kr` 스테일은 구조적으로 발동할 수 없다.
`intraday_30m`은 `daily_kr`을 상속하므로(`registry.stale_window` 확정 해석) 동일하다.
`fred_daily`는 1h에서만 7창이 달라져 **배선이 살아 있음은 확인**되나 정책적으로 의미 있는
구간(96h 이상)에서는 무효다. `daily_us`만 `^VIX3M`·`^MOVE`의 2026-07-17 절단(K-01/K-18)과
US·KR 휴장일 불일치에 실제로 걸린다.

→ AD-2대로 그리드는 `daily_us` 3값으로 축소하고 나머지 3축의 무효 사실은 그리드가 아니라
실측 기록으로 남긴다. server는 §9.2에 따라 동결·C1 이관.

---

## 4. w2026 정량 진단 — 스윕의 중심 문제

### 4.1 현상

창 안에서 KOSPI가 9,114.55(06-22) → 5,593.56(07-30)으로 **38.63% 하락**했는데 composite는
최고 35.15로 ORANGE 임계 40에 닿지 않는다. 그 최고치는 **06-08**에 나왔고 실제 최대낙폭일
07-30의 composite는 29.79다 — 하락이 깊어질수록 조용해지는 역전.

### 4.2 원인 분해 (07-30 틱, mobile)

| 지표 | weight | severity |
|---|---|---|
| usdkrw_z | 3.0 | **3** (modifier 포함, 이미 상한) |
| kospi_drawdown | 2.5 | **3** (crit 임계 7.0% — 실제 38.63%도 같은 3) |
| spx_drawdown_momentum | 2.0 | 1 |
| vkospi_z | 2.5 | 1 (realized_vol 폴백 K-02, 252일 z 자기정규화) |
| vix_level_z 3.0 · dxy_z 1.5 · ust_2s10s_move 1.0 · global_corr_break 1.5 · hy_oas_delta 3.0 · foreign_net_sell_kospi 2.0 · kospi_volume_distribution 1.5 | **13.5 합** | 전부 0 |
| vix_term_structure 2.5 · move_index_z 1.5 | 4.0 | 결측(야후 2026-07-17 절단) |
| krx_credit_spread_delta 2.0 · kr_cds_5y_delta 1.5 | 3.5 | 결측(BT-01 수집 범위 밖) |

유효 가중 Σw = 3.0 + 2.5 + 2.0 + 2.5 + 13.5 = **23.5** / 31.0 (coverage 0.758)
→ composite = 100 × 21.0 / (3 × 23.5) = 29.79. *(F-10: 라운드 1의 "11.5 합"은 오기)*

핵심은 **severity 포화**다. `kospi_drawdown`의 crit 임계가 7.0%라 38.63% 낙폭이 7.1%와
정확히 같은 값(3)을 낸다. `vkospi_z`는 252일 롤링 z라 폭락이 길어질수록 자기 표준편차가
커져 오히려 내려간다. 임계값 보정 문제가 아니라 **해상도(F-06) 문제**이며 BT-04의
대응안 3종이 다루도록 설계된 영역이다.

### 4.3 스윕 대상별 반사실 계산

**① usdkrw 임계 — 닫히지 않는다.** 07-30에 이미 severity 3이라 여지가 없다. 07-28은
severity 2 → 3으로 올려도 22.70 → 26.96.

**④ 스테일 창 — 역효과다.** `^VIX3M`·`^MOVE`는 절단 직전 값이 **severity 0**이었다
(07-22 틱에서 신선한 상태로 둘 다 0 확인). `daily_us`를 넓히면 그 0값이 분모에만 더 오래
들어온다. 실측: 96h에서 w2026 최고 composite 35.15 불변, AMBER 틱 42 → 41, 전이 6 → 8.

**①×④ 실제 그리드 내 전수 — ORANGE 0건.**

> **실행 조건 명시(F-4).** `sweep.yaml`의 **실제 그리드 안에서만** 돌렸다:
> ① 골든 통과 **24조합** × ④ `daily_us` **3값** = **72설정**, 각 설정 × 2프로파일 =
> **144회 실행**. server 스테일은 **현행 동결값**이다.
> *(라운드 1은 server `intraday_30m`을 스윕 공간 밖 값 26h로 고정하고, 그리드와 다른
> watch 열거 [1.25…2.0]에 ④는 4조합만 쓴 "128설정"을 보고했다 — 조건과 서술이
> 불일치했다. 아래 수치는 그리드 기준으로 재산출한 것이다.)*
>
> **결과: 최고 composite 전 조합 35.15, 첫 ORANGE 0건.**

**③ 상태기계 — 원리상 불가능.** `engine_ref.statemachine.run`은 `Tick(composite,
distinct_axes, any_crit)` 시퀀스만 받는 순수 후처리다. composite를 1점도 바꾸지 못하므로
`composite_gte: 40` 규칙을 만족시킬 수 없다.

**② F-04 — 데이터가 있어도 §6은 미충족.** 두 지표가 전 구간 crit(3)이라는 최대 가정의
반사실 composite(mobile):

| 날짜 | 기준선 | +F04(3,3) | +F04(2,2) |
|---|---|---|---|
| 2026-07-23 | 16.31 | 30.99 | 25.15 |
| 2026-07-24 | 14.18 | 29.24 | 23.39 |
| **2026-07-27** | 10.64 | **26.32** | 20.47 |
| **2026-07-28** | 22.70 | **36.26** | 30.41 |
| **2026-07-30**(최대낙폭일) | 29.79 | **42.11** | 36.26 |
| 2026-07-31 | 28.37 | 40.94 | 35.09 |

최대 가정에서도 ORANGE 도달은 **07-30 = 최대낙폭일 당일**, 리드타임 0이고 §6의
"07-28 이전 도달"은 미충족이다. 07-28 이전을 ORANGE로 만들려면 07-27의 10.64를 40으로
끌어올려야 하는데 **F-04 2종을 crit로 켜도 26.32에 그친다.**

> *(F-11: 라운드 1의 "유효 가중 전체를 다 켜도 도달하지 못한다"는 오류다 — 전 지표를
> crit로 켜면 정의상 100.00이다. 정확한 진술은 "F-04만으로는 26.32이며, 40에 닿으려면
> 스윕 대상 밖 지표들이 추가로 발화해야 한다"이다.)*

### 4.4 결론

> **w2026의 §6 갭은 스윕 대상 ①~④로 닫히지 않는다.** 지렛대는 ①③④ 어디에도 없고
> ②마저 최대 가정에서 리드타임 0에 그친다. 구조적 불가다.

원인은 임계값이 아니라 **severity 포화 + z-score 자기정규화**, 즉 해상도(F-06)다. 이 갭을
닫으려면 스윕 대상 밖 개입(upgrade 임계 조정, `kospi_drawdown` 임계 사다리 확장,
severity 4단계화)이 필요하다. **이 문서는 그것을 "제안"으로만 기록하고 그리드에 넣지
않는다.** 정식 경로는 BT-04의 해상도 대응안 3종 비교 시뮬레이션이며 채택은 사용자 승인
사항이다. **GM0 안건으로 상신한다.**

부기: 이 진단은 D-14(2026-07-28의 세 번째 골든 승격) 보류 권고를 강화한다 — 현행
레지스트리는 이 창을 탐지하지 못하므로 골든으로 동결할 대상 자체가 없다.

---

## 5. 조합 수 · 실행 예산 · 전수 vs 차원 분리

### 5.1 실측 실행 단가

| 단위 | 실측 |
|---|---|
| 골든 2창 × 2프로파일 (사전 필터) | 0.72s |
| 스윕 7창 × 2프로파일 | 1.61s |
| 스윕 7창 × mobile only | 0.84s |
| 전 9창 × 2프로파일 (MT0-04 실측 3.5s와 정합) | 3.6s |

후보 1건 완전 평가 = 0.72 + 1.61 = **2.33s**. 골든 사전 필터 탈락 시 0.72s.

### 5.2 전수 조합 vs 단계적

전수 |①|×|④|×|③| = 35 × 3 × 112 = **11,760조합** × 2.33s ≈ **7.6시간**.
단계적: S1(① 35) → S2(④ 3) → S3(교차 4) → S4(③ 112) → S5(최종) = **154회 / 약 4.6분**.

7.6시간은 절대적으로 불가능한 예산은 아니다. 그럼에도 단계적을 택하는 이유는 시간이
아니라 **해석 가능성**이다 — 11,760행의 표에서는 어떤 차원이 무엇을 바꿨는지 귀속할 수
없고, ④가 3값뿐인 상황에서 전수는 같은 ③ 응답을 105번 반복 측정하는 것에 지나지 않는다.

### 5.3 차원 간 상호작용을 무시해도 되는가 — 논증

**(1) ③은 ①④와 원리적으로 직교한다(증명).** `statemachine.run`의 입력은
`Tick(composite, distinct_axes, any_crit)` 시퀀스뿐이다. ①④는 이 시퀀스를 결정하고 ③은
그것을 국면 타임라인으로 사상한다 — `phase = f₃(f₁₄(x))`이며 ③의 파라미터는 `f₁₄`에
들어가지 않는다. 따라서 "①④ 고정 후 ③ 전수"는 근사가 아니라 **정확**하다. 남는 것은
"①④의 최적이 ③에 의존하는가"인데(순위 지표가 phase 기반이라 배제되지 않는다), S4를
S3 승자 위에서 전수로 돌고 ③이 바뀌면 S5 전에 S3 상위 후보를 새 ③으로 재평가해 흡수한다.

**(2) ①과 ④는 작용 지점이 겹치지 않는다(F-7 정정).**
라운드 1의 "스테일 4조합 결과가 완전히 동일했다"는 서술은 **부정확했다** — 불변인 것은
w2026의 `max_composite`뿐이고, `daily_us`는 실제로 per-tick 산출을 바꾼다(96h에서 w2026
전이 6 → 8, §3④ 표의 5종 산출). 정확한 직교 근거는 **작용 대상 지표 집합이 서로소**라는
것이다: ①은 `usdkrw_z` 한 지표의 severity 분류만 바꾸고, ④ `daily_us`는 US 계열 지표
(`vix_level_z`·`vix_term_structure`·`move_index_z`·`spx_drawdown_momentum`·`dxy_z`)의 생존
기간만 바꾼다. **교차하는 지표가 하나도 없다** — `usdkrw_z`의 cadence는 `intraday_30m`이고
mobile에서 `daily_kr`을 상속하므로 `daily_us`와 무관하다.

**(3) 그럼에도 가정을 검증한다(S3).** S1·S2의 상위를 교차해 실제로 돌린다.

**`top_k`의 단위 재정의(MR2-2).** `top_k`는 "랭킹 상위 k개 **후보**"가 아니라
**"선정 서명 기준 서로 다른 상위 k개 등가류"**이며, 각 등가류에서 대표 후보 1개씩만
교차한다. 상위 2후보가 같은 등가류에 속하면 교차 검증이 **조용히 축퇴**하는 구멍을
막기 위한 정의다. 선정 서명 = 7창 각각의
`(max_phase, n_transitions, first_orange_or_above_date)`.

**등가류 수 — 측정 기준을 명시한 실측(MR2-2 정정).** 라운드 2는 기준을 밝히지 않고
"2종"이라고만 썼다.

| 차원 | 후보 수 | 전체 `summary` 기준 | **선정 서명 기준** |
|---|---|---|---|
| ① (골든 통과분) | 24 | **4종** | **2종** |
| ④ `daily_us` | 3 | **3종** | **2종** |

선정은 선정 서명만 보므로 `top_k = 2`로 충분하고 그 이상은 같은 등가류를 중복 측정하는
낭비다(등가류가 k보다 적으면 있는 만큼만 쓴다). 교차 4조합. 교차 최적이 각 차원 단독
최적의 조합과 다르면 분리 가정이 깨진 것이므로 ①×④ 전수(105조합 ≈ 4분)로 자동 승격한다.

---

## 6. 선정 규칙의 조작적 정의

**고정 문언(변경하지 않음)**: "홀드아웃 제외 7창에서 수용 기준(§6) 통과 조합 중 오탐 최소
→ 리드타임 최대 순. 동률이면 단순한 값."  아래는 **계량 방법만** 확정한 것이다.

### 6.1 게이트

| 항목 | 조작적 정의 | 적용 |
|---|---|---|
| 탐지율 | `max_phase ≥ ORANGE`인 양성 창 수. **w2026_structural만 명시적으로 제외한 5/5 필수** | 양쪽 |
| 리드타임 | 창별 `lead = (최대낙폭일 거래일 인덱스) − (첫 ORANGE 이상 틱의 거래일 인덱스)`. 단위는 달력일이 아니라 거래일 그리드 인덱스 차. 앵커 = **러닝피크 기준 최대 낙폭일**. 집계 = 양성 창 중앙값, 기준 ≥ 1 | 양쪽 |
| 리드타임(창 지정) | w2026 첫 ORANGE < 2026-07-28. **도달 불가 증명됨 → FAIL 그대로 보고 + GM0 안건** | 양쪽 |
| 오탐 | 음성 스윕 창의 ORANGE+ 틱수 = 0 **그리고** AMBER 틱수 ≤ 3 | 양쪽 |
| 플래핑 | 양성 전이 ≤ 6, 음성 ≤ 2 | **server를 명시적 축소로 제외**(F-2 패턴, AD-5) — mobile은 게이트 유지. §9.2 |
| 골든 | §8 하드 제약 (사전 필터) | 양쪽 |

**detection을 통째로 빼지 않는 이유(F-2 / AD-1 ii).** 도달 불가가 증명된 창은
`w2026_structural` **1창뿐**이다(§4). 라운드 1은 `out_of_scope_criteria`에 `detection`을
통째로 넣어 나머지 5개 양성 창의 탐지 상실까지 무료로 허용하는 과다 제거를 했다.
그 1창만 명시적으로 축소하고 **5/5를 필수**로 둔다.

**앵커 정의 확정(F-6).** §6 문언 "최대 낙폭일"에 충실하게 **러닝피크 기준**으로 한다
(peak = 창내 누적 최고 종가, dd = (peak−close)/peak 의 최대일). 라운드 1은 "최저 종가일"을
썼다. **6개 양성 스윕 창 전부에서 두 정의가 일치하므로 §2 표의 수치 영향은 없다.**
두 정의가 갈리는 것은 홀드아웃 음성 창 `w2023_11_rally`(11-01 vs 11-13)뿐이고 음성 창은
앵커를 쓰지 않는다.

**탐지 상실이 순위를 올리는 역전의 봉쇄(F-2).** `undetected_handling: exclude_from_median`과
rank 2순위(리드타임 최대)를 결합하면 원리적으로 "리드가 짧은 창의 탐지를 잃으면 중앙값이
올라가 순위가 개선되는" 역전이 가능하다(예: server 리드 [10,12,17,4,0] 중앙값 10 →
w2024 탐지 상실 시 11). **이 역전은 detection 게이트가 5/5를 요구함으로써 규칙 수준에서
봉쇄된다** — w2026 외의 탐지 상실은 중앙값 계산에 도달하기 전에 탈락한다. w2026은 이미
전 후보에서 미탐지이므로 후보 간 차등을 만들지 않는다.

**오탐 항의 in-sample 완전 축퇴(F-8).** 라운드 1은 "AMBER 항이 유일한 해상도"라고 썼으나
**거짓이다.** 음성 스윕 창 `w2024_05_calm`은 전 틱 composite **0.00**이라 실측상 ① 24조합·
③ 56조합 **전수에서 AMBER 틱수가 항상 0**이다(관측된 값의 집합 = {0}). 즉 rank 1순위
(오탐 최소)는 전 후보가 (0, 0)으로 동률이며 **완전히 축퇴**한다 — **실질 선정은 rank 2
(리드타임) 이하로 환원된다.** 이 사실은 BT_REPORT 스윕 표에 명시해야 한다
(`sweep.yaml` `rank[0].degenerate_in_sample: true`). 과민화에 대한 실효 완충은 오탐이 아니라
**mobile 플래핑 게이트**다.

### 6.2 순위

1. **오탐 최소** — `(음성 ORANGE+ 틱수 합, 음성 AMBER 틱수 합)` 사전식 오름차순, 두 프로파일
   합산. *(in-sample 완전 축퇴 — 위 참조)*
2. **리드타임 최대** — `(양성 lead 중앙값, 양성 lead 합)` 내림차순, 두 프로파일 평균.
   합을 2차 타이브레이크로 두는 이유: 중앙값만으로는 6창 중 3창이 동시에 개선돼도 구분되지 않는다.
3. **단순한 값** — (a) 현행 0.2.0 대비 **변경 파라미터 개수**(보수성 원칙이자 O-4 완충)
   → (b) 수치 파라미터의 **소수 자릿수 합**(`2.5`→1, `1.75`→2) → (c) 정규화 yaml **사전식**
   (완전 결정론적 최종 타이브레이크 — 선정이 dict 순회 순서 같은 우연에 의존하지 않게).

### 6.3 축퇴 처리 — AD-1의 제한적 0.3.0-rc 경로

라운드 1은 §6 전 항목 AND 적용 시 feasible set이 공집합임을 지적하고 승인을 요청했다.
**AD-1이 그 답이다**: BT-03은 0.3.0-rc를 산출하되 다음을 지킨다.

1. 보정은 **실측 신호가 있는 차원**(① usdkrw, ③ mobile 프로파일, ④ `daily_us`)과
   **물리·SSOT 논증 차원**(확정 틱 시각)의 **최소 변경**으로 한정한다.
2. 게이트는 골든 하드 + detection 5/5 + mobile 오탐·플래핑을 **전면 유지**한다.
   느슨하게 만들지 않는다.
3. server 플래핑은 **F-2와 같은 명시적 축소**로 게이트에서 프로파일 범위만 좁힌다
   (AD-5) — 근거는 스윕 무감(20 불변)과 원인이 스윕 대상 밖(설정 모순)이라는 것이다(§9.2).
4. **§6 미충족 항목(w2026 탐지·리드타임, server 플래핑)은 BT_REPORT 판정표에 FAIL 그대로
   표기하고 GM0 안건으로 올린다.** 결과 라벨은 `0.3.0-rc (조건부)`.

---

## 7. F-04 경로 결정 — **(b) 비활성 유지**

근거 넷을 독립적으로 제시하며, 어느 하나만으로도 (a) 소량 실측 수집을 기각하기에 충분하다.

**1 — 픽스처 결손 범위: 9창 전부.** 9개 `*.meta.json` 전수 확인 결과 두 지표가
**9/9 창 모두 `status: uncollected`**다. BT-01 collection plan이 `krx_notice`·`krx_margin`
제공자를 제외했기 때문이며(MT0-03 범위 밖) 특정 창만의 문제가 아니다.

**2 — 수집 경로 자체가 없다.** `pykrx.stock` 공개 함수 90개를 실측 조회한 결과
신용융자잔고(`margin`/`credit`/`loan`)·서킷브레이커(`halt`/`circuit`/`sidecar`)에 대응하는
함수가 **0개**다. (a)는 "소량 실측"이 아니라 **신규 수집기 개발**이며, 2011·2015·2018 소급까지
필요하므로 KRX 정보데이터시스템/공시 아카이브 역사 스크레이퍼가 있어야 한다(M0 범위 밖,
K-03 1초 간격 제약 하 호출량도 무시 불가).

**3 — 하니스가 배선돼 있지 않다.** `enabled: true`로 바꾸면 `run_replay`가 즉시
`KeyError: 'krx_halt_events'`로 실패한다(`_BUILDERS` 미등록, 실측 확인).

**4 — 결정적: 데이터가 있어도 §6을 충족하지 못한다.** §4.3대로 최대 가정에서도 ORANGE
도달은 07-30(최대낙폭일 당일), 리드타임 0이다. (a)에 투자해도 BT-03이 답할 질문의 답은
바뀌지 않는다.

**결론.** D-13("활성화 여부는 C1에서 판정") + BACKTEST_PLAN("희석 문제의 해가 없으면
비활성 유지가 결론")에 따라 **비활성 유지(픽스처 결손, C1 실측 후 재판정)**를 공식 결론으로 한다.

부기: "희석" 우려 자체는 이 하니스에서 성립하지 않는다 — D-02 결측 분모 제외 규율상
데이터 없는 지표를 켜는 것은 composite에 무영향이다(켜면 크래시할 뿐). 희석 여부는 실제
데이터가 있는 C1에서만 판정 가능하다.

---

## 8. 골든 무회귀 하드 제약의 집행 방식

**집행 지점**: 후보별 평가의 최초 단계(`stage: pre_filter`). 7창 성능 산출보다 먼저 돌려
위반 후보는 성능 계산 자체를 생략한다. 성능과 무관하게 즉시 탈락하며 순위에 오르지 않는다.

**판정 내용**: `backtest/test_golden.py`의 4개 단언과 동일 의미. 재구현하지 않고 그 모듈을
import하거나 동일 yaml을 읽어 같은 비교를 한다. 두 골든의 **강도가 비대칭**이며 이 비대칭이
그리드 가용 영역을 결정한다.

| 프로파일 | 판정 | 강도 |
|---|---|---|
| server_intraday | D-08 원문 의미 판정: 2024-08-05 `kr_close` 틱 `phase ≥ ORANGE` + 발화 축에 `vol_global`·`kr_flow_price` 포함, 음성 창 전 틱 `≤ AMBER` | 느슨 |
| mobile_daily | `golden_mobile.yaml` 동결 타임라인 완전 일치: 틱별 `phase`·`composite`(rel 1e-9)·`coverage`·`fired_axes` | 엄격 |

**실측 결과 — 전부 `sweep.yaml` 그리드 기준 재산출(F-5).** 라운드 1의 표(37/22 등)는
그리드와 다른 후보 집합에서 나왔고 집합 정의도 없어 재현 불가였다. 아래는 §12.1 스크립트가
`sweep.yaml`을 읽어 생성한다.

| 차원 | 후보 집합의 정의 | 조합 수 | 통과 | 탈락 | 탈락 사유 |
|---|---|---|---|---|---|
| ① usdkrw 임계 | `grid` 전수 → `constraint.monotonic` 적용 | **35** | **24** | **11** | 11건 **전건** `mobile w2024_carry_unwind 2024-08-01` composite 이탈 |
| ③ mobile 프로파일 | `grid` 전수 → `dedupe.collapse_inert_min_dwell` 적용 | **112** | **56** | **56** | 56건 전건 `promote_sustain_ticks=2` → `mobile w2024_carry_unwind 2024-08-02 phase GREEN != AMBER` |
| ④ mobile `daily_us` | `grid` 전수 | **3** | **3** | 0 | — |
| ③ 확정 틱 시각 | 09:00~23:50 감도 시험 7값 | 7 | 7 | 0 | — (완전 무감) |

④와 확정 틱 시각이 골든에 전혀 걸리지 않는다는 것은, 이 두 차원에 대해 **골든이 안전망
역할을 하지 못한다**는 뜻이기도 하다(§10 R-6).

---

## 9. §5 한계 하의 "판정 가능 / 불가" 경계

### 9.1 차원별 경계

| 차원 | 판정 가능? | 근거 |
|---|---|---|
| ① usdkrw 임계 | **가능(제한적)** | 일봉 KRW=X의 z와 일중 변동폭 modifier를 쓴다. 단 §5.4의 KRW=X 가시성 ~16h 근사(O-1)로 경계 부근 틱 분류는 C1에서 흔들릴 수 있다 |
| ② F-04 | **불가** | 데이터 자체가 없다(§7) |
| ③ mobile 프로파일 | **가능** | 픽스처가 일봉이고 mobile 틱도 일 1회 — 리플레이의 시간 해상도가 실거동과 **같다** |
| ③ 확정 틱 시각 | **불가(완전 무감)** | 09:00~23:50 전 값이 비트 동일. 물리·스케줄 정합으로만 결정(§3③, AD-3) |
| ④ mobile `daily_us` | **가능** | 위와 동일 |
| ④ mobile 나머지 3축 | **판정 불필요** | 극단값에서도 무효 — 흔들 대상이 아니다(§3④) |
| **④ server 스테일** | **불가 → C1 이관** | 겨눌 일중 관측이 픽스처에 없다 + 홀드아웃 파괴 위험, §9.2(c) |
| **server 플래핑(§6 항목)** | **측정은 가능, 보정은 스윕 밖** | 스윕 무감(20 불변) + 원인이 설정 모순 → 게이트 범위 축소 + GM0 안건, §9.2(b) |

### 9.2 server 프로파일 — §6 충족성과 원인 귀속 (F-3 / AD-1 iii)

라운드 1은 §6 충족성 분석을 mobile 단독으로 돌려 server를 통째로 누락했다. 재분석 결과:

**(a) server 플래핑은 스윕 공간 전체에서 도달 불가다.**
차원 ① **35조합 전수** × 비홀드아웃 양성 6창을 server로 실행한 결과, 양성 창 최대 전이수는
**최솟값 = 최댓값 = 20**이었다(§6 기준 ≤ 6, 통과 0건). ①로는 1건도 움직이지 않는다.

**(b) 원인은 상태기계 설정 모순이다 — 라운드 2의 "아티팩트 입증"은 철회한다.**

> **철회 고지.** 라운드 2는 "server 플래핑은 일봉 근사 아티팩트이며 실거동 30분 데이터에서는
> 발생하지 않는다"고 결론했다. 이 결론은 **반증됐고 철회한다.** 아래는 독립 재현으로
> 확인한 반증 근거와 재귀속이다(AD-5).

**반증 1 — 정보 중복이 전혀 없는 입력에서도 영구 진동이 난다.**
상수 입력(`composite = 12.8205` 고정, `any_crit = True`, `distinct_axes = 1`) 100틱을
`engine_ref.statemachine.run`에 그대로 넣으면:

| 프로파일 | 전이 | 주기 | 타임라인 앞 24틱 |
|---|---|---|---|
| server_intraday | **15 / 100틱** | 6.7틱 | `GAAAAAAGGGGGGGGAAAAAAGGG` |
| mobile_daily | **49 / 100틱** | 2.0틱 | `AAAGAAAGAAAGAAAGAAAGAAAG` |

데이터도 픽스처도 개입하지 않는다. 진동은 **상태기계 자체의 성질**이다.

**반증 2 — 원인은 `statemachine.yaml`의 설정 모순이다.**

```
upgrade.AMBER      = {composite_gte: 20, or_any_crit: true}
downgrade.exit_AMBER = {composite_lt: 14}
```

`composite = 12.8205`, `any_crit = True`에서:
- 진입 조건: `12.8205 >= 20` 은 거짓이지만 `or_any_crit` 때문에 **영구 충족(True)**
- 이탈 조건: `12.8205 < 14` → **영구 충족(True)**

**진입과 이탈이 동시에, 영구히 충족된다.** 그 결과 국면은 카운터 주기
(`promote_sustain` ↔ `demote_below` + `min_dwell` + `cooldown`)에 맞춰 한계진동한다.
실측 증인 — 2026-05-19 server는 하루 종일 `composite = 12.8205`·`any_crit = True`로
**완전히 고정**인데 `AMBER → GREEN(11:30) → AMBER(15:30)`으로 두 번 전이한다.

**반증 3 (보조) — 정보를 부활시켜도 §6을 통과하지 못한다. 단 수치는 근거로 쓰지 않는다.**
평탄구간의 연속 동일 composite를 보간으로 대체(틱 수는 유지, 정보만 부활)한 반사실은
**knot 규약에 심하게 의존한다** — w2026_structural 기준으로

- 관측값 **대체** 규약(구간 앞뒤 값 사이 균등 보간): 20 → **9**
- 관측값 **보존** 규약(관측 틱을 knot으로 고정): 20 → **23** (감소가 아니라 **증가**)

로 크기뿐 아니라 **방향까지 반전된다**. 따라서 **이 표의 수치는 근거 지위에서 내리고
문서에 측정치로 남기지 않는다**(MR3-1). 규약을 고르는 원칙이 없는 한 "보간하면 9로 준다"는
인용은 성립하지 않는다.

남는 사실은 하나다: **어느 규약에서도 §6 기준(≤ 6)에 도달하지 못한다.** 즉 "정보만
부활하면 통과한다"는 라운드 2의 주장은 어느 쪽으로도 성립하지 않는다.

> **결론의 하중은 반증 1·2가 진다.** 재귀속(원인 = 상태기계 설정 모순)은 **반증 1(상수 입력
> 100틱만으로 재현되는 영구 진동)**과 **반증 2(진입·이탈 조건의 동시 영구 충족)**만으로
> 완결된다 — 둘 다 데이터·보간 규약과 무관하다. 반증 3은 보조 관찰이다.
>
> **라운드 2 교차 이력(정직 고지).** 비평가의 "20→20 감소 없음"은 비평가 자신의 구현 오류
> (항등함수)로 철회됐고, 본 문서 `[T5]` 구현(20→9)은 비평가가 정확히 재현했다. 그 위에서
> 올바른 독립 구현(관측값 보존 규약)이 20→23을 내면서 규약 민감도가 드러났다 — 그래서
> 위와 같이 수치를 근거에서 내린다.
>
> **한계진동형 분류 수치도 정의 의존적이다.** 본 문서의 판별식은 "Δcomposite = 0 ∧ 양쪽
> `any_crit` ∧ `composite < upgrade.AMBER.composite_gte`"이며 w2026 server 20건 중
> **12건**으로 나온다(비평가는 다른 판별식으로 15건). 기전에 대한 판단은 양쪽이 같다.

**철회되는 지표.** `flap_info_backed`(정보 뒷받침 전이만 센 값, server 20 → 5)는 이제
**결론을 지지하지 않는다.** 상수 입력 진동이 보여주듯 "Δcomposite = 0에서 일어난 전이"는
정보 중복의 증거가 아니라 **설정 모순의 증상**이다. 이 지표는 근거 지위에서 내리고,
관측 기록으로만 존치한다(§12.1 `[T4]`, 라벨을 "정보 뒷받침 전이"에서
**"한계진동 노출도 관측치"**로 정정).

**(b') 그럼에도 게이트에서 빼는 근거는 유효하다 — F-2 패턴의 명시적 축소.**

1. **스윕 무감(§9.2a의 실측은 유효).** ① 35조합 전수에서 server 양성창 최대 전이수는
   최솟값 = 최댓값 = **20 불변**이다. 게이트로 두면 **전 후보를 똑같이 탈락**시켜 선정을
   봉쇄할 뿐 후보를 전혀 변별하지 못한다.
2. **원인이 스윕 대상 밖이다.** 전이 규칙(`upgrade`/`downgrade`) 수정은 BACKTEST_PLAN
   §BT-03의 스윕 대상 ①~④ 어디에도 속하지 않는다.

> **AD-5 판정.** server 플래핑은 detection의 w2026 처리(F-2)와 **같은 패턴**으로 —
> 항목을 없애지 않고 **프로파일 범위만 명시적으로 축소**해 — 게이트에서 제외한다.
> BT_REPORT 판정표에는 **FAIL 그대로** 표기하고 원인 귀속(설정 모순, 전이 규칙 수정은
> 스윕 대상 밖)을 병기하며 **GM0 안건**(§13)으로 올린다.
> **"본 하니스 판정 불가·C1 이관" 문구는 폐기한다** — 이것은 하니스의 한계가 아니라
> **SSOT 결함 후보**다.
>
> **mobile은 게이트를 유지한다.** 동일 기전에 노출돼 있으나(상수 입력에서 주기 2.0틱으로
> 오히려 더 심하다) 골든 통과 56조합 중 **12조합이 실제로 통과**하므로 변별력이 있고,
> 게이트로서 정당하다(§3③).

**(c) server 스테일 창은 동결한다 — 근거는 (b)의 철회와 무관하게 성립한다.**

1. **[주근거] 겨눌 관측이 없다.** 스테일 창은 "값이 얼마나 오래 유효한가"의 정책이고,
   이를 보정하려면 **일중 갱신 주기라는 관측 대상**이 있어야 한다. 픽스처는 전 창이 일봉이라
   09:00~15:30 사이에 새 관측이 하나도 없다(BACKTEST_PLAN §5.2). 90분 창을 겨눌 관측 자체가
   없다. **이 근거 하나로 동결이 성립한다.**
2. **[보강] 골든이 안전망이 되지 못한다.** 진단 실측(`intraday_30m` 90m → 26h): w2018
   ORANGE→RED, w2020 전이 13→31, w2022 10→24 — 스윕 대상 7창만 봐도 거동이 크게 흔들리는데
   **골든은 통과한다**(server 골든이 의미 판정이라 이 변화를 잡지 못한다). 즉 이 차원에서는
   잘못된 값이 골든에 걸리지 않고 통과할 수 있다.

   > **진단 관측(선정 미반영, AD-6).** 같은 실측에서 홀드아웃 `w2023_11_rally`도
   > GREEN(AMBER 0틱) → AMBER 194틱 / 전이 12로 붕괴했다. **이 관측은 R-03에 따라 선정·
   > 랭킹·그리드 값 결정에 일절 반영하지 않았고**, 위 동결 판단은 홀드아웃을 빼고 근거
   > 1·2만으로 이미 성립한다. 리스크의 크기를 서술하기 위해서만 인용한다.

주: MT0-04 F-4의 구조 격리(KR 3지표가 90분 스테일로 `kr_close` 1틱만 생존 → `sustain=2`
불가)는 실재한다. 다만 **그것이 server 플래핑의 원인이라는 라운드 2의 귀속은 철회**됐다
((b) 참조). 위 1·2는 플래핑 귀속과 독립적으로 성립한다.

MT0-04 인계 항목 "90분 스테일↔sustain=2 구조 격리 보정 검토(BT-03 스윕 ④)"에 대한
BT-03의 답: **"겨눌 일중 관측이 없어 본 하니스에서 보정 불가 — C1 실측 30분 데이터에서 재판정."**

부기: 이 격리를 해소하는 다른 경로인 server `promote_sustain_ticks` 하향(2→1)은
BACKTEST_PLAN §BT-03 ③이 `mobile_daily` 한정이므로 스윕 대상 밖이다. 제안으로만 기록한다.

---

## 10. 리스크

**R-1 (최대) — O-4 과민화가 BT-04 홀드아웃에서 반려될 위험.**
*(진단 관측(선정 미반영, AD-6) — 아래 홀드아웃 수치는 리스크 서술 용도이며 선정·랭킹·
그리드 값 결정에 반영하지 않았다.)*
홀드아웃 `w2023_11_rally`는 **현행 0.2.0에서 이미** mobile AMBER 18/22틱으로 §6 오탐 기준
(≤ 3틱)을 초과한 상태다. 스윕은 이 창을 선정 입력으로 쓰지 않는다(R-03). 그런데 rank 1순위(오탐)가
**완전 축퇴**(§6.1)이므로 실질 선정이 rank 2(리드타임 최대)로 환원되어 구조적으로
**민감화 방향으로 끌린다**. 완충 장치와 그 실효성:

| 완충 | 실효성 |
|---|---|
| 음성 창 AMBER 계량 포함 | **거의 없음** — in-sample 음성 창 composite 0.00, 전 후보 AMBER 0 |
| **mobile 플래핑 게이트(≤6)** | **실효 있음** — 골든 통과 56조합 중 **12조합만** 통과(§3③). 민감화 후보는 양성 전이수가 먼저 6을 넘어 탈락한다 |
| server 스테일 동결(§9.2c) | **실효 있음** — 실측상 가장 강력한 민감화 요인을 그리드에서 제거 |
| detection 5/5 게이트 | 탐지 상실 후보를 즉시 탈락(F-2 역전 봉쇄) |
| 3순위 "변경 파라미터 수 최소" | 동률 시 현행에 가까운 값 선택 |

잔여 위험은 남는다. **BT-04에서 홀드아웃이 반려되면 그것은 스윕의 실패가 아니라 설계가
예측한 결과**이며 대응은 0.3.0-rc 철회 또는 해상도(F-06) 대응안 착수다.

**R-2 — `or_any_crit ⊕ exit_AMBER` 충돌이 프로덕션 결함일 수 있다(§13).** §9.2(b)의
한계진동은 데이터와 무관한 설정 성질이므로 **실서비스에서도 그대로 재현된다.** AMBER 알림
(`telegram_notify`·`tag_daily_digest`)이 crit 지속 중 주기적으로 재발화할 수 있다는 뜻이다.
BT-03은 전이 규칙을 고칠 권한이 없으므로 GM0 안건으로만 올린다. 안건이 반려되면 0.3.0-rc의
server 측 플래핑 수치는 계속 FAIL로 남는다.

**R-2b — server 플래핑 범위 축소가 뒤집힐 위험.** 축소 근거는 "스윕 무감(20 불변)"과
"원인이 스윕 대상 밖"이다. §13 안건이 채택돼 전이 규칙이 수정되면 이 항목은 다시 게이트로
복귀해야 하며, 그 시점의 0.3.0-rc는 재평가 대상이다.

**R-3 — in-sample 과적합(K-11).** 7창 × 154회 평가. 특히 ③은 112조합을 6개 양성 창의
전이수·리드타임에 맞추는 것이라 노이즈 적합 위험이 있다. 완충: 골든 하드 제약,
3순위 "최소 변경", BT-04 홀드아웃.

**R-4 — 확정 틱 시각이 수치가 아니라 논증으로 정해진다(AD-3).** 하니스가 09:00조차
구분하지 못하므로 §6은 답을 주지 못한다. 17:00은 "KRX 마감 15:30 이후 + `daily_kr` 수집
크론 16:50 이후"라는 스케줄 정합 논증에 근거하며 논증이 기각되면 근거가 사라진다.
`replay.yaml` 변경은 **M1 모바일 실제 확정 틱 설계와 동시 재확인**을 조건으로 한다(AD-3 b).

**R-5 (해소) — `intraday_30m` 키 신설.** AD-2로 **불허 확정**. 실측상 거동 영향 0이므로
비용만 있고 이득이 없다. 그리드에서 제거했다.

**R-6 — 골든이 ④·확정 틱 시각에 대해 안전망이 아니다.** §8 표대로 두 차원은 골든에
전혀 걸리지 않는다. 이 차원들의 오선정은 골든이 아니라 BT-04 홀드아웃에서야 드러난다.

---

## 11. 다음 단계(Stage B: 구현·실행)

### 11.1 착수 전 남은 확인

1. **확정 틱 17:00의 `replay.yaml` 반영**(AD-3 b) — "M1 모바일 실제 확정 틱 설계와 동시
   재확인" 부기를 함께 기록하는 조건.
2. **w2026 갭의 GM0 안건화**(§4.4) — 스윕 대상 밖 개입 제안의 처리 경로.
3. **server 플래핑의 게이트 범위 축소**(§9.2)를 BT_REPORT 판정표 양식에 반영 —
   FAIL 표기 + 원인 귀속 병기.
4. **GM0 안건 2건 상신**(§13) — w2026 해상도 갭, `or_any_crit ⊕ exit_AMBER` 충돌.

### 11.2 구현 요건 (실측으로 확인된 하니스 갭)

BT-03 스윕은 현행 하니스로 **그대로 실행할 수 없다**.

1. **`run_replay.run_replay()`에 `statemachine_path`·`replay_path` 파라미터 추가.**
   현재 두 경로가 모듈 상수라 **차원 ③을 CLI로 흔들 방법이 없다**. MT0-04 F-1이
   `--config`를 `stale_profiles`까지 전면 배선한 것과 같은 이유다.
2. **`engine_ref.registry.load_statemachine()`에 `path` 인자 추가.** 동일 사유.
   F-2의 교훈대로 명시 path는 캐시하지 않는다.
3. **`run_sweep.py`는 그리드 리터럴을 갖지 않는다.** 전부 `sweep.yaml`에서 로드하고
   `windows.yaml`의 `holdout` 플래그로 창을 필터링한다(창 id 하드코딩 금지).
4. **`backtest/results/metrics.json`(기준선)을 덮어쓰지 않는다.** 스윕 산출은 별도 경로
   (예: `backtest/results/sweep/`)에 쓴다.
5. **F-04를 실행하려면**(현 설계에서는 실행하지 않음) `_BUILDERS` 등록이 선행돼야 한다.

### 11.3 완료 기준 (제안)

- `uv run ruff check . && uv run pytest -q` — 145 green 유지(신규 테스트 추가분 포함).
- `uv run pytest -q backtest/test_golden.py` — 선정된 0.3.0-rc를 `configs/`에 반영한
  **후에도** green. 하드 제약의 최종 증인이다.
- `uv run python backtest/run_sweep.py` — 산출 json에 평가 창 목록이 기록되고 홀드아웃 2창이
  **없음**을 증명.
- **골든 사전 필터 작동 증인 테스트**: 골든 위반이 확정된 조합(`promote_sustain_ticks = 2`,
  `usdkrw watch = 2.25`)이 후보 목록에서 탈락함을 단언. MT0-03 신설 규율 ①의 적용.
- **선정 규칙 결정론 증인**: 동일 입력 2회 실행이 동일 승자를 내고, rank 1순위가 축퇴한
  상태에서 rank 2·3이 실제로 호출되는 경로에 테스트가 있을 것(§6.1).
- `BT_REPORT.md`: 머리에 "근사-PIT — C1에서 실측 확정", §6 판정표에 **미충족 항목 FAIL
  그대로** + 범위 밖 표기와 **원인 귀속 병기**, 스윕 표에 **rank 1 축퇴 사실 명시**,
  server 플래핑의 게이트 범위 축소 근거(스윕 무감 + 설정 모순), GM0 안건 2건(§13) 요약.
- 그래프는 `backtest/reports/`에 저장하고 경로를 보고한다.
- 스윕 실행 로그의 실소요 시간을 §5.1 예산 추정과 대조한다.

---

## 12. 재현 방법 (검증자용)

### 12.1 명령 하나로 전 표 재생성

아래를 저장소 루트에 `bt03_repro.py`로 저장하고 `uv run python bt03_repro.py`.
`sweep.yaml`의 그리드를 그대로 읽으므로 **스크립트에 그리드 리터럴이 없다.** 저장소는
변경하지 않는다(후보 config는 임시 디렉터리에 쓴다).
출력: **T1** = §8 표 ①행 + §9.2(a), **T2** = §8 표 ③행, **T3** = §8 표 ④행 + §3④,
**T4** = §9.2(b) 아티팩트 표.

```python
"""MT0-05 / BT-03 설계 근거 재현 스크립트 (검증자용, 저장소 무변경)."""
from __future__ import annotations
import copy, sys, tempfile
from itertools import pairwise, product
from pathlib import Path
import yaml

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = Path(__file__).resolve().parent          # 저장소 루트에 두고 실행
sys.path.insert(0, str(ROOT))
from backtest import run_replay as R
from backtest.fixture_schema import load_windows
from engine_ref import registry

TMP = Path(tempfile.mkdtemp(prefix="bt03_repro_"))
SW = yaml.safe_load((ROOT / "backtest" / "sweep.yaml").read_text("utf-8"))
BASE_IND = yaml.safe_load((ROOT / "configs" / "indicators.yaml").read_text("utf-8"))
BASE_SM = yaml.safe_load((ROOT / "configs" / "statemachine.yaml").read_text("utf-8"))
BASE_RP = yaml.safe_load((ROOT / "backtest" / "replay.yaml").read_text("utf-8"))
GS = yaml.safe_load((ROOT / "backtest" / "golden_server.yaml").read_text("utf-8"))
GM = yaml.safe_load((ROOT / "backtest" / "golden_mobile.yaml").read_text("utf-8"))
ORDER = ["GREEN", "AMBER", "ORANGE", "RED"]
WINS = {w.window_id: w for w in load_windows()}
SWEEP = [w.window_id for w in load_windows() if not w.holdout]
POS = [w for w in SWEEP if WINS[w].kind == "positive"]
GOLDEN = SW["golden_constraint"]["windows"]
D = SW["dimensions"]

def _sm(d):
    return registry.StatemachineConfig(
        phases=list(d["phases"]), initial_phase=d["initial_phase"],
        upgrade=d["upgrade"]["rules"], downgrade=d["downgrade"]["rules"],
        skip_levels=bool(d["skip_levels"]),
        profiles={n: registry.ProfileParams(
            promote_sustain_ticks=int(p["promote_sustain_ticks"]),
            demote_below_ticks=int(p["demote_below_ticks"]),
            min_dwell_ticks=int(p["min_dwell_ticks"]),
            reentry_cooldown_ticks=int(p.get("reentry_cooldown_ticks", 0)),
        ) for n, p in d["profiles"].items()})

def run(ind, sm, rp, wids, profs):
    """차원 ③은 run_replay의 두 경로가 모듈 상수라 in-process 임시 대체가 필요하다
    (§11.2-1·2의 구현 갭 — 해소되면 --config처럼 CLI로 재현 가능해진다)."""
    ip, sp, rpp = TMP / "i.yaml", TMP / "s.yaml", TMP / "r.yaml"
    for o, p in ((ind, ip), (sm, sp), (rp, rpp)):
        p.write_text(yaml.safe_dump(o, allow_unicode=True, sort_keys=False), "utf-8")
    o1, o2, o3 = R.STATEMACHINE_YAML_PATH, R.REPLAY_YAML_PATH, registry.load_statemachine
    R.STATEMACHINE_YAML_PATH, R.REPLAY_YAML_PATH = sp, rpp
    registry.load_statemachine = lambda: _sm(yaml.safe_load(sp.read_text("utf-8")))
    try:
        return R.run_replay(profs, wids, indicators_path=ip)
    finally:
        R.STATEMACHINE_YAML_PATH, R.REPLAY_YAML_PATH = o1, o2
        registry.load_statemachine = o3

def golden(ind, sm, rp):
    """backtest/test_golden.py의 4개 단언과 동일 의미. -> (pass, 최초 위반 사유)"""
    res = run(ind, sm, rp, GOLDEN, ["server_intraday", "mobile_daily"])
    sched = R.load_schedule_times(yaml.safe_load((TMP / "s.yaml").read_text("utf-8")))
    sp = GS["positive"]; lbl = sched[sp["check_tick"]][0].strftime("%H:%M")
    hit = next((t for t in res["windows"][sp["window_id"]]["server_intraday"]["ticks"]
                if t["date"] == sp["check_date"] and t["kst_time"] == lbl), None)
    if hit is None: return False, "server: check tick missing"
    if ORDER.index(hit["phase"]) < ORDER.index(sp["min_phase"]):
        return False, f"server pos phase {hit['phase']}"
    if not set(sp["required_fired_axes"]) <= set(hit["fired_axes"]):
        return False, f"server pos axes {hit['fired_axes']}"
    sn = GS["negative"]
    for t in res["windows"][sn["window_id"]]["server_intraday"]["ticks"]:
        if ORDER.index(t["phase"]) > ORDER.index(sn["max_phase"]):
            return False, f"server neg {t['date']} {t['phase']}"
    for wid in GOLDEN:
        act, exp = res["windows"][wid]["mobile_daily"]["ticks"], GM["windows"][wid]["ticks"]
        if len(act) != len(exp): return False, f"mobile {wid} tick count"
        for a, e in zip(act, exp, strict=True):
            if a["phase"] != e["phase"]:
                return False, f"mobile {wid} {a['date']} phase {a['phase']}!={e['phase']}"
            if abs(a["composite"] - e["composite"]) > 1e-9 * max(1.0, abs(e["composite"])):
                return False, (f"mobile {wid} {a['date']} composite "
                               f"{a['composite']:.4f}!={e['composite']:.4f}")
            if abs(a["coverage"] - e["coverage"]) > 1e-9:
                return False, f"mobile {wid} {a['date']} coverage"
            if a["fired_axes"] != e["fired_axes"]: return False, f"mobile {wid} {a['date']} axes"
    return True, "-"

def dim1_candidates():
    g = D["usdkrw_thresholds"]["grid"]
    return [(a, b, c) for a, b, c in product(g["watch"], g["warn"], g["crit"]) if a < b < c]

def dim3_candidates():
    """sweep.yaml dedupe.collapse_inert_min_dwell (D-25 O3-1) 적용."""
    g = D["mobile_daily_profile"]["grid"]; seen, out = set(), []
    for p, de, dw, co, cf in product(g["promote_sustain_ticks"], g["demote_below_ticks"],
                                     g["min_dwell_ticks"], g["reentry_cooldown_ticks"],
                                     g["confirm_time_kst"]):
        key = (p, de, de if dw <= de else dw, co, cf)
        if key in seen: continue
        seen.add(key); out.append((p, de, dw, co, cf))
    return out

def ind_with_usdkrw(t):
    ind = copy.deepcopy(BASE_IND)
    for s in ind["indicators"]:
        if s["id"] == "usdkrw_z":
            s["thresholds"] = {"watch": t[0], "warn": t[1], "crit": t[2]}
    return ind

def flap(res, prof):
    return max(res["windows"][w][prof]["summary"]["n_transitions"] for w in POS)

def flap_info_backed(res, prof):
    """**한계진동 노출도 관측치**(라벨 정정, AD-5): composite가 실제로 바뀐 틱 경계에서
    일어난 전이만 센 양성창 최대값. 라운드 2는 이를 "정보 뒷받침 전이"라 부르며 아티팩트
    근거로 썼으나, [T5]가 보이듯 Δcomposite=0 전이는 정보 중복의 증거가 아니라 설정
    모순의 증상이다. 근거가 아니라 관측 기록으로만 존치한다."""
    best = 0
    for w in POS:
        n = 0
        for a, b in pairwise(res["windows"][w][prof]["ticks"]):
            if a["phase"] == b["phase"]: continue
            same = (a["composite"] is not None and b["composite"] is not None
                    and abs(a["composite"] - b["composite"]) < 1e-12)
            if not same: n += 1
        best = max(best, n)
    return best

print("[T1] 차원 ① — 그리드 전수 + 골든 사전필터 + server 플래핑")
c1 = dim1_candidates()
print(f"조합 수 = {len(c1)} (선언 {D['usdkrw_thresholds']['combinations']})")
np_, nf, reasons, srv = 0, 0, {}, []
for t in c1:
    ind = ind_with_usdkrw(t); ok, why = golden(ind, BASE_SM, BASE_RP)
    if ok: np_ += 1
    else:
        nf += 1; k = why.split(" composite")[0]; reasons[k] = reasons.get(k, 0) + 1
    r = run(ind, BASE_SM, BASE_RP, SWEEP, ["server_intraday"])
    srv.append((flap(r, "server_intraday"), flap_info_backed(r, "server_intraday")))
print(f"골든 통과 {np_} / 탈락 {nf}   탈락 사유: {reasons}")
print(f"server 양성창 최대 전이수: 최솟값 {min(s[0] for s in srv)}, 최댓값 {max(s[0] for s in srv)}"
      f" (§6 <=6 -> 통과 0건. 최솟값=최댓값 => 스윕 무감, 후보 변별 불가)")
print(f"  (참고) 한계진동 노출도 관측치: 최솟값 {min(s[1] for s in srv)}, "
      f"최댓값 {max(s[1] for s in srv)} — 근거 아님, [T5] 참조")

print("\n[T2] 차원 ③ — dedupe 후 전수 + 골든 사전필터")
c3 = dim3_candidates()
print(f"dedupe 후 {len(c3)} (선언 {D['mobile_daily_profile']['combinations']})")
byp = {}
for p, de, dw, co, cf in c3:
    sm = copy.deepcopy(BASE_SM)
    sm["profiles"]["mobile_daily"] = {"tick": "1d", "promote_sustain_ticks": p,
        "demote_below_ticks": de, "min_dwell_ticks": dw, "reentry_cooldown_ticks": co}
    rp = copy.deepcopy(BASE_RP); rp["profiles"]["mobile_daily"]["confirm_time_kst"] = cf
    ok, why = golden(BASE_IND, sm, rp)
    e = byp.setdefault(p, [0, 0, set()]); e[0 if ok else 1] += 1
    if not ok: e[2].add(why)
for p, (ok, ng, ws) in sorted(byp.items()):
    print(f"  promote_sustain_ticks={p}: 통과 {ok} / 탈락 {ng}  사유={sorted(ws) if ws else '-'}")

print("\n[T3] 차원 ④ mobile daily_us — 그리드 + 골든 + 7창 응답")
for v in D["stale_windows"]["mobile_daily"]["grid"]["daily_us"]:
    ind = copy.deepcopy(BASE_IND)
    ind["engine"]["stale_profiles"]["mobile_daily"]["daily_us"] = v
    ok, _ = golden(ind, BASE_SM, BASE_RP)
    r = run(ind, BASE_SM, BASE_RP, SWEEP, ["mobile_daily"])
    br = " ".join(f"{r['windows'][w]['mobile_daily']['summary']['max_phase'][:1]}"
                  f"{r['windows'][w]['mobile_daily']['summary']['n_transitions']}" for w in SWEEP)
    print(f"  {v:>5} 골든={'PASS' if ok else 'FAIL'}  {br}")

print("\n[T4] (관측 기록) Δcomposite=0 전이 비율 — 원인 귀속 근거 아님, [T5]가 대체")
for prof in ("server_intraday", "mobile_daily"):
    res = run(BASE_IND, BASE_SM, BASE_RP, SWEEP, [prof])
    tot = z = pc = pd_ = 0
    for w in SWEEP:
        ticks = res["windows"][w][prof]["ticks"]
        for a, b in pairwise(ticks):
            if a["phase"] == b["phase"]: continue
            tot += 1
            if (a["composite"] is not None and b["composite"] is not None
                    and abs(a["composite"] - b["composite"]) < 1e-12): z += 1
        if prof == "server_intraday":
            byday = {}
            for t in ticks: byday.setdefault(t["date"], []).append(t)
            for v in byday.values():
                pl = [x for x in v if "09:00" <= x["kst_time"] <= "15:30"]
                if pl:
                    pd_ += 1
                    if len({round(x["composite"], 12) for x in pl}) == 1: pc += 1
    print(f"  {prof:16} 전이 {tot:3}건 중 composite 무변화 {z:3}건 ({z/tot*100 if tot else 0:.1f}%)")
    if prof == "server_intraday":
        print(f"  {'':16} kr_intraday 평탄구간(09:00~15:30, 14틱) 상수인 날 = {pc}/{pd_}")

# ---------------------------------------------------------------------------
# [T5] AD-5: server 플래핑의 진짜 원인 — 상태기계 설정 모순 (아티팩트 가설 반증)
# ---------------------------------------------------------------------------
from engine_ref import statemachine                                    # noqa: E402
SM = registry.load_statemachine()
up, ex = SM.upgrade["AMBER"], SM.downgrade["exit_AMBER"]
C = 12.8205
print("\n[T5] server 플래핑 원인 — 상태기계 설정 모순 (상수 입력 진동)")
print(f"  upgrade.AMBER={up}  downgrade.exit_AMBER={ex}")
print(f"  composite={C}, any_crit=True -> 진입 충족 "
      f"{C >= up['composite_gte'] or bool(up.get('or_any_crit'))}"
      f" / 이탈 충족 {C < ex['composite_lt']}  => 동시 영구 충족")
const_ticks = [statemachine.Tick(composite=C, distinct_axes=1, any_crit=True)] * 100
for prof in ("server_intraday", "mobile_daily"):
    tl = statemachine.run(const_ticks, SM.profiles[prof], SM)
    tr = sum(1 for a, b in pairwise(tl) if a != b)
    print(f"  {prof:16} 상수 100틱 -> 전이 {tr}건 (주기 {100/tr:.1f}틱) "
          f"{''.join(p[0] for p in tl[:24])}")

def interpolate(s):
    """연속 동일 composite 구간을 앞뒤 서로 다른 값 사이 균등 보간으로 대체.
    틱 수는 유지하고 '정보만 부활'시키는 반사실.

    **경고 — 이 출력은 근거가 아니다(MR3-1).** 결과는 knot 규약에 심하게 의존한다:
    이 구현은 관측값 **대체** 규약(구간 앞뒤 값 사이 균등 보간)이라 w2026 20->9지만,
    관측값 **보존** 규약(관측 틱을 knot으로 고정)에서는 20->23으로 **방향까지 반전**된다.
    공통점은 "어느 규약에서도 §6(<=6) 미달성"뿐이다. 재귀속의 하중은 위의 상수 입력
    진동(반증 1)과 설정 모순(반증 2)이 진다 — 저널 §9.2(b) 참조."""
    out = list(s); i = 0; n = len(out)
    while i < n:
        j = i
        while (j + 1 < n and out[j+1] is not None and out[i] is not None
               and abs(out[j+1] - out[i]) < 1e-12): j += 1
        if j > i and i > 0 and j + 1 < n and out[i] is not None and out[j+1] is not None:
            lo, hi = out[i-1], out[j+1]
            if lo is not None and hi is not None:
                span = j - i + 2
                for k in range(i, j+1): out[k] = lo + (hi-lo)*(k-(i-1))/span
        i = j + 1
    return out

print("  (보조 관찰, 근거 아님 — knot 규약 의존) 선형보간 반사실, 관측값 대체 규약:")
_r5 = run(BASE_IND, BASE_SM, BASE_RP, SWEEP, ["server_intraday"])
for w in SWEEP:
    tt = _r5["windows"][w]["server_intraday"]["ticks"]
    raw = sum(1 for a, b in pairwise(tt) if a["phase"] != b["phase"])
    tl = statemachine.run(
        [statemachine.Tick(c, t["distinct_axes"], t["any_crit"])
         for c, t in zip(interpolate([t["composite"] for t in tt]), tt, strict=True)],
        SM.profiles["server_intraday"], SM)
    itr = sum(1 for a, b in pairwise(tl) if a != b)
    lim = sum(1 for a, b in pairwise(tt) if a["phase"] != b["phase"]
              and abs((a["composite"] or 0)-(b["composite"] or 0)) < 1e-12
              and a["any_crit"] and b["any_crit"]
              and (a["composite"] or 0) < up["composite_gte"])
    print(f"    {w:22} 원본 {raw:3} -> 보간 후 {itr:3}  (한계진동형 {lim:3}건)"
          + ("  << 보간 후에도 §6(<=6) FAIL" if itr > 6 else "")
          + "   [규약 의존 — 인용 금지]")

# ---------------------------------------------------------------------------
# [T6] MR2-1: mobile §6 플래핑 게이트를 실제로 통과하는 조합
# ---------------------------------------------------------------------------
print("\n[T6] mobile §6 플래핑 게이트 통과 조합 (골든 통과 56조합 중)")
_ok = []
for p, de, dw, co, cf in dim3_candidates():
    if p != 1: continue                       # promote_sustain=2는 골든에서 이미 탈락
    sm = copy.deepcopy(BASE_SM)
    sm["profiles"]["mobile_daily"] = {"tick": "1d", "promote_sustain_ticks": p,
        "demote_below_ticks": de, "min_dwell_ticks": dw, "reentry_cooldown_ticks": co}
    rp = copy.deepcopy(BASE_RP); rp["profiles"]["mobile_daily"]["confirm_time_kst"] = cf
    r = run(BASE_IND, sm, rp, SWEEP, ["mobile_daily"])
    pm = max(r["windows"][w]["mobile_daily"]["summary"]["n_transitions"] for w in POS)
    nm = max(r["windows"][w]["mobile_daily"]["summary"]["n_transitions"]
             for w in SWEEP if w not in POS)
    if pm <= 6 and nm <= 2: _ok.append((de, dw, co))
_u = sorted(set(_ok))
print(f"  통과 {len(_ok)}/56   (demote,dwell,cooldown) = {_u}")
print(f"  dwell 집합 {sorted({d for _, d, _ in _u})} · "
      f"cooldown 집합 {sorted({c for _, _, c in _u})} · "
      f"min_dwell>demote 조합 {sum(1 for de, dw, _ in _u if dw > de)}/{len(_u)}")

# ---------------------------------------------------------------------------
# [T7] MR2-2: top_k 근거 — 측정 기준별 등가류 수
# ---------------------------------------------------------------------------
print("\n[T7] 등가류 수 — 측정 기준별 (top_k 근거)")
SEL = ("max_phase", "n_transitions", "first_orange_or_above_date")
_full = lambda r: tuple(tuple(sorted(r["windows"][w]["mobile_daily"]["summary"].items()))
                        for w in SWEEP)
_sel = lambda r: tuple(tuple(r["windows"][w]["mobile_daily"]["summary"][k] for k in SEL)
                       for w in SWEEP)
f1, s1 = set(), set()
for t in dim1_candidates():
    ind = ind_with_usdkrw(t)
    if not golden(ind, BASE_SM, BASE_RP)[0]: continue
    r = run(ind, BASE_SM, BASE_RP, SWEEP, ["mobile_daily"]); f1.add(_full(r)); s1.add(_sel(r))
print(f"  ① 골든통과 24조합: 전체 summary {len(f1)}종 / 선정 서명 {len(s1)}종")
f4, s4 = set(), set()
for v in D["stale_windows"]["mobile_daily"]["grid"]["daily_us"]:
    ind = copy.deepcopy(BASE_IND)
    ind["engine"]["stale_profiles"]["mobile_daily"]["daily_us"] = v
    r = run(ind, BASE_SM, BASE_RP, SWEEP, ["mobile_daily"]); f4.add(_full(r)); s4.add(_sel(r))
print(f"  ④ daily_us 3값:    전체 summary {len(f4)}종 / 선정 서명 {len(s4)}종")
```

기대 출력(이 저장소, 라운드 3 시점):

```
[T1] 조합 수 = 35 (선언 35) / 골든 통과 24 / 탈락 11
     탈락 사유: {'mobile w2024_carry_unwind 2024-08-01': 11}
     server 양성창 최대 전이수: 최솟값 20, 최댓값 20   (한계진동 노출도 관측치: 5, 5)
[T2] dedupe 후 112 (선언 112) / promote_sustain_ticks=1: 통과 56 탈락 0
                              / promote_sustain_ticks=2: 통과 0 탈락 56
[T3] 48h·72h·96h 전부 골든 PASS, 96h에서 w2026 전이 6 -> 8
[T4] server 68건 중 49건(72.1%), 평탄구간 상수 331/331 · mobile 33건 중 4건(12.1%)
     (관측 기록일 뿐 — 아티팩트 근거 아님, [T5] 참조)
[T5] 진입 충족 True / 이탈 충족 True => 동시 영구 충족
     server 상수 100틱 -> 15건 (주기 6.7틱) GAAAAAAGGGGGGGGAAAAAAGGG
     mobile 상수 100틱 -> 49건 (주기 2.0틱) AAAGAAAGAAAGAAAGAAAGAAAG
     (보조 관찰, 근거 아님 — knot 규약 의존) 관측값 대체 규약 보간:
              w2011 7->4 / w2018 14->6 / w2020 13->6 / w2022 10->8(FAIL)
              w2024 4->4 / w2026 20->9(FAIL, 한계진동형 12) / calm 0->0
              ※ 관측값 보존 규약에서는 w2026 20->23(증가). 규약 의존이라 인용 금지 —
                공통 사실은 "어느 규약에서도 §6(<=6) 미달성"뿐이다(MR3-1).
[T6] 통과 12/56  (demote,dwell,cooldown) = [(2,5,3) (3,5,2) (3,5,3) (4,2,3) (4,5,2) (4,5,3)]
     dwell {2,5} · cooldown {2,3} · min_dwell>demote 조합 5/6
[T7] ① 24조합: 전체 summary 4종 / 선정 서명 2종
     ④ daily_us 3값: 전체 summary 3종 / 선정 서명 2종
```

### 12.2 개별 재현

**①·④ 관련 수치** — 현행 CLI로 재현 가능하다(`--config`는 MT0-04 F-1 해소로
`engine.stale_profiles`까지 전면 배선됨):

```bash
uv run python backtest/run_replay.py --profile both --window w2026_structural --config <후보경로>
```

단 `main()`이 `backtest/results/metrics.json`을 덮어쓰므로, 기준선을 보존하려면
`run_replay.run_replay(...)`를 in-process로 호출해 반환 dict를 쓸 것.

**③ 관련 수치** — 현행 CLI로는 재현 **불가**(§11.2-1·2). 위 스크립트의 `run()`이 쓰는
in-process 임시 대체가 필요하며, 그 필요성 자체가 구현 갭의 증거다.

**F-04 결손 확인**:

```bash
uv run python -c "
import json, pathlib
for p in sorted(pathlib.Path('backtest/fixtures').glob('*.meta.json')):
    m = json.loads(p.read_text(encoding='utf-8'))
    print(p.stem, {k: v['status'] for k, v in m['series'].items() if v.get('status') != 'ok'})
"
```

### 12.3 기준선 스탬프 재생성 (F-12)

`backtest/results/metrics.json`의 `registry_version`이 `0.1.0`으로 남아 현행 레지스트리
(0.2.0)와 어긋나 있었다. 재생성으로 스탬프만 갱신했고 **산출 수치 불변을 증명**했다:

| 필드 | before | after |
|---|---|---|
| `registry_version` | `0.1.0` | `0.2.0` |
| `generated_at` | `2026-08-02T14:43:26.020287+00:00` | `2026-08-02T16:23:16.958990+00:00` |
| `schema` · `note` · `profiles_run` | 불변 | 불변 |
| **`windows` 페이로드(18행 전 틱)** | **sha256 `50e1b15934f994e9ff576bce291b2c05…`** | **sha256 `50e1b15934f994e9ff576bce291b2c05…`** |

```bash
uv run python backtest/run_replay.py --profile both --window all   # 재생성
# 검증: sort_keys 정규화 후 windows 페이로드 sha256 비교 -> 동일
```

---

## 13. GM0 상신 안건

BT-03의 권한 밖이지만 GM0 판단이 필요한 항목이다. **어느 것도 이 단계에서 구현하지 않았고
`configs/`를 수정하지 않았다** — 방향 스케치와 근거만 기록한다.

### 안건 1 — w2026 해상도 갭 (§4)

현행 레지스트리는 창내 **-38.63%** 폭락(`w2026_structural`)을 ORANGE로 인지하지 못한다
(최고 composite 35.15 < 40). 원인은 임계값이 아니라 **severity 포화 + z-score 자기정규화**
= 해상도(F-06)이며, 스윕 대상 ①~④ 전 영역에서 도달 불가임이 실측 증명됐다.
정식 대응 경로는 BT-04의 대응안 3종(임계 사다리 확장 / severity 4단계 / RED 서브레벨)
비교 시뮬레이션이다. **BT-03은 제안만 하고 채택은 사용자 승인 사항이다.**

### 안건 2 — `or_any_crit ⊕ exit_AMBER` 충돌 (신설, §9.2b)

**결함 후보의 성격.** `configs/statemachine.yaml`의 두 규칙이 특정 구간에서 **동시에 영구
충족**된다:

```
upgrade.AMBER        = {composite_gte: 20, or_any_crit: true}
downgrade.exit_AMBER = {composite_lt: 14}
```

`composite < 14` **이면서** 어느 지표든 `severity == 3`인 상태 — 즉 **"저(低) composite +
단일 crit"** 구간에서 진입 조건은 `or_any_crit`으로, 이탈 조건은 `composite_lt`로 각각
영구 참이 된다. 국면은 카운터 주기에 맞춰 한계진동한다.

**재현(데이터 무관).** 상수 입력 `composite = 12.8205`, `any_crit = True` 100틱:
server **15전이(주기 6.7틱)**, mobile **49전이(주기 2.0틱)**. 실측 증인은 2026-05-19 server —
하루 종일 값이 고정인데 `AMBER → GREEN(11:30) → AMBER(15:30)`.

**왜 D-03 설계 의도 위반인가.** D-03은 "한 번 켜진 경계는 천천히 꺼지는 보수적 계기판"을
의도한다. 현행 조합은 정반대로 **가장 약한 신호 구간에서 가장 빠르게 깜빡인다.**
프로덕션에서 그대로 재현되므로(데이터 성질이 아니라 설정 성질) AMBER 진입 액션
(`telegram_notify`·`tag_daily_digest`)이 주기적으로 재발화할 수 있다.

**노출 범위.** 두 프로파일 모두. mobile이 오히려 주기가 짧다(2.0틱 = 2영업일).
다만 mobile은 §6 플래핑 게이트를 통과하는 조합이 12/56 실존하므로 게이트 유지가 정당하고
(§3③), server는 스윕 무감(20 불변)이라 게이트에서 범위 축소했다(§9.2b').

**수정 방향 스케치 (구현하지 않음 — GM0 채택 시 별도 서브태스크).**

| 방향 | 요지 | 유의점 |
|---|---|---|
| A. crit 지속 중 이탈 차단 | `any_crit`이 참인 동안 `exit_AMBER`를 미충족으로 취급(히스테리시스를 진입 근거와 짝지음) | 가장 작은 변경. crit가 오래 걸린 채 방치되면 AMBER가 영구 고착될 수 있어 상한 dwell 필요 여부 검토 |
| B. 이탈선을 진입 근거와 대칭화 | `exit_AMBER`에 `and_no_crit` 조건 추가 — 진입이 `or`면 이탈은 `and` | 스키마에 이탈 조건 키 신설 필요(SSOT 형상 변경) |
| C. `or_any_crit` 자체를 좁힘 | 예: crit가 **2개 이상**이거나 crit + 최소 composite 하한 동시 요구 | D-08 골든(단일 crit로 AMBER 진입하는 경로)에 영향 가능 — 골든 무회귀 재확인 필수 |

**어느 방향이든 골든 무회귀(D-08 2케이스 × 2프로파일) 재확인이 선행 조건이다.**
세 방향 모두 AMBER 진입·이탈 경로를 건드리므로 `golden_mobile.yaml` 동결 타임라인이
움직일 수 있다.
