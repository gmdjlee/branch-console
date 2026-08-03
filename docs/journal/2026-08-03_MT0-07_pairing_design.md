# MT0-07 / D-26 이스케이프-이탈 짝지음 — 설계 (Stage A)

> **근사-PIT — C1에서 실측 확정.** 이 문서의 모든 수치는 `backtest/fixtures/*.parquet`
> 소급 수집 근사(BACKTEST_PLAN.md §5)에서 산출됐다. server_intraday는 30분 그리드의
> 일봉 근사 재현이고, 소급 수집치는 개정치를 포함할 수 있어 진짜 PIT이 아니다. 여기서
> 도출하는 판단(스코프 선택·상한 규율 필요 여부·SSOT 형상)은 이 근사 위의 1차 설계
> 가설이며, 최종 확정은 C1 실측 lake에서 재수행한다(D-04 "모든 임계값은 가설").

- 작성일: 2026-08-03 · 소속: M0 / MT0-07 Stage A(설계) · 역할: backtest-analyst Worker
- 권한: GATE_GM0 안건 3(a)·5 사용자 승인(2026-08-03) → D-26(`docs/P0_DESIGN_DECISIONS.md`
  말미) · TASK_mobile_m0.md §MT0-07
- **저장소 변경 범위(Stage A)**: 이 문서 하나뿐이다. `engine_ref/`·`configs/`·
  `backtest/results/metrics.json` 무변경(§8 SHA-256 확인). 탐색 실측은 전부
  in-process 샌드박스(§8 재현 스크립트, scratchpad 실행 — bt03/bt04_repro 패턴을
  저장소 밖에서 수행).

## 0. 전제

D-26은 GM0 안건 5로 **수정 방향 A**("이스케이프 근거가 지속되는 동안 해당 레벨의
이탈 조건을 미충족으로 취급 — 히스테리시스를 진입 근거와 짝지음")를 채택했다. 적용
범위는 `or_*` 이스케이프 전 계열 공통 — 현재는 `upgrade.AMBER.or_any_crit`
(프로덕션) 하나뿐이고, `upgrade.ORANGE.or_any_extreme`(MT0-06/BT-04 ① 변형, 실험,
미프로덕션)가 향후 두 번째 사례다.

D-26의 네 가지 구현 선행 조건(제약 ①~④)이 이 문서의 절 구성이다:
① 골든 무회귀 실측 확정(§4) ② 영구 고착 위험의 상한 규율 필요 여부 판정(§2)
③ 정확한 실행 의미론을 D-25 부기 형식으로 확정(§1, §3) ④ Kotlin 패리티(BT-05) 형상
변경 여부(§3).

이 설계가 서 있는 상위 사실 — **AD-5**(server 플래핑의 진짜 원인은 상태기계 설정
모순: `or_any_crit`⊕`exit_AMBER`가 특정 구간에서 동시 영구 충족돼 한계진동을 낳는다,
MT0-05 §9.2b) — 는 D-26이 바로 그 모순을 고치는 결정이라는 뜻이다. 이 문서의 §5
실측은 그 예측(짝지음이 진동을 줄인다)이 실제로 성립하는지를 검증한다.

### 0.1 Advisor 결정 (AD-12·13, 2026-08-03 — M0 AD 시리즈 AD-1~11 승계)

**AD-12 — 상한(escape hold ceiling) Stage B 미구현·이월 확정.** 근거: (i) GM0
승인 범위는 방향 A 자체이고 상한은 별도 결정 사항 — 본 저널 §2의 권고와 일치
(ii) 실측(§2.1)상 mobile의 장기 AMBER+ 체류 3창(w2015·w2020·w2026)은 전부 실제
위기 창이며, D-03 의도("한 번 켜진 경계는 천천히 꺼진다") 관점에서 crit 지속 중
AMBER+ 유지는 결함이 아니라 **의도된 보수성**이다 (iii) 알림 재발화는 전이
감소(§5)로 오히려 줄어든다. §2 실측은 근거 기록으로 존치하고, 상한 필요성이
실운영에서 관측되면 별도 상신한다(C1 또는 M2 알림 설계 시점 재검토 부기).

**AD-13 — `backtest/results/metrics.json` 재생성 사전 승인 (MT0-05 §14.1 규율
이행).** Stage B가 D-26 짝지음을 프로덕션 `engine_ref`에 반영하면 기준선
`metrics.json`은 실거동 변경으로 재생성이 필요하다 — **사전 승인한다.** 조건:
(i) 재생성본의 `note` 필드(또는 동반 기록)에 출처 명기 — "registry 0.3.0-rc
configs 불변 + D-26 pairing semantics 적용" (ii) 재생성 전후 §6 지표 diff 표를
BT_REPORT 부기에 보고(서버 플래핑 25→13 등, §5 델타표 승계) (iii) 골든 6 green을
재생성 전후 재확인. `registry_version` 스탬프는 configs 불변이므로 **0.3.0-rc
유지** — 단 의미론 변경 사실은 `note`로 구분한다.

## 1. 실행 의미론

### 1.1 레벨-로컬 vs 스태킹 — 9창 실측 비교, 레벨-로컬 채택

두 갈래를 실측했다(재현: §8, 섹션 3):

- **레벨-로컬**: 레벨 L의 `or_*` 이스케이프가 참인 동안 **그 L 자신의** `exit_L`만
  미충족으로 취급한다. 다른 레벨의 이탈 평가에는 아무 영향이 없다(현재 엔진 구조상
  한 틱에 평가되는 이탈 규칙은 현재 국면의 것 하나뿐이므로, 자연스러운 구현이다).
- **스태킹**: 현재 국면 X에서 이탈을 평가할 때, X **이하**의 어느 레벨이든 이스케이프가
  참이면(예: ORANGE에 있는데 AMBER의 `or_any_crit`이 여전히 참) `exit_X`를 미충족으로
  취급한다 — "한 번 지나온 위험 신호가 상위 국면의 이탈까지 계속 막는다"는 더 넓은
  해석이다.

9창 실측(`or_any_crit`, reset 스트릭 정책 고정):

| 창\|프로파일 | level_local (Δ전이, ΔAMBER+틱) | stacking (Δ전이, ΔAMBER+틱) |
|---|---|---|
| w2011\|server | +0, +0 | **-4**, +0 |
| w2011\|mobile | +0, +0 | **-1**, +0 |
| w2015(H)\|server | -18, +72 | -18, +72 (동일) |
| w2015(H)\|mobile | -2, +3 | **-3**, +3 |
| w2018\|server | -2, +8 | -2, +8 (동일) |
| w2018\|mobile | +0, +0 | **-3**, +0 |
| w2020\|server | +0, +0 | **-4**, **+11** |
| w2020\|mobile | -2, +3 | **-3**, +3 |
| w2022\|mobile | +0, +5 | +0, **+10**(2배) |
| w2024_carry\|server | +0, +0 | **-1**, +0 |
| w2026\|server | -12, +51 | -12, +51 (동일) |
| w2026\|mobile | -2, +4 | -2, +4 (동일) |

스태킹은 **level_local과 같거나 항상 더 많이** 전이를 줄이고 dwell을 늘린다(w2020
server: level_local은 무변화인데 stacking은 -4전이/+11dwell, w2022 mobile: dwell
증가가 2배). 즉 스태킹은 level_local의 **strict superset** 개입이다 — 같은 방향의
효과를 더 넓은 범위에 적용한다.

**채택: 레벨-로컬.** 근거:
1. **D-26 원문과 일치.** "해당 레벨의 이탈 조건"이라는 문언은 이스케이프와 그 자신의
   레벨을 1:1로 짝짓는다고 읽는 것이 가장 직접적이다 — 스태킹은 "해당" 범위를
   넘어서는 추가 해석이다.
2. **최소 변경 원칙(MT0-05 §13 표, 방향 A의 정의 자체가 "가장 작은 변경").** 스태킹은
   §2가 다루는 "영구 고착" 위험을 **구조적으로 더 키운다** — 위 표가 보이듯 항상
   같거나 더 큰 dwell 증가를 낳는다.
3. **골든 무회귀는 둘 다 동일하게 유지**(§4) — 이 축에서는 구분되지 않으므로 위
   1·2가 결정한다.

### 1.2 강등 스트릭 카운터 정책 — "미충족 취급"을 문자 그대로 구현(reset), accumulate와 실측 무차이

두 정책을 실측했다(§8 섹션 4):
- **reset**: `_exit_satisfied()`가 반환하는 진리값 자체를 `base_exit_ok AND NOT
  이스케이프_활성`으로 바꾼다. 이스케이프가 참인 틱은 "이탈 조건 미충족"과 완전히
  같은 코드 경로(`else: demote_streak = 0`)를 탄다 — **진입 쪽과 정확히 대칭**이다:
  `or_any_crit`도 `_rule_satisfied()`의 반환값 자체를 바꾸고, 그 값이 그대로
  `promote_streaks`에 먹힌다(D-25 §1). 이탈 쪽도 같은 패턴을 따르는 것이 구조적
  일관성이다.
- **accumulate**: 스트릭은 `base_exit_ok`만으로 누적하고(이스케이프와 무관), 커밋 순간에만
  `AND NOT 이스케이프_활성`을 추가로 요구한다 — `min_dwell_ticks`가 스트릭 커밋을
  지연시키되 스트릭 자체는 계속 쌓이는 D-25 §2 dwell 선례와 같은 모양이다.

9창 실측 결과: **두 정책은 이번 9창 표본에서 행태가 완전히 동일했다** — `n_transitions`
델타가 6개 비영(非零) 창·프로파일 전부에서 `reset == accumulate`(§8 섹션 4 raw 출력).
즉 이 표본에서는 실측으로 우열을 가릴 수 없다 — **판단은 텍스트·구조 정합성**으로
내린다.

**채택: reset.** 근거:
1. D-26 원문 "미충족으로 취급"은 조건의 **진리값 자체**를 바꾸라는 뜻이지, "조건은
   충족됐지만 커밋만 지연하라"는 뜻이 아니다 — 후자는 오히려 방향 B(§13 표의
   "이탈선을 진입 근거와 대칭화")의 다른 구현에 더 가깝다.
2. `or_any_crit`이 진입 스트릭에 작용하는 방식(진리값 자체를 바꿔 그 값이 스트릭에
   그대로 먹힘)과 완전히 대칭이다 — 새 게이트 종류(dwell처럼 별도 레이어)를 추가하지
   않는다. **가장 작은 변경**(D-26·수정 방향 A의 정의)이라는 원칙과 정합한다.
3. 실측 무차이는 accumulate를 채택할 근거가 되지 못한다(우열이 없으면 더 단순한
   쪽을 고른다).

### 1.3 dwell·cooldown과의 상호작용 — 새 메커니즘 불요, 기존 게이트와 직교

- **dwell(`min_dwell_ticks`)**: pairing은 `_exit_satisfied()`의 진리값만 바꾼다.
  dwell 게이트(`ticks_in_phase >= min_dwell_ticks + 1`)는 그 아래에서 완전히 동일하게
  작동한다 — 이스케이프가 막 풀린 뒤에도 dwell 미달이면 여전히 커밋되지 않는다.
  두 게이트는 AND로 합성되며 서로 간섭하지 않는다.
- **cooldown(`reentry_cooldown_ticks`)**: 원본 엔진에서 cooldown은 **승격 스트릭만**
  0으로 정지·리셋한다(D-25 §1) — 이탈 평가 분기(`if not transitioned and phase !=
  order[0]`)는 cooldown 값과 무관하게 매 틱 실행된다. pairing은 이 분기 내부만
  건드리므로 cooldown 중에도 정확히 같은 방식으로 적용된다: 방금 강등되어 cooldown이
  도는 낮은 국면에서 이스케이프가 (재차) 참이면 그 국면의 추가 강등도 동일하게 막힌다.
  **새 특별 케이스가 필요 없다** — 9창 실측(server 다수 창에서 강등·재승격·cooldown이
  반복되는데도 전이수가 예측대로 줄고 크래시 없음)이 이 조합성을 간접 확인한다.

### 1.4 D-25 부기 초안 (Advisor가 P0_DESIGN_DECISIONS.md D-25에 물질화할 문안, 역참조용)

> **부기(2026-08-03, MT0-07 — D-26 실행 의미론).** D-26의 "이탈 조건을 해당 레벨의
> 이스케이프와 짝짓는다"는 다음과 같이 구현한다. 레벨 L의 upgrade 규칙이 `or_any_*`
> 키를 가지면, 그 키에 대응하는 Tick 필드(예: `or_any_crit`→`any_crit`)가 참인 동안
> `exit_L`은 **그 자신의 조건과 무관하게 미충족**으로 평가된다(레벨-로컬 — 다른
> 레벨의 이탈에는 영향 없음). 이 미충족은 강등 스트릭이 "이탈 조건이 그냥 거짓인
> 틱"과 **동일한 코드 경로**(스트릭 리셋)를 타도록 구현한다 — D-25 §1의 승격 스트릭이
> `or_any_crit`로 수정된 진리값을 그대로 누적하는 것과 대칭이다. cooldown·dwell은
> 무수정 — 두 게이트는 이 수정된 이탈 진리값 위에서 기존과 동일하게 합성된다.
> **`upgrade.RED`에는 `or_any_*` 키가 없어(O-4) `exit_RED`는 어떤 경우에도 차단되지
> 않는다** — pairing은 upgrade 규칙에 실제로 이스케이프 키가 선언된 레벨에만 적용되고,
> RED는 현재도 향후에도 그런 선언이 없는 한 이 결정의 영향을 받지 않는다.

## 2. 영구 고착 상한 규율 — AD-12: Stage B 미구현·이월 확정

### 2.1 실측 근거

`any_crit` 연속 참 런의 최댓값(9창×2프로파일, `or_any_crit` 이스케이프 대상):

| 순위 | 창\|프로파일 | 최장 연속 런(틱) |
|---|---|---|
| 1 | w2022_tightening\|server | 672 |
| 2 | w2020_covid\|server | 545 |
| 3 | w2011_us_downgrade\|server | 433 |
| 4-5 | w2018/w2015(H)\|server | 241 |
| ... | (나머지 13쌍은 §8 재현 스크립트 실행 시 "5. any_crit run-length distribution" 섹션이 18쌍 전체를 순위와 함께 출력한다 — O-5) | |

원값 지속 자체는 방대하다(server는 30분 그리드라 수백 틱이 몇 주에 해당). 그러나
이것이 **곧바로** dwell 증가로 번지지는 않는다 — 실제로 영향이 있으려면 "이스케이프
활성" **그리고** "그 레벨에 현재 체류 중" **그리고** "composite가 그 레벨의 이탈
임계 아래"가 동시에 성립해야 한다. 실측된 실제 영향(레벨-로컬, reset, `or_any_crit`
프로덕션 조건):

**server_intraday**: 최장 단일 AMBER+ 연속 체류(`max_amber_plus_run`)는 **9창
전부에서 baseline과 동일했다**(변화 0) — 누적 dwell(`amber_plus_ticks`)은 늘지만
(w2015 +72, w2026 +51, w2018 +8), 그 증가분이 **여러 개의 짧은 삽화로 분산**된다.
단일 정체 위험은 이 프로파일에서 관측되지 않는다.

**mobile_daily**: 정반대다 — 최장 단일 AMBER+ 연속 체류가 **창 전체를 잠식**한다:

| 창(비고) | baseline 최장 연속 | pairing 후 최장 연속 | 창 총 틱수 | 비중 |
|---|---|---|---|---|
| w2015_cny_deval **(홀드아웃)** | 23 | **44** | 44 | **100%** |
| w2020_covid | 41 | **56** | 63 | 88.9% |
| w2026_structural | 15 | **28** | 53 | 52.8% |

일봉 그리드에서는 crit 지표(예: 신용스프레드·환율 z)가 여러 날 연속 참인 경우가
흔해, 짝지음이 걸리면 **한 번 진입한 국면이 그 창의 나머지 전 구간 동안 한 번도
GREEN으로 돌아오지 못하는** 사례가 3/9창에서 나왔다(그중 1개는 홀드아웃, 2개는 정규
스윕 창 — 즉 홀드아웃 특수 사례가 아니다).

### 2.2 판정

**§6 수치 게이트만 보면 문제가 드러나지 않는다.** 플래핑 게이트(`n_transitions`)는
오히려 **개선**된다(§5) — 진동이 줄었으니 당연하다. 오탐 게이트(`w2023_11_rally`
AMBER 18틱)도 불변이다(§5). 즉 **현재 §6 지표 체계는 "한 번 켜지면 안 꺼짐"이라는
질적 위험을 계량하지 않는다** — 이것이 D-26 제약 ②가 별도 판정을 요구하는 이유다.

D-03의 설계 의도("한 번 켜진 경계는 천천히 꺼지는 보수적 계기판")는 **composite가
결국 하락하면 이탈이 재개된다**는 것을 전제한다. 그런데 pairing 하에서는 단일
지표가 severity 3에 고정된 채(예: 22거래일 연속 severity 3 고정 사례가 이미
MT0-06 §2.4에 별도 지표로 문서화돼 있다 — 같은 계열의 데이터 성질) 몇 주간 머물면
"천천히 꺼진다"가 아니라 **"꺼질 길이 없다"**로 변질된다. 이것은 D-03의 의도를
초과하는 결과이고, 오경보 피로(alarm fatigue) 측면에서 실질적 비용이다 —
실측(mobile 창 100%·88.9%·52.8% 잠식)은 이 우려가 이론적 가능성이 아니라 이
9창짜리 근사-PIT 표본에서도 이미 발현한다는 것을 보인다.

**판정(Advisor 확정 — AD-12, §0.1)**: 상한 규율은 **Stage B에서 구현하지 않고
이월**한다. 이 저널의 실측만으로는 "안전판이 필요하다"는 판단도 성립할 수 있었으나,
Advisor는 같은 실측을 다르게 귀속했다 — mobile 장기 AMBER+ 체류 3창은 전부 실제
위기 창(홀드아웃 1개 포함 나머지는 정규 양성 스윕 창)이고, crit이 계속 참인 동안
국면이 내려가지 않는 것은 D-03이 의도한 보수성 그 자체이지 결함이 아니다. 알림
피로 우려도 §5의 전이 감소(플래핑 개선)로 상쇄된다 — 알림은 줄기는 해도 늘지
않는다. **최종 판정은 이중이다**: (a) 방향 A 자체(안전판 없이)는 §4 골든·§5 §6
게이트 모두 통과하므로 그 자체로 채택 가능 (b) 상한 규율은 만들지 않는다 — §2.1
실측은 근거 기록으로 존치하고, 실운영(C1) 또는 M2 알림 설계 시점에 상한 필요성이
재관측되면 그때 별도 상신한다(AD-12).

### 2.3 안전판 형상(참고 기록 — AD-12로 미채택, 재상신 시 출발점)

- 이름(제안): `escape_hold_ceiling_ticks` — 프로파일별 정수, 기본값 `null`(비활성 =
  현재 방향 A 그대로, 무제한 dwell).
- 의미: 이스케이프가 **연속으로** 이탈을 차단한 틱수가 이 값에 도달하면, 그 다음
  틱부터는 이스케이프를 무시하고 원래의 `composite_lt` 단독 조건으로 이탈을 재개한다
  (한 번 "풀리면" 카운터는 리셋 — 이스케이프가 다시 몇 틱 꺼졌다 켜지면 새 상한
  구간이 다시 시작된다).
- 위치: `configs/statemachine.yaml` `profiles.<profile>.escape_hold_ceiling_ticks`
  (dwell·cooldown과 나란한 프로파일별 틱 카운트 파라미터 — D-16의 "틱 카운트만
  프로파일별로 다르다" 원칙과 정합).
- 기본값 근거: mobile_daily가 위험이 관측된 프로파일이므로 활성화한다면 그 값은
  BT-03류 스윕(골든 무회귀 + §6 게이트 재확인)으로 결정해야 한다 — 이 문서는 형상만
  제안하고 값을 추측하지 않는다(D-04).
- **Stage B는 이 안전판을 구현하지 않는다(AD-12 확정)** — GM0가 승인한 것은
  방향 A(짝지음) 자체이지 상한 규율이 아니고, Advisor는 §2.1 실측을 "안전판이
  필요한 증거"가 아니라 "D-03이 의도한 보수성이 실제로 작동하는 증거"로 귀속했다.
  이 형상 초안은 향후(C1·M2) 실운영에서 상한 필요성이 재관측될 경우의 출발점으로만
  남긴다 — 지금 만들지 않는다.

## 3. SSOT 형상 — configs 키 신설 없음, D-25 선례를 따르는 엔진 의미론 확정

**결정: 새 configs 키를 두지 않는다.** `or_any_crit: true` / `or_any_extreme: true`
라는 **기존** 플래그의 존재 자체가 이미 "이 레벨은 이스케이프 대상"이라는 정보를
완전히 담고 있다 — pairing은 그 플래그가 **이미 하고 있던 일**(진입 조건 완화)에
대칭 짝(이탈 조건 강화)을 엔진이 자동으로 추가하는 것뿐이다. 새 토글이 있다면
그것은 "이 레벨에서 짝지음을 켤지 끌지"인데, D-26의 결정 자체가 "`or_*` 이스케이프
전 계열 공통"이라 명시했으므로 끄는 옵션 자체가 설계상 존재하지 않는다 — 끄고
싶으면 애초에 그 레벨에 `or_any_*` 키를 정의하지 않으면 된다(이미 configs가 그
분기점을 쥐고 있다).

**근거(D-25 선례와의 정합)**: D-25 §1~§3은 정확히 이런 성격의 확정 3건을 "configs
값 변경 없음 — 해석의 확정"으로 기록했다. 다만 D-25는 순수 해석 명확화(코드가 이미
그렇게 동작해야 했는데 모호했던 것을 확정)였고, D-26 pairing은 **실제 타임라인이
바뀌는 진짜 거동 변경**이라는 점에서 다르다 — 그래서 이 문서 §1.4가 D-25에
"부기"로 붙이는 형식을 취한다(TASK 브리프가 명시한 방식): 값은 그대로, 그러나 그
값(`or_any_crit`/`or_any_extreme` 플래그)이 엔진에서 무엇을 뜻하는지가 넓어진다.

**Kotlin 패리티(BT-05) 비교**:
- **엔진 의미론 확정(채택)**: Kotlin은 Python과 동일한 고정 규칙(레벨-로컬,
  reset)을 하드코딩하면 된다 — `or_any_crit` 패턴을 이미 이식했어야 하는 코드
  경로(진입 쪽)의 **대칭 분기 하나**를 추가하는 정도다. 스키마 변경이 없으므로
  두 엔진이 같은 yaml을 파싱하는 방식도 무변경 — 패리티 테스트(BT-05, `|Δcomposite|
  ≤ 0.05`)가 커버하는 표면이 늘지 않는다.
  - MT0-06 §3-A(d)가 `or_any_extreme` 자체의 Kotlin 부담을 "moderate"(기존
    `or_any_crit` 패턴의 자연스러운 일반화)로 평가한 것과 같은 논리가 pairing에도
    적용된다: 새 개념이 아니라 기존 개념(이스케이프 플래그)의 자연스러운 완성이다.
- **configs 키 신설(기각)**: 두 엔진이 새 스키마 필드를 **각각** 파싱하고, 그 값의
  해석(레벨-로컬이냐 스태킹이냐, 어떤 스트릭 정책이냐)까지 각각 구현해야 한다 —
  드리프트 표면이 늘어난다(스키마 파싱 불일치 + 의미론 불일치라는 두 겹 위험).
  D-26이 이미 "전 계열 공통" 단일 규칙으로 결정했으므로, 이걸 굳이 configs로
  노출해 두 엔진이 매번 같은 값을 읽어 같은 분기를 타게 만드는 것은 불필요한
  간접화다(YAGNI — 끌 수 있는 옵션이 필요하다는 요구가 없다).

## 4. 골든 호환성 사전 분석 (핵심 리스크) — 4개 변형 전부 완전 무손상

레벨-로컬×reset·레벨-로컬×accumulate·스태킹×reset·스태킹×accumulate **네 조합
전부**를 `backtest/run_sweep.golden_pass()`(재구현 없음, `RS.golden_pass` 그대로
재사용)로 확인했다(§8 섹션 1):

- **4개 변형 전부 `golden_pass=True`.**
- **`golden_mobile.yaml`의 두 창(양성 `w2024_carry_unwind`·음성 `w2024_05_calm`)
  틱별 phase·composite가 baseline과 0건 불일치** — 전 틱 완전 동일(±1e-6 허용오차,
  실제로는 정확히 동일).
- **`golden_server.yaml` 양성 체크(2024-08-05 kr_close)의 phase = ORANGE**(D-08
  요건 "ORANGE 이상" 충족, D-14/MT0-04가 확정한 baseline 값과 일치) — 4개 변형
  전부 동일.

**이유(구조적, 데이터가 아니라 골든 타임라인의 형태 자체가 결정한다)**: 골든 양성
창은 `GREEN → AMBER(08-02) → RED(08-05, skip_levels)`로 **단조 상승만** 하고
`w2024_carry_unwind` 구간 안에서 단 한 번도 강등(이탈)이 일어나지 않는다 —
pairing은 이탈 평가 분기 안에서만 작동하므로, 이탈이 원래도 한 번도 발동하지 않는
창에서는 pairing이 어떤 조합이든 **개입할 지점 자체가 없다**. 음성 창
`w2024_05_calm`은 composite가 전 틱 0.0으로 AMBER 진입 자체가 없어(이스케이프도
전 틱 거짓) 마찬가지다. 이는 단일 데이터 우연이 아니라 **골든 타임라인의 형태가
가진 성질**이므로, 이스케이프 대상이 `or_any_crit`이든 향후 다른 `or_*` 계열이든
같은 결론이 유지될 것으로 예상한다(단, 새 이스케이프가 실제로 골든 구간에서 발화하는
경우는 개별 재확인이 필요하다 — MT0-06 §3-A(b)가 `or_any_extreme` 골든 안전
상한(15.557%)을 별도로 계산해 사전에 확인한 것과 같은 절차).

**결론(D-26 제약 ①)**: 프로덕션 `or_any_crit`에 pairing(레벨-로컬×reset)을 적용해도
**D-08 2케이스×2프로파일 무회귀가 100% 유지된다** — 사용자에게 "골든이 깨진다"고
보고할 사안이 이번 표본에서는 발생하지 않았다. Stage B 착수를 막는 골든 리스크는
없다.

## 5. §6 재판정 예상 — 프로덕션 `or_any_crit` 짝지음 (9창 샌드박스 실측)

레벨-로컬×reset(§1에서 채택한 형태)을 프로덕션 조건(`or_any_crit`만, `configs/
statemachine.yaml` 그대로)에 적용한 9창 실측(§8 섹션 2):

| 항목 | baseline(0.3.0-rc) | pairing 적용 후 | 비고 |
|---|---|---|---|
| server 플래핑(양성 최대) | **25**(w2015) | **13**(w2020) | **거의 절반으로 개선** — 여전히 §6 상한(6) 초과 FAIL이나, AD-5가 지목한 원인(설정 모순)을 직접 제거한 효과가 실측으로 확인됨 |
| server 플래핑(음성 최대) | 0 | 0 | 불변 |
| mobile 플래핑(양성 최대) | **6**(w2026, 여유 0) | **5** | **개선** — 하드 게이트 여유가 0에서 1로 늘어남 |
| mobile 플래핑(음성 최대) | 2(w2023_11) | 2 | 불변 |
| mobile 오탐(`w2023_11_rally` AMBER틱) | **18**(FAIL, 상한 3) | **18** | **불변 — 짝지음이 이 창의 AMBER 체류를 늘리지도 줄이지도 않았다**(항목 명시 확인 완료) |
| 탐지율(양성, w2026 제외) | 6/6 두 프로파일 | 6/6 두 프로파일 | 불변 |
| 리드타임 중앙값 | server 7.0 / mobile 7.5 | server 7.0 / mobile 7.5 | 완전 불변 — pairing은 진입(승격) 쪽에 전혀 관여하지 않으므로 당연한 결과 |
| 첫 ORANGE 도달일(전 9창) | — | — | **9창 18개 (창,프로파일) 쌍 전부 baseline과 동일한 날짜** — 탐지 시점은 단 하루도 이동하지 않는다 |

**결론**: `or_any_crit` pairing은 **탐지·리드타임을 조금도 훼손하지 않으면서
플래핑을 개선**한다(mobile은 §6 하드 게이트 여유를 0→1로, server는 원인 진단대로
크게 완화). 유일한 대가는 §2가 정량화한 dwell 증가(alarm 지속 시간)이며, 이는
§2의 상한 규율 논의가 다루는 정확한 트레이드오프다. `w2023_11_rally` 오탐 FAIL은
그대로 남는다(개선도 악화도 아님) — AD-8 정직성 조항대로 이 FAIL을 지우기 위한
재보정은 여기서도 하지 않았다.

## 6. ① 변형(`or_any_extreme` + 짝지음) 재시뮬 프로토콜

### 6.1 목적과 범위

MT0-06/BT-04는 ①(`or_any_extreme`, ORANGE 한정)이 3후보(16/18/20%) 전부에서
`w2026_structural` mobile 전이 6→9로 플래핑 하드 게이트에 걸려 탈락했다고 확정했다
(BT4.3·BT4.4, AD-11(iv) 구조 동형성 경고의 실증). 이 절은 "①에 D-26 pairing을
같이 적용하면 그 실격 사유가 해소되는가"를 재시뮬하는 절차를 정의한다. **① 변형의
프로덕션 채택 여부는 이 절이 정하지 않는다 — 별도 사용자 결정 사항이다.**

### 6.2 예비 관찰(비공식, Stage B 정식 결과 아님 — §8 섹션 6)

`kospi_drawdown` extreme=20.0%(f06_variants.yaml 후보값) + pairing(레벨-로컬×reset,
`or_any_crit`·`or_any_extreme` 둘 다)을 1개 후보로 미리 실행해봤다:

- **`w2026_structural` mobile 전이 = 5**(baseline-①-무pairing이었던 9에서 대폭
  감소, §6 하드 게이트 6 이하로 복귀) — pairing이 AD-11(iv)가 지적한 병리를 실제로
  없앤다는 초기 신호다.
- **`w2026_structural` mobile 첫 ORANGE = 2026-07-08**(Stage A §3-A(c) 산술 투사와
  정확히 일치, 07-28 마감보다 크게 앞섬).
- **그러나 `w2026_structural` server_intraday 첫 ORANGE = None(미탐지 그대로)** —
  pairing은 이탈 조건만 건드리므로 AD-10이 `or_any_extreme`에서 면제하지 않은
  `distinct_axes_gte: 2`(진입 조건)에는 **전혀 영향을 주지 않는다**. §6이 두
  프로파일 모두의 탐지를 요구하므로, mobile 문제(플래핑)가 pairing으로 풀려도
  server 문제(distinct_axes 미탐지)가 남아 "① 달성" 판정은 여전히 성립하지 않는다.
- golden_pass = True(이 조합에서도 골든 무손상).

이 관찰은 **정식 Stage B 재시뮬을 대체하지 않는다** — 아래 프로토콜로 3후보
전부·모든 게이트를 정식으로 재평가해야 한다.

### 6.3 정식 재시뮬 절차

1. **게이트·랭킹은 `backtest/f06_variants.yaml` `selection` 블록을 그대로 승계**
   한다(CLAUDE.md §1 — 새 하드코딩 금지). 특히:
   - `selection.gate.golden`(hard, `applies_to: [A_threshold_ladder_extension]`)
     — pairing을 더해도 사전 필터는 동일하게 적용한다.
   - `selection.gate.flapping`(hard, `positive_transitions_max: 6`,
     `negative_transitions_max: 2`, mobile 한정, server는 `except_profiles`) — 이
     게이트가 pairing 도입의 존재 이유(예비 관찰상 6→9→5로 회복)이므로 **완화하지
     않는다**.
   - `selection.rank`(hard_gate → w2026_target → tie_break) — 순서·키 불변.
2. **하니스 확장**: `backtest/run_f06_variants.py`의 `evaluate_variant_a()`에
   pairing 적용 여부를 위한 인자(예: `apply_pairing: bool`)를 추가하거나, `A`
   후보 3종을 "pairing 없음(현재)"과 "pairing 있음(신규)" 두 그룹(6평가)으로
   확장한다. `or_any_crit`(AMBER)에도 항상 pairing을 함께 적용한다 — D-26이 "전
   계열 공통"으로 결정했으므로 ORANGE에만 선택적으로 적용하는 것은 결정 위반이다.
3. **server distinct_axes 미탐지 문제 처리** — **실측 검토만, 스코프 확대 금지**:
   - 원인(§6.2 관찰, MT0-06 §8.3 재확인): AD-10이 `or_any_extreme`에서
     `distinct_axes_gte`를 면제하지 않았기 때문에, server 그리드에서 raw drawdown이
     extreme을 넘는 틱에 다른 축이 동시 발화하지 않으면 ORANGE 진입 자체가 없다.
   - **해소 방안이 있다면 제안만 한다**: `or_any_extreme`도 `or_any_crit`처럼
     `distinct_axes_gte`까지 전부 우회하도록 넓히면 server 탐지는 개선될 수 있으나,
     이는 AD-10이 **의도적으로** 쳐 둔 안전장치(단일 축 급변만으로 ORANGE 승격되는
     것을 막는 목적)를 제거하는 것이다 — F-5(MT0-06)가 지적한 "목표 외 신규 RED/
     ORANGE 승격" 부작용 범주를 재도입할 위험이 있다. 이것은 pairing 설계의 범위
     밖이며, 채택하려면 **AD-10 자체의 재검토**라는 별도 결정이 필요하다.
   - 재시뮬 절차는 이 대안을 **측정만** 한다: 후보 그리드에 "distinct_axes_gte
     유지(현행)"·"distinct_axes_gte 우회(대안)" 두 축을 추가해 4×3=12평가로
     확장하고, 결과를 나란히 보고하되 어느 쪽도 기본으로 선택하지 않는다.
4. **비교 산출물**: `BT_REPORT.md` §BT4 형식(발화 창·리드타임·골든·플래핑·server
   Δ전이·Kotlin 부담 열)을 그대로 승계해 pairing 유무 비교표를 추가한다.
5. **완료 기준**: 6(또는 distinct_axes 대안 포함 12) 평가 전부 `results/f06/`에
   기록, 골든 무회귀 재확인(`pytest backtest/test_golden.py`), `metrics.json`
   불변, 랭킹 산출(생존자가 있으면 §4.3 규칙대로, 없으면 "채택 후보 0"을 그대로
   보고 — AD-1(iv) 정직성 조항).
6. **① 변형의 프로덕션 채택은 이 재시뮬로 결정되지 않는다** — 재시뮬은 "게이트를
   통과하는 후보가 있는가"까지만 답하고, 실제 configs 반영은 이 결과를 본 사용자의
   별도 승인 사항이다(BACKTEST_PLAN §BT-04 "임의 구현 금지" 원칙 승계).

## 7. Stage B 구현 요건 + 완료 기준

### 7.0 실행 순서 (O-3)

Stage B는 아래 3단계를 **이 순서로** 수행한다 — 뒤 단계가 앞 단계의 산출물(그린
게이트·재생성된 `metrics.json`)에 의존하므로 순서를 바꾸면 재작업이 생긴다.

1. **pairing 프로덕션 반영** — §7.1의 `engine_ref/statemachine.py` 변경 + 신규
   증인 테스트(a)(b)(c) + `uv run pytest backtest/test_golden.py -q` 6 green까지
   확인한다. 이 시점까지 `configs/*.yaml`·`backtest/results/metrics.json`은
   손대지 않는다.
2. **`metrics.json` 재생성** — 1이 green인 상태에서만 수행한다. AD-13(§0.1) 조건
   3건(note 출처·§6 diff 표·골든 전후 green)을 전부 충족한다(§7.2).
3. **① 변형 재시뮬(§6)** — 2까지 끝난 프로덕션 위에서 샌드박스로 수행한다(§6.3
   절차 그대로, `f06_variants.yaml` selection 승계). 이 단계는 `configs/*.yaml`을
   다시 건드리지 않고 **2에서 재생성한 `metrics.json`도 불변**이어야 한다(AD-7
   샌드박스 불변식 승계 — §6.2 프리뷰가 이미 확인한 패턴 그대로).

### 7.1 구현 범위

- `engine_ref/statemachine.py`: `_exit_satisfied()` 호출부(또는 그 직전)에
  레벨-로컬 짝지음을 추가 — §1.4 D-25 부기 문안 그대로. 새 Tick 필드·config 스키마
  키는 불필요(§3). `_KNOWN_UPGRADE_KEYS`·`_rule_satisfied()`는 무변경(진입 쪽은
  이미 D-25로 확정돼 있다).
- `configs/statemachine.yaml`: **값 변경 없음.** 이 결정은 순수 엔진 의미론
  확정이다(§3) — GM0 승인 대상은 이 자체다.
- `engine_ref` 변경은 **골든 무회귀 증인**(D-08 2케이스×2프로파일, 정확히 §4가
  확인한 4개 조합 중 채택분인 레벨-로컬×reset)을 반드시 회귀 테스트로 고정한다.
- 신규 단위 테스트(§7.3이 완료 기준으로 요구하므로 필수 — O-2, "권고" 아님):
  (a) 레벨-로컬 스코프 증인 — 상위 레벨에 있을 때 하위 레벨 이스케이프가 이탈에
  영향을 주지 않음을 단정 (b) reset 스트릭 정책 증인 — 이스케이프 해제 직후
  강등까지 다시 `demote_below_ticks` 전체가 필요함을 단정 (c) §9.2(b)의 상수 입력
  한계진동 재현 케이스가 pairing 적용 후 실제로 진동하지 않음을 단정(정확히 이
  결정이 고치려는 병리의 직접 회귀 테스트).

### 7.2 `backtest/results/metrics.json` 취급 (MT0-05 §14.1 규율 승계)

- Stage B가 `configs/statemachine.yaml`을 **값 변경 없이**(§3) 두더라도,
  `engine_ref/statemachine.py`의 **거동**은 바뀌므로 `metrics.json`(0.3.0-rc
  프로덕션 리플레이 산출물)의 **수치 자체가 재생성 시 달라진다** — §5의 델타표가
  그 크기를 보인다(예: server pos_transitions_max 25→13).
- **AD-13(§0.1)으로 사전 승인됐다** — MT0-05 §14.1 교훈(Stage B 라운드 1이 승인
  없는 재생성으로 qa-verifier 반려를 받은 전례)에 따라, 이 저널 자체가 그 사전
  승인 문서다. Stage B 완료 보고는 AD-13의 조건 3건 충족 여부를 전부 명기해야
  한다: (i) 재생성본의 `note` 필드(또는 동반 기록)에 "registry 0.3.0-rc configs
  불변 + D-26 pairing semantics 적용" 출처 명기 (ii) 재생성 전후 §6 지표 diff
  표를 BT_REPORT.md 부기에 보고(§5 델타표 승계 — 예: server pos_transitions_max
  25→13) (iii) 골든 6 green을 재생성 **전후 모두** 재확인.
- `registry_version`은 **0.3.0-rc 그대로 유지**한다(AD-13) — `configs/
  statemachine.yaml` 값은 무변경이므로(§3) 버전 스탬프를 올릴 근거가 없다.
  의미론 변경 사실은 위 (i)의 `note` 필드로만 구분하고, 별도 버전 라벨(예:
  0.3.1)을 새로 붙이지 않는다.
- 샌드박스 실측(이 저널 자체)은 `metrics.json`을 **건드리지 않았다**(§8 섹션 7,
  SHA-256 실행 전후 동일 확인) — Stage B의 "정식 프로덕션 반영" 재생성과 이 저널의
  "탐색적 사전 측정"은 구분된다.

### 7.3 완료 기준 (제안, Advisor 확정 대상)

- `uv run ruff check . && uv run pytest -q` green.
- `uv run pytest backtest/test_golden.py -q` 6 green(pairing 반영 후).
- 신규 증인 테스트(§7.1 (a)(b)(c)) green.
- `metrics.json` 재생성 시 AD-13(§0.1) 조건 3건(note 출처·§6 diff 표·골든 전후
  green) 전부 충족 + git diff 원문 첨부.
- 상한(escape hold ceiling)은 Stage B 범위에 포함하지 않는다(AD-12) — §2.3 형상
  초안은 구현 대상이 아니라 참고 기록이다.
- §6 프로토콜에 따른 ① 변형 재시뮬 산출물(결과 무관 — 생존자 0이어도 정직 보고).
- server 플래핑 FAIL(§6) 해소 여부를 있는 그대로 보고(§5 실측: 25→13로 개선되나
  여전히 FAIL — "해소됐다"고 과장하지 않는다).
- qa-verifier → aaa-critic 2단 PASS(코드·문서 모두), 완료 보고에 `git status
  --porcelain` 원문 + 게이트 명령 마지막 줄 첨부(REVIEW_M0 신설 규율).
- `docs/P0_DESIGN_DECISIONS.md` D-25에 §1.4 부기 문안 물질화 + D-26 제약 ③에
  역참조 추가 — **주체: Advisor**(이 설계 저널의 aaa-critic PASS 직후 수행,
  Stage B Worker의 구현 범위가 아니다, M-3).

## 8. 재현 방법 (검증자용)

전 실측(§1~§6)은 아래 스크립트 **전문**(발췌 아님 — `main()` 포함, 그대로 저장해
실행하면 이 저널의 모든 표가 그 stdout에서 그대로 나온다) 하나의 실행 산출물이다.
**저장소에는 없다** — scratchpad에 저장 후 저장소 루트를 `cwd`로 `uv run python`으로
실행한다(bt03/bt04 repro 패턴을 저장소 밖에서 수행 — Stage A는 이 문서 하나만
저장소를 바꾼다). `engine_ref.statemachine.run`을 실행 중에만 몽키패치하고 즉시
원복하므로 `configs/*.yaml`·`backtest/results/metrics.json`은 전혀 쓰지 않는다
(스크립트 자체가 SHA-256 전후 동일을 출력해 검증한다).

**M-2(재구현 대조군)**: `main()` 첫 블록("0b.")이 `make_paired_run(None, "reset")`
재구현 결과와 실제 `engine_ref.statemachine.run`(패치 없음) 결과를 9창×2프로파일
18쌍 전부 틱별 phase로 직접 비교한다 — **불일치 0/18**(이 저널·aaa-critic 독립
실행 양쪽에서 확인됨). 이것이 성립해야 §1·§2·§3·§4·§5·§6의 모든 델타가 "진짜
프로덕션 엔진 대비 델타"라는 근거가 선다 — 재구현 자체가 프로덕션과 다르면 이하
전 수치가 무의미해지기 때문이다.

```bash
uv run python <스크립트 경로>   # 아래 전문을 파일로 저장 후 실행. ~20초.
```

```python
"""MT0-07 Stage A sandbox repro — D-26 escape-exit pairing empirical measurement.

NOT part of the repository. Lives in scratchpad, imports the repo's backtest/engine_ref
packages in-process, monkeypatches engine_ref.statemachine.run for the duration of a
call, and restores it immediately after. configs/*.yaml and backtest/results/metrics.json
are never written to (read-only yaml loads via backtest.run_sweep/run_f06_variants module
globals, replay executed in-process with no file writes for the pairing variants).

Usage:  uv run python <this file>          (run with cwd = repo root)

approx-PIT — C1 confirms later. Numbers here are 9-window sandbox estimates only,
feeding docs/journal/2026-08-03_MT0-07_pairing_design.md.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(r"D:\wp_2026\branch-console")
sys.path.insert(0, str(REPO_ROOT))

import hashlib

from backtest import run_f06_variants as F06R
from backtest import run_replay as R
from backtest import run_sweep as RS
from engine_ref import statemachine as SM
from engine_ref.statemachine import Tick, _exit_satisfied, _rule_satisfied  # noqa: F401

sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # K-xx cp949 console trap

PROFILES = RS.PROFILES
ALL_WINDOW_IDS = F06R.ALL_WINDOW_IDS
POS_IDS = F06R.POS_IDS
NEG_IDS = F06R.NEG_IDS
WINDOW_CTX = RS.WINDOW_CTX
BASE_SM = RS.BASE_SM
GOLDEN_IDS = RS.SW["golden_constraint"]["windows"]

METRICS_JSON_PATH = REPO_ROOT / "backtest" / "results" / "metrics.json"


# -----------------------------------------------------------------------------
# paired run() variants (sandbox-only copies of engine_ref.statemachine.run, D-26)
# -----------------------------------------------------------------------------


def _escape_flag_of_level(config) -> dict[str, str]:
    """Which upgrade level has an or_* escape key, and which Tick field gates it.
    Generalizes over the "or_* escape family" per D-26 scope (current: or_any_crit on
    AMBER; or_any_extreme would appear here too if a candidate config defines it on
    ORANGE)."""
    out: dict[str, str] = {}
    for lvl in config.phases[1:]:
        rule = config.upgrade.get(lvl, {})
        if rule.get("or_any_crit"):
            out[lvl] = "any_crit"
        if rule.get("or_any_extreme"):
            out[lvl] = "any_extreme"
    return out


def make_paired_run(pairing_mode: str | None, streak_policy: str = "reset"):
    """pairing_mode: None | "level_local" | "stacking".
    streak_policy: "reset" (D-26 literal: exit condition treated as unmet -> same
    reset path as an ordinary unmet exit) | "accumulate" (streak keeps accumulating,
    only the final commit is gated by "not blocked" -- dwell-style second gate)."""

    def run_paired(ticks: list[Tick], profile, config) -> list[str]:
        order = config.phases
        idx = {name: i for i, name in enumerate(order)}
        levels = order[1:]
        escape_of = _escape_flag_of_level(config)

        def escape_active(tick: Tick, lvl: str) -> bool:
            flag = escape_of.get(lvl)
            return bool(flag) and bool(getattr(tick, flag))

        phase = config.initial_phase
        ticks_in_phase = 1
        promote_streaks = dict.fromkeys(levels, 0)
        demote_streak = 0
        cooldown = 0
        timeline: list[str] = []

        for tick in ticks:
            if tick.composite is None:
                timeline.append(phase)
                continue

            ticks_in_phase += 1

            if cooldown > 0:
                for level in levels:
                    promote_streaks[level] = 0
            else:
                for level in levels:
                    rule = config.upgrade[level]
                    if _rule_satisfied(
                        rule, tick.composite, tick.distinct_axes, tick.any_crit, tick.any_extreme
                    ):
                        promote_streaks[level] += 1
                    else:
                        promote_streaks[level] = 0

            phase_idx = idx[phase]
            transitioned = False

            if cooldown == 0:
                eligible = [
                    lvl
                    for lvl in levels
                    if idx[lvl] > phase_idx
                    and promote_streaks[lvl] >= profile.promote_sustain_ticks
                ]
                if eligible:
                    target = (
                        max(eligible, key=lambda lvl: idx[lvl])
                        if config.skip_levels
                        else min(eligible, key=lambda lvl: idx[lvl])
                    )
                    phase = target
                    ticks_in_phase = 1
                    demote_streak = 0
                    transitioned = True

            if not transitioned and phase != order[0]:
                exit_rule = config.downgrade[f"exit_{phase}"]
                base_exit_ok = _exit_satisfied(exit_rule, tick.composite)

                if pairing_mode == "level_local":
                    blocked = escape_active(tick, phase)
                elif pairing_mode == "stacking":
                    blocked = any(
                        escape_active(tick, lvl) for lvl in levels if idx[lvl] <= phase_idx
                    )
                else:
                    blocked = False

                if streak_policy == "reset":
                    exit_ok = base_exit_ok and not blocked
                    if exit_ok:
                        demote_streak += 1
                        if (
                            demote_streak >= profile.demote_below_ticks
                            and ticks_in_phase >= profile.min_dwell_ticks + 1
                        ):
                            phase = order[idx[phase] - 1]
                            ticks_in_phase = 1
                            demote_streak = 0
                            cooldown = profile.reentry_cooldown_ticks
                            transitioned = True
                    else:
                        demote_streak = 0
                else:  # "accumulate": streak counts base_exit_ok alone; blocked only gates commit
                    if base_exit_ok:
                        demote_streak += 1
                        if (
                            demote_streak >= profile.demote_below_ticks
                            and ticks_in_phase >= profile.min_dwell_ticks + 1
                            and not blocked
                        ):
                            phase = order[idx[phase] - 1]
                            ticks_in_phase = 1
                            demote_streak = 0
                            cooldown = profile.reentry_cooldown_ticks
                            transitioned = True
                    else:
                        demote_streak = 0

            if cooldown > 0 and not transitioned:
                cooldown -= 1

            timeline.append(phase)

        return timeline

    return run_paired


def run_with_patch(pairing_mode, streak_policy, window_ids):
    original = SM.run
    SM.run = make_paired_run(pairing_mode, streak_policy)
    try:
        return R.run_replay(list(PROFILES), window_ids)
    finally:
        SM.run = original


# -----------------------------------------------------------------------------
# stats helpers (mirrors BT4.1 9-window gate definitions)
# -----------------------------------------------------------------------------


def stats_for(result):
    return {
        (wid, profile): RS.window_profile_stats(result, wid, profile, WINDOW_CTX[wid])
        for wid in ALL_WINDOW_IDS
        for profile in PROFILES
    }


def gate_9w(stats):
    out = {}
    for profile in PROFILES:
        det_ids = [wid for wid in POS_IDS if wid not in RS.DETECTION_EXCEPT_WINDOWS]
        detection_pass = all(stats[(wid, profile)]["detected"] for wid in det_ids)
        leads = [stats[(wid, profile)]["lead"] for wid in POS_IDS if stats[(wid, profile)]["lead"] is not None]
        lead_median = sorted(leads)[len(leads) // 2] if leads and len(leads) % 2 else (
            (sorted(leads)[len(leads) // 2 - 1] + sorted(leads)[len(leads) // 2]) / 2 if leads else None
        )
        fp_orange = sum(stats[(wid, profile)]["orange_or_above_ticks"] for wid in NEG_IDS)
        fp_amber = sum(stats[(wid, profile)]["amber_ticks"] for wid in NEG_IDS)
        pos_trans_max = max((stats[(wid, profile)]["n_transitions"] for wid in POS_IDS), default=0)
        neg_trans_max = max((stats[(wid, profile)]["n_transitions"] for wid in NEG_IDS), default=0)
        out[profile] = {
            "detection_pass": detection_pass,
            "lead_median": lead_median,
            "fp_orange_sum": fp_orange,
            "fp_amber_sum": fp_amber,
            "pos_transitions_max": pos_trans_max,
            "neg_transitions_max": neg_trans_max,
        }
    return out


def amber_plus_ticks(result, wid, profile):
    ticks = result["windows"][wid][profile]["ticks"]
    return sum(1 for t in ticks if t["phase"] in ("AMBER", "ORANGE", "RED"))


def n_ticks(result, wid, profile):
    return len(result["windows"][wid][profile]["ticks"])


def max_amber_plus_run(result, wid, profile):
    """longest single continuous episode of phase in {AMBER,ORANGE,RED} -- the 'stuck'
    measure item 2 actually cares about, as opposed to the cumulative sum across
    separate excursions."""
    ticks = result["windows"][wid][profile]["ticks"]
    best = cur = 0
    for t in ticks:
        if t["phase"] in ("AMBER", "ORANGE", "RED"):
            cur += 1
            best = max(best, cur)
        else:
            cur = 0
    return best


def max_any_crit_run(result, wid, profile):
    ticks = result["windows"][wid][profile]["ticks"]
    best = cur = 0
    for t in ticks:
        if t["any_crit"]:
            cur += 1
            best = max(best, cur)
        else:
            cur = 0
    return best


def print_gate(label, gate):
    for p in PROFILES:
        g = gate[p]
        print(
            f"    [{label}] {p}: detection_pass={g['detection_pass']} lead_median={g['lead_median']} "
            f"fp_orange={g['fp_orange_sum']} fp_amber={g['fp_amber_sum']} "
            f"pos_trans_max={g['pos_transitions_max']} neg_trans_max={g['neg_transitions_max']}"
        )


def main() -> int:
    metrics_before = METRICS_JSON_PATH.read_bytes() if METRICS_JSON_PATH.exists() else b""
    hash_before = hashlib.sha256(metrics_before).hexdigest()

    print("=" * 78)
    print("0. baseline (0.3.0-rc as-is, unpatched engine_ref.statemachine.run)")
    print("=" * 78)
    baseline_result = R.run_replay(list(PROFILES), ALL_WINDOW_IDS)
    baseline_stats = stats_for(baseline_result)
    baseline_gate = gate_9w(baseline_stats)
    print_gate("baseline", baseline_gate)
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            print(
                f"    {wid}|{p}: n_transitions={baseline_stats[(wid, p)]['n_transitions']} "
                f"amber_plus_ticks={amber_plus_ticks(baseline_result, wid, p)} "
                f"max_any_crit_run={max_any_crit_run(baseline_result, wid, p)} "
                f"first_orange={baseline_stats[(wid, p)]['first_orange_or_above_date']}"
            )

    print("\n" + "=" * 78)
    print("0b. M-2: reimplementation parity check -- make_paired_run(None,'reset') vs real "
          "engine_ref.statemachine.run (must be byte-identical timelines, 9 windows x 2 profiles)")
    print("=" * 78)
    reimpl_none_result = run_with_patch(None, "reset", ALL_WINDOW_IDS)
    mismatches = 0
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            real_phases = [t["phase"] for t in baseline_result["windows"][wid][p]["ticks"]]
            reimpl_phases = [t["phase"] for t in reimpl_none_result["windows"][wid][p]["ticks"]]
            if real_phases != reimpl_phases:
                mismatches += 1
                print(f"    MISMATCH {wid}|{p}: real != reimpl(None)")
    print(f"  mismatches={mismatches} / 18 (window,profile) pairs -- "
          f"{'reimplementation is a faithful copy of the production engine' if mismatches == 0 else 'REIMPL DIVERGES FROM PRODUCTION -- all downstream deltas are suspect'}")

    print("\n" + "=" * 78)
    print("1. golden compatibility (D-08 2 cases x 2 profiles) under each pairing variant")
    print("=" * 78)
    variants = [
        ("level_local_reset", "level_local", "reset"),
        ("level_local_accumulate", "level_local", "accumulate"),
        ("stacking_reset", "stacking", "reset"),
        ("stacking_accumulate", "stacking", "accumulate"),
    ]
    golden_results = {}
    for label, mode, policy in variants:
        gresult = run_with_patch(mode, policy, GOLDEN_IDS)
        golden_results[label] = gresult
        ok, reason = RS.golden_pass(gresult, BASE_SM)
        print(f"  [{label}] golden_pass={ok} reason={reason}")
        # explicit tick-level diff vs golden_mobile.yaml frozen ticks
        for wid, gm_window in RS.GOLDEN_MOBILE["windows"].items():
            actual = gresult["windows"][wid]["mobile_daily"]["ticks"]
            expected = gm_window["ticks"]
            n_diff = 0
            if len(actual) != len(expected):
                print(f"      {wid}: TICK COUNT MISMATCH {len(actual)} != {len(expected)}")
                continue
            for a, e in zip(actual, expected, strict=True):
                if a["phase"] != e["phase"] or abs(a["composite"] - e["composite"]) > 1e-6:
                    n_diff += 1
            print(f"      {wid} mobile_daily: {n_diff} tick(s) differ from golden_mobile.yaml")
        # server check tick
        server_ticks = gresult["windows"]["w2024_carry_unwind"]["server_intraday"]["ticks"]
        hit = next(
            (t for t in server_ticks if t["date"] == "2024-08-05" and t["kst_time"] == "17:00"),
            None,
        )
        print(f"      w2024_carry_unwind server_intraday 2024-08-05 17:00: {hit['phase'] if hit else 'MISSING'}")

    print("\n" + "=" * 78)
    print("2. or_any_crit PRODUCTION pairing (level_local, reset) -- 9-window sandbox, section-6-style gate")
    print("=" * 78)
    primary_result = run_with_patch("level_local", "reset", ALL_WINDOW_IDS)
    primary_stats = stats_for(primary_result)
    primary_gate = gate_9w(primary_stats)
    print_gate("primary(level_local,reset)", primary_gate)
    print("  per-window delta vs baseline (n_transitions, amber_plus_ticks):")
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            dn = primary_stats[(wid, p)]["n_transitions"] - baseline_stats[(wid, p)]["n_transitions"]
            da = amber_plus_ticks(primary_result, wid, p) - amber_plus_ticks(baseline_result, wid, p)
            fo_delta = (
                primary_stats[(wid, p)]["first_orange_or_above_date"]
                != baseline_stats[(wid, p)]["first_orange_or_above_date"]
            )
            if dn != 0 or da != 0 or fo_delta:
                print(
                    f"    {wid}|{p}: d_transitions={dn:+d} d_amber_plus_ticks={da:+d} "
                    f"first_orange_changed={fo_delta} "
                    f"(baseline={baseline_stats[(wid,p)]['first_orange_or_above_date']} "
                    f"-> primary={primary_stats[(wid,p)]['first_orange_or_above_date']})"
                )
    print(f"  w2023_11_rally mobile_daily amber_ticks: baseline={baseline_stats[('w2023_11_rally','mobile_daily')]['amber_ticks']} "
          f"primary={primary_stats[('w2023_11_rally','mobile_daily')]['amber_ticks']}")
    fo_unchanged = sum(
        1
        for wid in ALL_WINDOW_IDS
        for p in PROFILES
        if primary_stats[(wid, p)]["first_orange_or_above_date"]
        == baseline_stats[(wid, p)]["first_orange_or_above_date"]
    )
    print(f"  first_orange_or_above_date unchanged in {fo_unchanged}/18 (window,profile) pairs")

    print("\n  max single-episode AMBER+ dwell run (baseline -> primary), window size for scale:")
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            b_run = max_amber_plus_run(baseline_result, wid, p)
            p_run = max_amber_plus_run(primary_result, wid, p)
            if b_run != p_run:
                tot = n_ticks(baseline_result, wid, p)
                print(f"    {wid}|{p}: {b_run} -> {p_run}  (delta {p_run - b_run:+d}, window total ticks={tot})")

    print("\n" + "=" * 78)
    print("3. scope comparison: level_local vs stacking (9-window, or_any_crit)")
    print("=" * 78)
    stacking_result = run_with_patch("stacking", "reset", ALL_WINDOW_IDS)
    stacking_stats = stats_for(stacking_result)
    stacking_gate = gate_9w(stacking_stats)
    print_gate("stacking(reset)", stacking_gate)
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            dn_ll = primary_stats[(wid, p)]["n_transitions"] - baseline_stats[(wid, p)]["n_transitions"]
            dn_st = stacking_stats[(wid, p)]["n_transitions"] - baseline_stats[(wid, p)]["n_transitions"]
            da_ll = amber_plus_ticks(primary_result, wid, p) - amber_plus_ticks(baseline_result, wid, p)
            da_st = amber_plus_ticks(stacking_result, wid, p) - amber_plus_ticks(baseline_result, wid, p)
            if dn_ll != 0 or dn_st != 0 or da_ll != 0 or da_st != 0:
                print(
                    f"    {wid}|{p}: level_local(d_trans={dn_ll:+d}, d_amber_plus={da_ll:+d}) "
                    f"vs stacking(d_trans={dn_st:+d}, d_amber_plus={da_st:+d})"
                )

    print("\n" + "=" * 78)
    print("4. streak policy comparison: reset vs accumulate (level_local, or_any_crit)")
    print("=" * 78)
    accum_result = run_with_patch("level_local", "accumulate", ALL_WINDOW_IDS)
    accum_stats = stats_for(accum_result)
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            dn_reset = primary_stats[(wid, p)]["n_transitions"] - baseline_stats[(wid, p)]["n_transitions"]
            dn_acc = accum_stats[(wid, p)]["n_transitions"] - baseline_stats[(wid, p)]["n_transitions"]
            if dn_reset != 0 or dn_acc != 0:
                print(f"    {wid}|{p}: reset(d_trans={dn_reset:+d}) vs accumulate(d_trans={dn_acc:+d})")

    print("\n" + "=" * 78)
    print("5. any_crit run-length distribution (ALL 18 (window,profile) pairs -- full ranking, journal shows top 5 + this section is the full table)")
    print("=" * 78)
    all_runs = []
    for wid in ALL_WINDOW_IDS:
        for p in PROFILES:
            r = max_any_crit_run(baseline_result, wid, p)
            all_runs.append((r, wid, p))
    all_runs.sort(reverse=True)
    for rank, (r, wid, p) in enumerate(all_runs, start=1):
        print(f"    {rank:2d}. {wid}|{p}: max_any_crit_run={r}")

    print("\n" + "=" * 78)
    print("6. PREVIEW ONLY (informs [6] resim protocol design, not a Stage B result): "
          "or_any_extreme(ORANGE, 20.0%) + pairing(level_local,reset) on AMBER+ORANGE both")
    print("=" * 78)
    import tempfile

    tmp_dir = Path(tempfile.mkdtemp(prefix="mt07_preview_"))
    extreme_pct = 20.0
    ind_variant = F06R.with_kospi_extreme_threshold(RS.BASE_IND, extreme_pct)
    sm_variant = F06R.with_or_any_extreme_orange(RS.BASE_SM)

    original = SM.run
    SM.run = make_paired_run("level_local", "reset")
    try:
        preview_result = RS.run_candidate(tmp_dir, ind_variant, sm_variant, RS.BASE_RP, ALL_WINDOW_IDS)
    finally:
        SM.run = original
    preview_stats = stats_for(preview_result)
    pos_max_mobile = max(preview_stats[(wid, "mobile_daily")]["n_transitions"] for wid in POS_IDS)
    neg_max_mobile = max(preview_stats[(wid, "mobile_daily")]["n_transitions"] for wid in NEG_IDS)
    print(f"  mobile_daily flapping under or_any_extreme+pairing(20.0%): pos_max={pos_max_mobile} (gate<=6) neg_max={neg_max_mobile} (gate<=2)")
    for wid in ALL_WINDOW_IDS:
        print(
            f"    {wid}|mobile_daily: n_transitions={preview_stats[(wid,'mobile_daily')]['n_transitions']} "
            f"first_orange={preview_stats[(wid,'mobile_daily')]['first_orange_or_above_date']}"
        )
        print(
            f"    {wid}|server_intraday: n_transitions={preview_stats[(wid,'server_intraday')]['n_transitions']} "
            f"first_orange={preview_stats[(wid,'server_intraday')]['first_orange_or_above_date']}"
        )
    original2 = SM.run
    SM.run = make_paired_run("level_local", "reset")
    try:
        golden_preview = RS.run_candidate(tmp_dir, ind_variant, sm_variant, RS.BASE_RP, GOLDEN_IDS)
        ok, reason = RS.golden_pass(golden_preview, sm_variant)
    finally:
        SM.run = original2
    print(f"  golden_pass under this preview combo: {ok} ({reason})")

    print("\n" + "=" * 78)
    print("7. metrics.json sandbox invariant")
    print("=" * 78)
    metrics_after = METRICS_JSON_PATH.read_bytes() if METRICS_JSON_PATH.exists() else b""
    hash_after = hashlib.sha256(metrics_after).hexdigest()
    print(f"  sha256 before={hash_before}")
    print(f"  sha256 after ={hash_after}")
    print(f"  unchanged={hash_before == hash_after}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

**게이트 재확인(코드 무변경, Stage A 확인)**:
```bash
uv run ruff check . && uv run pytest -q     # 173 green (기존과 동일 — Stage A는 코드 미변경)
```
