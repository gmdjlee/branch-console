# BT_REPORT — 백테스트 하니스 결과 (BT-02·BT-03·BT-04)

> **근사-PIT — C1에서 실측 확정.** 이 리포트의 모든 수치는 `backtest/fixtures/*.parquet`
> (BT-01 소급 수집)에 대한 리플레이 산출이다. 소급 수집치는 개정치를 포함할 수 있어 진짜
> PIT이 아니고(docs/BACKTEST_PLAN.md §5.1), server_intraday의 30분 재현은 **전 창이 일봉
> 근사**다(§5.2). 여기서 확정하는 `0.3.0-rc`는 1차 보정 가설이며 "모든 임계값은
> 가설"(D-04) 규율이 그대로 적용된다. 진짜 확정은 C1 실측 lake 재수행 몫이다.

- 작성일: 2026-08-03 · 소속: M0 / MT0-05④(BT-03) · 역할: backtest-analyst Worker 산출물
- 설계(Stage A): `docs/journal/2026-08-03_MT0-05_sweep_design.md`(4라운드, aaa-critic PASS 종결)
- 그리드·게이트·선정 SSOT: `backtest/sweep.yaml`
- 스윕 실행기: `backtest/run_sweep.py` · 실행 산출: `backtest/results/sweep/sweep_result.json`
- **결과 라벨: `0.3.0-rc (조건부)`** — §6 미충족 항목 2건이 FAIL로 잔존한다(아래 §1 표,
  스윕 대상 밖으로 원인 귀속됨). 게이트를 느슨히 해 "성공"으로 보이게 만들지 않는다(AD-1).

---

## 0. 반영된 configs 변경 (0.2.0 → 0.3.0-rc)

| 파일 | 키 | 0.2.0 | 0.3.0-rc | 변경 사유 |
|---|---|---|---|---|
| `configs/indicators.yaml` | `registry_version` | 0.2.0 | 0.3.0-rc | BT-03 스윕 선정 반영 |
| `configs/indicators.yaml` | `usdkrw_z.thresholds` | watch2.0/warn2.5/crit3.5 | **무변경** | 35조합 중 골든통과 24개 rank 1위가 기준선과 동일 등가류 |
| `configs/indicators.yaml` | `stale_profiles.mobile_daily.daily_us` | 48h | **무변경** | 3값 중 기준선 48h가 rank 1위 |
| `configs/statemachine.yaml` | `profiles.mobile_daily.promote_sustain_ticks` | 1 | **무변경** | golden상 유일 가용값(2는 56/56 전건 탈락) |
| `configs/statemachine.yaml` | `profiles.mobile_daily.demote_below_ticks` | 3 | **무변경** | 선정 결과 기준선과 동일값이 승자 |
| `configs/statemachine.yaml` | `profiles.mobile_daily.min_dwell_ticks` | 2 | **5** | §6 mobile 플래핑 게이트(양성≤6) 통과 필수 조건(O3-1 dwell>demote 영역) |
| `configs/statemachine.yaml` | `profiles.mobile_daily.reentry_cooldown_ticks` | (미정의, 기본 0) | **2** | 최초 확정. §6 mobile 플래핑 게이트 통과 조건(AD-4) |
| `configs/statemachine.yaml` | `profiles.server_intraday.*` | — | **무변경** | BT-03 ③은 mobile_daily 한정 — 스윕 대상 밖 |
| `backtest/replay.yaml` | `profiles.mobile_daily.confirm_time_kst` | 16:20 | **17:00** | AD-3: 하니스 완전 무감 + 물리·스케줄 논증(§4 참조). M1 실제 확정 틱 설계와 동시 재확인 조건 |

변경 파라미터 수(현행 대비): **3**(min_dwell, reentry_cooldown, confirm_time_kst) — rank 3순위
"단순한 값" 기준으로 동률 후보 중 최소치.

---

## 1. §6 수용 기준 판정표 (BACKTEST_PLAN §6, 전 항목 verbatim — 홀드아웃 제외 7창, 0.3.0-rc)

| 지표 | 기준 | server_intraday | mobile_daily | 판정 | 원인 귀속(FAIL 항목) |
|---|---|---|---|---|---|
| 탐지율 | 6/6 (양성) | 5/6 | 5/6 | **FAIL** | `w2026_structural` 1창만 — 해상도 갭(F-06, severity 포화+z 자기정규화), 스윕 대상 ①~④로 도달 불가 실측 증명(§4). 나머지 5창은 양쪽 다 탐지 |
| 리드타임 중앙값 | ≥1 영업일 | 10 | 12 | PASS | — |
| 리드타임(w2026) | 07-28 이전 도달 | 미도달 | 미도달 | **FAIL** | 위와 동일(해상도 갭) — GM0 안건 1 |
| 오탐 | ORANGE+ 0건, AMBER≤3틱 | 0/0 | 0/0 | PASS | (rank 1순위 완전 축퇴 — §3 참조) |
| 플래핑 | 양성≤6, 음성≤2 | 최대20 / 0 | 최대6 / 0 | server **FAIL** / mobile PASS | server: 상태기계 설정 모순(`or_any_crit`∧`exit_AMBER` 동시 영구 충족, 한계진동) — 전이 규칙 수정은 스윕 대상 ①~④ 밖. 게이트에서 명시적 축소(AD-5), 판정표엔 FAIL 유지 + GM0 안건 2 |
| 골든 무회귀 | 2케이스×2프로파일 | PASS | PASS | PASS | `uv run pytest backtest/test_golden.py -q` 6건 green(0.3.0-rc 반영 후 재확인) |
| 패리티(BT-05) | Python↔Kotlin | — | — | 미실행 | M1 소속, BT-03 범위 밖 |
| 해상도(참고) | severity 3 동시 고정 지표 수 | — | — | 참고 | BT-04 몫(F-06 대응안 3종 비교) |

**총평**: 6개 §6 항목 중 4개 PASS, 2개 FAIL(둘 다 원인이 스윕 대상 ①~④ 밖으로 실측
확인됨 — GM0 안건 2건으로 상신). AD-1(ii)에 따라 게이트를 느슨히 해 "성공"으로 위장하지
않고 FAIL 그대로 보고한다. 결과 라벨 `0.3.0-rc (조건부)`의 "조건부"는 이 2건을 가리킨다.

---

## 2. 창별 타임라인 (9창 전체, 0.3.0-rc 최종 확인 실행 — S5)

| 창 | 성격 | 홀드아웃 | server max_phase / 전이 / 최고composite / 첫ORANGE | mobile max_phase / 전이 / 최고composite / 첫ORANGE |
|---|---|---|---|---|
| w2011_us_downgrade | 양성 | | RED / 7 / 81.63 / 2011-08-05 | RED / 4 / 81.63 / 2011-08-03 |
| w2015_cny_deval | 양성 | **H** | RED / 25 / 76.19 / 2015-08-24 | RED / 4 / 76.19 / 2015-08-24 |
| w2018_q4_tightening | 양성 | | ORANGE / 14 / 65.38 / 2018-10-11 | RED / 5 / 60.54 / 2018-10-11 |
| w2020_covid | 양성 | | RED / 13 / 87.76 / 2020-02-25 | RED / 5 / 87.76 / 2020-02-25 |
| w2022_tightening | 양성 | | ORANGE / 10 / 53.74 / 2022-09-26 | ORANGE / 5 / 53.74 / 2022-09-27 |
| w2024_carry_unwind | 양성(골든) | | RED / 4 / 72.65 / 2024-08-05 | RED / 2 / 66.06 / 2024-08-05 |
| w2026_structural | 양성(F-02) | | AMBER / 20 / 35.15 / 미도달 | AMBER / 6 / 35.15 / 미도달 |
| w2024_05_calm | 음성(골든) | | GREEN / 0 / 0.00 / — | GREEN / 0 / 0.00 / — |
| w2023_11_rally | 음성 | **H** | GREEN / 0 / 28.48 / — | AMBER / 2 / 28.48 / — |

**H = 홀드아웃(R-03)**: 스윕 선정에는 사용되지 않았다(AD-6, "읽는 행위"가 아니라 "선정에
되먹이는 행위"만 금지). 위 두 행은 이번 실행에서 함께 산출된 값을 **참고용으로만** 기재한다
— 편향 없는 최종 판정은 BT-04의 홀드아웃 검증 몫이다. mobile `w2023_11_rally` 전이 2회는
§6 음성 상한(≤2) 이내이나, 이는 선정 근거로 쓰이지 않았고 BT-04에서 독립적으로 재확인해야
한다.

before/after 비교 그래프(mobile 플래핑, 0.2.0 기준선 vs 0.3.0-rc): **`backtest/reports/mobile_flapping_before_after.png`**

---

## 3. 스윕 표 — 단계별 평가·등가류·게이트·선정 근거

선정 규칙 고정 문언(BACKTEST_PLAN §BT-03): "홀드아웃 제외 7창에서 수용 기준(§6) 통과 조합 중
오탐 최소 → 리드타임 최대 순. 동률이면 단순한 값." 계량 정의는 `sweep.yaml` `selection`.

### 3.1 단계 실행 결과

| 단계 | 대상 | 평가 수 | golden 통과/탈락 | 선정서명 등가류 수 | 비고 |
|---|---|---|---|---|---|
| S1 usdkrw_thresholds | ① | 35 | 24 / 11 | **2**(전체summary 4종) | 탈락 11건 전건 사유: mobile w2024_carry_unwind 2024-08-01 composite 이탈(§8 실측과 일치) |
| S2 stale daily_us | ④ | 3 | 3 / 0 | **2**(전체summary 3종) | 3값 전부 golden PASS |
| S3 교차검증 | ①×④ | 4(top_k=2×2) | — | — | **agrees=True, 전수 승격(105조합) 불필요** — 차원 분리 가정이 검증됨(§5.3 논증과 실측 합치) |
| S4 mobile_daily_profile | ③ | 112(dedupe 후) | 56 / 56 | — | `promote_sustain=1` 56건 전건 통과 / `=2` 56건 전건 탈락(설계 §8과 정확히 일치) |
| S4 완전판 게이트(flapping 포함) | ③ | 56(golden통과분) | 통과 **12** | — | `sweep.yaml` T6 실측(12/56)과 일치 |
| S4 확정틱 하한 적용 후 | ③ | 12 | 선정가능 **6** | — | `confirm_time_kst < 16:50`(즉 16:20) 6건 기각(평가는 했으나 물리·스케줄 하한 미달) |
| S4 최종 rank | ③ | 6 | 승자 1 | — | fp(0,0) 동률 축퇴 → lead(mobile 12,44 / server 10,43) 동률(5/6) → 단순성으로 최종 결정 |
| S5 최종 확인 | 전체 | 9창×2프로파일 1회 | golden PASS | — | 홀드아웃 결과는 선정에 미반영(R-03) |
| **합계** | | **154 + 0 에스컬레이션** | | | 실측 wall time **250.4초**(§5.1 예산 추정 ~4.6분과 근접 — 예상보다 다소 큼, Python 오버헤드) |

### 3.2 게이트·rank 조작적 정의 (요지, `sweep.yaml` selection 전문 참조)

- **게이트**: 골든 하드제약(사전필터) ∧ detection(양성 5/5, `w2026_structural` 명시적 제외)
  ∧ leadtime(중앙값≥1) ∧ 오탐(ORANGE+0·AMBER≤3) ∧ 플래핑(양성≤6·음성≤2, **server는
  명시적 축소로 게이트 제외** — 근거 §9.2/AD-5: ①35조합 전수에서 server 양성창 최대
  전이수가 최솟값=최댓값=20으로 스윕 무감, 원인이 상태기계 설정 모순으로 스윕 대상 밖).
- **rank**: (1) 오탐 최소[음성 ORANGE+틱합, AMBER틱합] 오름차순 — **완전 축퇴**(전 후보
  (0,0), F-8: 음성 스윕 창 `w2024_05_calm`이 전 틱 composite 0.00이라 실질 선정은 rank
  2부터 결정된다) (2) 리드타임 최대[양성 lead 중앙값, lead합] 두 프로파일 평균 내림차순
  (3) 단순한 값[변경 파라미터 수 → 소수 자릿수 합 → 정규화 yaml 사전식].
- **S1/S2/S3 단계 내부 top_k 산정 방법론 노트(구현 판단, sweep.yaml에 명문화되지 않은
  부분)**: S1~S3는 ③(mobile_daily 프로파일)을 기준선(0.2.0)에 고정한 채 도는데, 기준선
  자체가 이미 §6 mobile 플래핑(양성≤6)을 위반한다(최대 7, §1 표). 즉 ③이 실제로 바뀌는
  S4 이전에는 플래핑 게이트가 전 후보에서 상시 FAIL이라 판별력이 없다. 이 구간의
  top_k/불일치 판정은 **완화 게이트**(detection∧leadtime∧오탐, 플래핑 제외)로 golden-pass
  후보를 추려 rank했다. **최종 선정(S4 winner)은 플래핑을 포함한 완전판 게이트로
  판정했다** — AD-1(ii) "게이트 전면 유지"는 최종 선정에서 그대로 지켜진다.

### 3.3 명시적 축소 2건 (F-2 패턴 — 항목을 없애지 않고 범위만 좁힘)

| 항목 | 축소 내용 | 근거 |
|---|---|---|
| detection | `w2026_structural` 1창을 `except_windows`로 명시 제외, 나머지 5양성창은 5/5 필수 유지 | 도달 불가가 그 1창에서만 실측 증명됨(§4) — 통째로 제외하면 다른 5창의 탐지 상실까지 무료 허용하는 과다 제거 |
| flapping | `server_intraday` 프로파일을 `except_profiles`로 명시 제외, mobile은 게이트 유지 | ① 35조합 전수에서 server 양성창 최대 전이수 최솟값=최댓값=20(스윕 무감) + 원인이 상태기계 설정 모순(스윕 대상 밖). mobile은 골든통과 56조합 중 12조합이 실제로 통과해 변별력 있음 |

---

## 4. F-04 (구조지표 2종) — 비활성 유지 (BT-03 공식 결론, 실행 0회)

`krx_halt_events`·`margin_leverage_stress`는 스윕을 **실행하지 않았다**(`sweep.yaml`
`execute: false`). 근거 4종(어느 하나만으로도 충분):

1. **픽스처 결손**: 9창 전부 `status: uncollected`(BT-01 collection plan이 krx_notice·
   krx_margin 제공자 제외).
2. **수집 경로 부재**: pykrx 공개 함수 90개 중 신용융자잔고·서킷브레이커 대응 함수 0개.
3. **하니스 미배선**: `enabled: true`로 바꾸면 `run_replay`가 `KeyError`로 즉시 실패
   (`_BUILDERS` 미등록).
4. **결정적**: 두 지표를 전 구간 crit(3)로 최대 가정해도 w2026 ORANGE 도달은 최대낙폭일
   당일(07-30, composite 42.11) — §6 "07-28 이전 도달"은 여전히 미충족.

**결론(D-13 + BACKTEST_PLAN 규율에 따름): 비활성 유지, C1 실측 후 재판정.** 그리드 구조는
`sweep.yaml`에 C1 재사용을 위해 보존한다.

---

## 5. 해상도(F-06) — w2026 갭 (참고, 판정 기준 아님)

창내 -38.63% 폭락(`w2026_structural`)에서 최고 composite 35.15로 ORANGE 임계(40)에 미달.
원인은 임계값이 아니라 **severity 포화**(`kospi_drawdown` crit 임계 7.0%가 38.63%와 동일하게
포화값 3을 냄) **+ z-score 자기정규화**(`vkospi_z`가 252일 롤링이라 폭락이 길수록 자기
표준편차가 커져 오히려 하락)다. 스윕 대상 ①③④ 전 영역 + ②(F-04) 최대 가정에서도 도달
불가가 실측 증명됐다(설계 §4). 정식 대응 경로는 BT-04의 해상도 대응안 3종(임계 사다리
확장/severity 4단계/RED 서브레벨) 비교 시뮬레이션이며, 채택은 사용자 승인 사항이다.

---

## 6. GM0 상신 안건 (BT-03 권한 밖, 판단 필요 — 상세는 설계 저널 §13)

1. **w2026 해상도 갭** — 위 §5. BT-03은 제안만 하고 채택은 사용자 승인 사항.
2. **`or_any_crit ⊕ exit_AMBER` 충돌** — `configs/statemachine.yaml`의
   `upgrade.AMBER{composite_gte:20, or_any_crit:true}`와
   `downgrade.exit_AMBER{composite_lt:14}`가 "저(低)composite + 단일 crit" 구간에서 동시
   영구 충족되어 한계진동을 일으킨다(상수 입력 100틱만으로 server 15건·mobile 49건 재현,
   데이터 무관 — 설정 성질). 프로덕션에서도 그대로 재현되므로 AMBER 알림이 주기적으로
   재발화할 수 있다. 수정 방향 3종(crit 지속 중 이탈 차단 / 이탈선 대칭화 / `or_any_crit`
   자체 축소)을 스케치만 하고 구현하지 않았다 — 채택 시 별도 서브태스크, 어느 방향이든
   골든 무회귀 재확인이 선행 조건.

---

## 7. 미결 (다음 행동)

| 항목 | 상태 | 다음 단계 |
|---|---|---|
| server 스테일 창(90분~) | 동결(스윕 미실행) | C1 실측 30분 데이터로 재판정 — 겨눌 일중 관측이 이 근사-PIT 하니스에는 없음(§9.2c) |
| 확정 틱 17:00 | 하니스 완전 무감, 물리·스케줄 논증으로만 결정 | M1 모바일 실제 확정 틱 설계와 동시 재확인(AD-3 b) |
| 홀드아웃 검증(w2015·w2023_11) | S5에서 참고용 산출만(선정 미반영) | BT-04에서 독립 검증 — R-1(과민화 위험) 최대 리스크의 실제 판정처 |
| server 플래핑(GM0 안건 2) | FAIL 잔존, 게이트 명시적 축소 | GM0 판단 대기 — 채택 시 전이 규칙 수정 + 골든 재확인 별도 서브태스크 |
| w2026 해상도 갭(GM0 안건 1) | FAIL 잔존, 구조적 도달 불가 | BT-04 해상도 대응안 3종 비교 시뮬레이션 |
| 패리티(BT-05) | 미실행 | M1 소속 |

---

## 8. 재현·검증

```bash
uv run ruff check . && uv run pytest -q                    # 153 green (기존 145 + 신규 8)
uv run pytest backtest/test_golden.py -q                    # 6 green (0.3.0-rc 반영 후)
uv run pytest backtest/test_run_sweep.py -q                 # 8 green — 하니스 갭·선정 로직 증인
uv run python backtest/run_sweep.py                          # 스윕 재실행 (~4분) -> results/sweep/sweep_result.json
uv run python backtest/plot_sweep.py                          # 그래프 재생성 -> reports/mobile_flapping_before_after.png
```

산출물:
- `backtest/results/sweep/sweep_result.json` — 전 단계 원자료(등가류·게이트·rank 근거 포함)
- `backtest/reports/mobile_flapping_before_after.png` — mobile 플래핑 before/after 그래프
- `backtest/results/metrics.json` — 0.3.0-rc 9창×2프로파일 기준선 재생성

---

# BT-04 — 성능·해상도 리포트 + F-06 대응 제안 (Stage B, MT0-06)

> **근사-PIT — C1에서 실측 확정.** 이 절의 모든 수치는 위 §0의 근사-PIT 고지(소급 수집치·
> server_intraday 30분 재현이 전 창 일봉 근사)를 그대로 상속한다. 추가로: 대응안 3종의
> 성능·골든 호환성 수치는 Stage A(설계 저널)의 **산술 투사**가 아니라 이 절 전체가
> `backtest/run_f06_variants.py`의 **실제 `engine_ref` 실행**이다(AD-7). 산술 투사와
> 갈리는 지점은 §BT4.5에 전부 기록한다.

- 작성일: 2026-08-03 · 소속: M0 / MT0-06 · 역할: backtest-analyst Worker 산출물(Stage B)
- 설계(Stage A): `docs/journal/2026-08-03_MT0-06_bt04_design.md`(5라운드, aaa-critic PASS
  종결) · 후보값/게이트 SSOT: `backtest/f06_variants.yaml`
- 실행기: `backtest/run_f06_variants.py`(그리드·게이트 리터럴 0개 — f06_variants.yaml만
  로드) · 실행 산출: `backtest/results/f06/f06_variants_result.json`
- **`configs/*.yaml`은 이 절 작성 과정에서 프로덕션 반영되지 않았다** — 전 실행은 샌드박스
  (임시 디렉터리 candidate configs)로만 이뤄졌고, `backtest/results/metrics.json`은
  실행 전후 SHA-256 바이트 동일(아래 §BT4.6 명령으로 재현 가능).
- **결론 선반영**: F-06 대응안 3종 중 이번 9창 표본에서 즉시 채택 가능한 것은 **없다**
  (§BT4.4). ①(임계 사다리 확장)은 mobile 플래핑 하드 게이트(AD-11)에서 3후보 전부
  탈락했다 — 이는 설계 결함이 아니라 AD-11이 사전에 명문화한 하드 게이트가 정확히
  작동한 사례다(AD-1(iv) 정직성 원칙 그대로 보고).

## BT4.1 §6 최종 판정표 (9창 전체, 홀드아웃 포함, 0.3.0-rc — Stage A §2.2 재확인)

`backtest/run_f06_variants.py`의 baseline 계산(0.3.0-rc, in-process, 저장소 무변경)이
설계 저널 §2.2의 수치와 소수점까지 정확히 일치함을 재확인했다(아래 표는 이 재확인의
산출).

| 항목 | 기준 | server_intraday | mobile_daily | 판정 | 원인 귀속 |
|---|---|---|---|---|---|
| 탐지율 | 6/6, 홀드아웃 포함 7/7 | 5/6, 홀드아웃 포함 **6/7** | 5/6, 홀드아웃 포함 **6/7** | **FAIL**(`w2026`만) | 해상도 갭(F-06, §BT4.2) — GM0 안건 1 |
| 리드타임 중앙값(비홀드아웃) | ≥1 | 10 | 12 | PASS | — |
| 리드타임 중앙값(홀드아웃 포함) | ≥1 | 7.0 | 7.5 | PASS | 홀드아웃 `w2015`(리드 0) 포함해도 기준 충족 |
| 리드타임(w2026) | 07-28 이전 | 미도달 | 미도달 | **FAIL** | 해상도 갭 — GM0 안건 1 |
| 오탐(홀드아웃 포함 2음성창) | ORANGE+ 0건, AMBER≤3틱 | ORANGE+ 0/0 · AMBER 0/0 → PASS | ORANGE+ 0/0 통과, **AMBER 0/18**(`w2023_11_rally`) → FAIL | server PASS / **mobile FAIL** | GM0 안건 3(신설, AD-8) |
| 플래핑(홀드아웃 포함 9창) | 양성≤6, 음성≤2 | 양성 최대 **25**(`w2015`) / 음성 최대 0 | 양성 최대 **6**(`w2026`, 여유 0) / 음성 최대 **2**(`w2023_11`, 여유 0) | server **FAIL** / mobile **PASS**(경계) | server: GM0 안건 2 |
| 골든 무회귀 | 2케이스×2프로파일 | PASS | PASS | PASS | `uv run pytest backtest/test_golden.py -q` 6건 green(이 절 작성 전후 재확인) |
| 패리티(BT-05) | Python↔Kotlin | — | — | 미실행 | M1 소속 |
| 해상도(F-06, 참고) | severity 3 동시 고정 지표 수 | — | — | 참고 | §BT4.2 |

이 표는 Stage A(설계 저널 §2.2)의 판정을 **변경하지 않는다** — Stage B는 대응안을
실행했을 뿐 프로덕션 configs를 바꾸지 않았으므로 0.3.0-rc 자체의 판정은 그대로다. 이
표를 여기 다시 신는 이유는 §BT4.3의 비교표가 이 baseline을 기준선으로 삼기 때문이다.

## BT4.2 F-06 해상도 분석 요약

전체 분석(53행 `n_crit` 시계열, composite 역전 사례, coverage 결측 원인)은 설계 저널
§2.4가 원본이다 — 여기서는 §BT4.3~4.5 해석에 필요한 결론만 재인용한다.

- **포화-과소(2026-07)**: `kospi_drawdown`(가중 2.5) severity가 07-01~07-31 22거래일
  연속으로 3(crit)에 고정된다. 그 사이 raw drawdown은 8.90%→38.63%로 4.3배 벌어지지만
  이 지표가 제공하는 정보량은 0이다. 같은 구간 `n_crit`(severity=3 지표 개수, 15종
  분모)은 1~2 사이에 머물러(창 최대 2/15), 다른 지표들의 비발화(국지적 발화 패턴)와
  결합해 composite를 40(ORANGE) 아래에 가둔다.
- **composite 역전**: 창 전체 최고 composite(35.15)는 최대낙폭일(07-30, dd=38.63%,
  composite=29.79)이 아니라 2026-06-08(dd=14.96%, composite=35.15)에서 나온다 —
  `global_corr_break`가 그 틱에서 우연히 crit을 찍은 데다 07-30은 07-17 이후 미국계
  2종(^VIX3M·^MOVE) 결측으로 coverage 자체가 낮다(0.758 vs 0.887).
- **포화-과다(CONSISTENCY_AUDIT 원 우려)**: 이 9창 실측에서 아직 관측되지 않았다 — 최고
  composite는 87.76(w2020_covid)이고 100 근처에 근접한 창이 없다.
- 세 대응안은 서로 다른 방향을 겨눈다: ①은 포화-과소(w2026 실측 문제) 방향, ③은
  포화-과다(감사 원 우려) 방향을 겨눈다. ②는 골든 재생성을 승인해도 w2026을 해결하지
  못하며 **오히려 악화시킨다**(이번 표본 실측 — w2026 최고 composite 35.15→**34.12로
  하락**, §BT4.3) — 골든 비호환은 채택을 막는 이유 중 하나일 뿐, 설령 그 장벽이
  없더라도 ②는 목표(w2026 조기 탐지)에 반대 방향으로 작용한다(§BT4.4).

## BT4.3 대응안 3종 비교 — 실측표 (AD-9(c), §4.2 열 양식)

### ① 임계 사다리 확장(`or_any_extreme`, ORANGE 승격 한정, AD-10) — 3후보 전부 실측

| 후보 | 골든 판정 | w2026 첫 ORANGE(mobile, 실측) | 07-28 이전 달성(server∧mobile) | 나머지 6창 손상 | 홀드아웃 음성창 신규 오탐 | **mobile 플래핑 판정(하드)** | server Δ전이수 | Kotlin 부담 |
|---|---|---|---|---|---|---|---|---|
| 16.0% | PASS | 2026-07-02 | mobile만 달성(server 미탐지) → **미달성** | 0/12 | 0 | **FAIL**(`w2026_structural` 6→**9**, 상한 6 초과) | 전 창 0 | moderate |
| 18.0% | PASS | 2026-07-08 | 〃 | 0/12 | 0 | **FAIL**(동일: 6→9) | 전 창 0 | moderate |
| 20.0% | PASS | 2026-07-08 | 〃 | 0/12 | 0 | **FAIL**(동일: 6→9) | 전 창 0 | moderate |

- **server_intraday는 3후보 전부 w2026을 전혀 탐지하지 못했다**(`first_orange_or_above_date
  = None`) — AD-10이 `distinct_axes_gte`를 이스케이프 대상에서 제외했기 때문에(§3-A(a)),
  raw drawdown이 extreme 임계를 넘는 server 틱에서 `distinct_axes>=2`가 동시 충족되지
  않는다. §6은 두 프로파일 모두 통과를 요구하므로, 플래핑 게이트가 없었어도 "달성"
  자체가 이미 성립하지 않았다.
- `w2011_us_downgrade`·`w2020_covid`(§3-A(b)가 발화 창으로 지목)는 **3후보 전부 mobile
  전이 수가 baseline과 완전히 동일**했다 — 이스케이프가 실제로 발화하지만(§3-A(b) 표의
  발화일 이후는 이미 정상 경로로 ORANGE 이상이라) 최종 타임라인에 영향을 주지 못한다는
  §3-A(b)의 가설이 실측으로 확인됐다.
- 랭킹(§4.3 규칙 1, 하드 게이트): **survivors = 0**. 2단계(목표 달성)·3단계(tie-break)
  적용 대상 자체가 없다.

### ② severity 4단계(`max_severity=4`, 20.0% 1후보, 계량 전용 — AD-7)

| 후보 | 골든 판정 | w2026 첫 ORANGE(양쪽) | 07-28 이전 달성 | **w2026 최고 composite(baseline→실측)** | mobile 플래핑 | server Δ전이수 | 비고 |
|---|---|---|---|---|---|---|---|
| 20.0% | **FAIL**(`mobile_w2024_carry_unwind_2024-07-25_composite`) | 미도달(양쪽) | — | **35.1515 → 34.1176**(양쪽 프로파일 동일, Δ**-1.034**) | 게이트 미적용(계량 전용, `applies_to` 부재) | 전 창 0(9창 전부) | 랭킹 대상 아님(§4.3-5) |

- **w2026 효과는 "미탐지"로만 요약하면 부정확하다 — 실제로는 악화다(S-1, aaa-critic
  Stage B 라운드 2).** `w2026_structural`의 최고 composite는 baseline **35.1515**
  (2026-06-08, dd=14.96%<20% — 그 틱에서 분자는 불변)에서 candidate **34.1176**으로
  **하락**한다(분모만 82.5→85.0으로 커진 결과). Stage A §3-D가 "②=해결(투사, 골든 재생성
  대가)"이라 적은 것에 대한 **정면 반증**이다 — ②는 골든 재생성을 승인해도 w2026을
  해결하지 못하고, 오히려 ORANGE 임계(40)에서 더 멀어지게 만든다.
- **골든 08-05 composite 델타**: 실측 **-1.8360**(62.42424... → 60.58824...) — 설계 저널
  §3-B(b)의 산술 투사(**-1.84**)와 소수 둘째 자리까지 일치한다.
- **산술과 실측이 갈리는 지점**: §3-B(b)는 08-05 틱 하나만 손계산했지만, 실제
  `golden_pass()`의 **최초** 이탈 지점은 08-05가 아니라 **2024-07-25**(`w2024_carry_unwind`
  창의 첫 틱)다 — 분모 재정의(`Σ(wᵢ·max_severity_i)`)가 `kospi_drawdown`이 유효값을
  갖는 **전 틱**에 적용되기 때문이다(공식 자체(§3-B(a))가 이미 내포한 사실이지만 Stage A는
  이 함의까지 계산하지 않았다). 자세한 목록은 §BT4.5.
- mobile 전이 수는 3창에서 변한다(`w2015_cny_deval` 4→5, `w2018_q4_tightening` 5→4,
  `w2026_structural` 6→4) — 다만 **server_intraday 전이 수는 9창 전부 delta=0**이다.
  composite 값 자체는 server에서도 실제로 달라진다(`w2026_structural`만 848틱 중 52틱
  차이 확인, §BT4.5) — 그 차이가 어느 창에서도 phase 전이 경계를 넘지 않았을 뿐이다.

### ③ RED 서브레벨(분기점 80.0, 표시 계층 파생값 — engine 무변경)

9창 전체 `phase=="RED"` 틱 624개(server+mobile 합산)에 대한 라벨 분포:

| 창 | RED-1 (심각, [60,80)) | RED-2 (재난, [80,100]) |
|---|---|---|
| w2011_us_downgrade | 83 | 2 |
| w2015_cny_deval | 115 | 0 |
| w2018_q4_tightening | 5 | 0 |
| w2020_covid | 335 | 42 |
| w2024_carry_unwind | 42 | 0 |
| w2022_tightening / w2026_structural | 0 (RED 미도달) | 0 |

골든 무회귀 무관 확인(라벨은 phase·composite·상태기계 어디에도 재입력되지 않는 순수
파생값, §3-C(c)) — `pytest backtest/test_golden.py` 6/6 green 유지. `w2026_structural`은
여전히 RED에 도달하지 못하므로(AMBER 최고) 이 안의 적용 대상이 아니다(§3-C(e) 한계
그대로 재확인).

## BT4.4 우열 판정 (§4.3 규칙 적용 결과)

1. **§4.3 규칙 1(하드 게이트) — ①은 3후보 전부 즉시 탈락.** `w2026_structural` mobile
   전이가 baseline에서 이미 §6 상한(6)과 정확히 같았다(여유 0, §2.3/AD-11(i)). `or_any_extreme`
   도입 후 3후보 전부 동일하게 9로 증가해 상한을 3틱 초과했다. 값(16/18/20%)과 무관하게
   같은 방식으로 실패한다 — 이스케이프 조건 자체(원값이 임계를 넘는 구간에서 반복
   발화)가 이 창에서 세 후보 모두 비슷한 패턴으로 나타나기 때문이다.
2. **구조 동형성 경고(AD-11(iv)/O-i)가 실측으로 확인됐다.** 설계 저널 §3-A(c)가 명시한
   문장을 그대로 인용한다:

   > `or_any_extreme ⊕ exit_ORANGE{composite_lt:32}`는 GM0 안건 2의 `or_any_crit ⊕
   > exit_AMBER{composite_lt:14}`와 형태가 같고 ... 이 병리가 실제로 나타나는지는 설계
   > 단계의 우려로 남을 필요 없이 Stage B의 하드 게이트가 자동으로 걸러낸다.

   실측 결과: 이스케이프 발화 → `w2026_structural` mobile 전이 6→9 → §4.1 하드 게이트
   즉시 발동 → 3후보 전부 탈락. **예측과 실측이 정확히 일치한다.** 이는 안건 2의 진단이
   특정 파라미터 조합의 우연이 아니라 "or 조건 진입 + composite 단독 조건 이탈" 짝짓기
   자체의 일반적 취약점이라는 독립 증거이며, GM0 안건 2에도 반영해야 한다(§BT4.5).
3. **②는 채택 후보가 아니다**(§4.3-5, AD-7) — 이중으로 확정됐다: (a) 계량 결과가 골든
   비호환(§3-B(b)의 결론을 실측이 재확인)을 그대로 확정했고, (b) **설령 골든 재생성을
   승인하더라도** ②는 w2026을 해결하지 못한다 — 목표 창의 최고 composite를 오히려
   35.15→34.12로 끌어내린다(S-1, §BT4.3). Stage A §3-D의 "②=해결(투사)" 판정은 이
   실측으로 **반증**됐다 — 골든 비호환은 ②를 막는 두 이유 중 하나일 뿐이다.
4. **③은 목표(w2026 조기 탐지)와 무관한 별도 산출물**이라 우열 비교 대상이 아니다 —
   RED 내부 세분류라는 자기 목적은 정상 작동한다(엔진 무변경, 골든 무관 확인).

**총 결론: 세 대응안 중 이번 9창 표본에서 즉시 채택 가능한 것은 없다.** ①이 목표
리드타임 자체는 크게 초과 달성했으나(mobile만, 13~17거래일 여유) AD-11이 사전에
명문화한 하드 게이트에 정확히 걸렸다 — 게이트가 느슨했다면 승인됐을 뻔한 후보를
걸러낸, 설계가 의도대로 작동한 사례다(AD-1(iv)). 랭킹 순위 자체는 공집합이다.

## BT4.5 산술 투사 vs 실측 — 차이 목록 (AD-9(e))

| # | Stage A 산술 투사(§3) | Stage B 실측 | 일치/차이 |
|---|---|---|---|
| 1 | ① w2026 최초 충족일: 16%→07-02, 18%/20%→07-08 | mobile_daily 실측 첫 ORANGE: 16%→07-02, 18%/20%→07-08 | **정확히 일치** |
| 2 | ① "이후 실제로 ORANGE에 머무는지는 산술로 확인 불가"(§3-A(c) 미결) | 머물지 않는다 — mobile 플래핑 하드 게이트 위반(6→9)이 그 증거 | **불확실성이 실측으로 해소됨**(부정적 방향) |
| 3 | ① server_intraday에 대한 산술 투사 자체가 없었음(§3-A(c) 각주: mobile 기준만 산출) | server는 3후보 전부 w2026을 전혀 탐지하지 못함 | **신규 정보**(산술이 다루지 않은 지점) |
| 4 | ② 골든 08-05 델타 -1.84(§3-B(b)) | 실측 -1.8360 | **소수 둘째 자리까지 일치** |
| 5 | ② §3-B(b)는 08-05 틱만 계산 — 최초 이탈 지점을 특정하지 않음 | `golden_pass()` 최초 FAIL 지점은 2024-07-25(창의 첫 틱) | **차이** — 분모 재정의가 창 전체에 걸린다는 공식의 함의(§3-B(a))를 §3-B(b)는 08-05 한 틱으로만 예시했다 |
| 6 | AD-11(iv)/O-i: "실제 발현하면 §4.1 하드 게이트가 자동으로 걸러낸다" | 정확히 그대로 발현·발동 | **정확히 일치** |
| 6a(S-1, 라운드 2) | §3-D "② = 해결(투사)" — severity 4단계가 w2026 문제를 해결한다는 판정 | **정면 반증**: w2026 최고 composite가 35.1515→**34.1176**으로 하락(06-08 틱, dd=14.96%<20%라 분자 불변·분모만 82.5→85.0). 미탐지일 뿐 아니라 목표 방향과 반대로 악화된다 | **차이(반증)** — §3-D 산술은 골든 비호환만 지적했지 w2026 자체에 대한 부정적 영향은 검토하지 않았다 |
| 7 | ① `w2011_us_downgrade`·`w2020_covid`의 타임라인 무손상 여부는 Stage B 확인 항목으로 남겨둠(§3-A(b)) | 3후보 전부 두 창의 mobile 전이 수가 baseline과 완전히 동일(무손상) | **확인**(가설과 정합) |
| 8 | ② server_intraday에 대한 영향은 산술로 다루지 않음 | composite 값은 실제로 달라지지만(`w2026_structural` 848틱 중 52틱) 9창 전부 전이 경계는 넘지 않음(delta=0) | **신규 정보** |

## BT4.6 GM0 상신 안건 갱신

1. **w2026 해상도 갭(기존, BT-03 §6 안건 1)** — **미해결로 유지.** F-06 대응안 3종 모두
   이번 표본에서 도입 불가로 확인됐다(①=하드 게이트 위반, ②=골든 비호환 **그리고 목표
   자체를 악화**(w2026 최고 composite 35.15→34.12, S-1 — 골든 재생성을 승인해도 채택할
   이유가 없다), ③=이 문제를 애초에 겨냥하지 않음). 다음 단계 옵션(권고, 결정은 사용자):
   (a) `or_any_extreme`을 AD-11(iv)이 예고한 대칭 히스테리시스(아래 안건 2)와 함께
   재설계해 재시도 — 안건 2의 채택 방향에 의존 (b) w2026을 §6 예외 창으로 유지한 채 C1
   실측에서 재평가 (c) `kospi_drawdown` 외 다른 지표(`usdkrw_z`·`vkospi_z`)로 유사
   이스케이프 확장 검토 — 이번엔 1개 지표만 시도했다. **(c)를 검토할 때 ②(severity
   4단계) 경로는 후보에서 제외한다** — 분모 재정의 방식 자체가 이번 실측에서 목표 창의
   composite를 낮추는 방향으로 작용함을 확인했으므로, 다른 지표에 동일 방식을 적용해도
   같은 역효과가 재현될 구조적 위험이 있다(분자가 그대로인 채 분모만 커지는 것은
   지표·창에 무관한 산식 자체의 성질이기 때문).
2. **`or_any_crit ⊕ exit_AMBER` 충돌(기존, BT-03 §6 안건 2)** — 미변경, 이월. **추가
   근거(BT-04 신규)**: §BT4.4-2가 인용한 구조 동형성이 이번 Stage B 실측에서 독립적으로
   재현됐다(`or_any_extreme ⊕ exit_ORANGE` 쌍이 동일한 병리를 `w2026_structural` mobile
   타임라인에서 실제로 만들어냄). 안건 2의 수정 방향 A(crit/extreme 지속 중 이탈 차단)가
   채택되면 두 이스케이프 모두에 동일한 히스테리시스 짝짓기를 적용해야 실효가 있다.
3. **mobile 오탐 FAIL — `w2023_11_rally` AMBER 18틱(신설, AD-8)** — §BT4.1에 FAIL
   verbatim으로 기록됐다(§6 오탐 기준 AMBER≤3틱 초과). §2.3(설계 저널) 확인대로 이
   AMBER 절대량은 0.2.0에서도 이미 §6을 초과하던 상태이며, 0.3.0-rc의 mobile 파라미터
   변경(min_dwell 2→5, reentry_cooldown 0→2)은 "전이 억제"를 겨눈 노브였지 "AMBER 진입
   억제"를 겨눈 노브가 아니었다. 처리 옵션(권고, 결정은 사용자):
   - (a) 0.3.0-rc 철회 → 0.2.0 롤백. 근거: 신규 §6 FAIL 발생. 단점: BT-03이 달성한 mobile
     플래핑 개선(양성≤6 달성)도 함께 잃는다.
   - (b) FAIL 명기 조건부 유지 — 위 §0 결과 라벨 "0.3.0-rc (조건부)"의 조건부 목록에 이
     항목을 추가. 단점: §6 판정표에 FAIL이 하나 더 늘어난 채로 M0 게이트를 통과시킨다.
   - (c) C1 실측 재보정으로 이관. 근거: 근사-PIT 하니스의 구조적 한계(K-11, D-04 "모든
     임계값은 가설"). 단점: 최종 판단이 지연된다.
   - **권고(비구속)**: (b)+(c) 병행 — 조건부 유지로 M0 진행은 계속하되 C1 첫 재보정
     대상에 이 창을 명시 포함한다. (a)는 BT-03의 실측 개선까지 함께 버리므로 최후
     수단으로 남긴다. **AD-8 정직성 조항(R-8) 재확인**: 이 FAIL을 지우기 위한 재보정·
     재스윕은 BT-04·Stage B 어느 단계에서도 하지 않았다 — 홀드아웃 성적으로 파라미터를
     되돌리는 R-03 위반을 피하기 위함이다.

## BT4.7 재현·검증

```bash
uv run ruff check . && uv run pytest -q                        # 173 green (기존 153 + 신규 20)
uv run pytest backtest/test_golden.py -q                        # 6 green (Stage B 실행 전후 불변)
uv run pytest backtest/test_f06_variants.py -q                  # 10 green — Stage B 하니스 갭·증인
uv run python backtest/run_f06_variants.py                       # ~15초 -> results/f06/f06_variants_result.json
```

산출물:
- `backtest/results/f06/f06_variants_result.json` — 대응안 3종 실측 원자료(baseline·
  variant_a·variant_b·variant_c)
- `backtest/results/metrics.json` — **불변**(SHA-256 실행 전후 동일, 스크립트 자체가
  검증해 종료 코드로 확인)

---

# MT0-07 — 이스케이프-이탈 짝지음 (D-26, Stage B)

> **근사-PIT — C1에서 실측 확정.** 이 절도 위 §0의 고지를 그대로 상속한다. 설계
> (Stage A, aaa-critic 2라운드 PASS): `docs/journal/2026-08-03_MT0-07_pairing_design.md`
> — 레벨-로컬 짝지음(reset 스트릭 정책), configs 키 신설 없음(엔진 의미론 확정,
> `engine_ref/statemachine.py` D-25 §4), AD-12(상한 이월)·AD-13(`metrics.json`
> 재생성 사전 승인) 반영.

## MT0-07.1 프로덕션 반영 요약

`engine_ref/statemachine.py`에 D-25 §4(D-26 짝지음)를 구현했다 — `configs/
statemachine.yaml`·`configs/indicators.yaml`은 **무변경**(값 복제 0, 새 스키마 키
0개). 레벨 L의 upgrade 규칙에 `or_any_*` 키가 있으면 그 입력이 참인 동안 `exit_L`은
그 자신의 조건과 무관하게 미충족으로 취급되고(레벨-로컬 — 다른 레벨 이탈에 영향
없음), 강등 스트릭은 "이탈 조건이 거짓인 틱"과 동일한 코드 경로로 리셋된다(reset
정책). `upgrade.RED`엔 `or_any_*` 키가 없어 `exit_RED`는 이 결정의 영향을 받지
않는다. 신규 증인 3종(설계 저널 §7.1 (a)(b)(c)) 포함 `tests/test_engine_ref.py`
176 green, `pytest backtest/test_golden.py -q` 6 green(반영 전후 모두 재확인).
기존 `test_f2_2_...`(cooldown 스트릭 리셋 F2 뮤테이션 증인)는 pairing 도입으로
원 구성이 무력화돼 합성 `StatemachineConfig`로 재구성했다(SB-1, 설계 저널 §9) —
F2 뮤테이션 7종 재사멸 확인(7/7 KILLED).

## MT0-07.2 `backtest/results/metrics.json` 재생성 (AD-13)

AD-13 조건 3건 전부 충족:
1. **note 출처 명기** — 재생성본 `note` 필드: "근사-PIT — C1에서 실측 확정
   (docs/BACKTEST_PLAN.md §5). registry 0.3.0-rc configs 불변 + D-26 pairing
   semantics 적용(MT0-07, AD-13)." (`backtest/run_replay.py`의 하드코딩 문자열을
   이 문구로 갱신 — D-26 짝지음은 이제 엔진의 영구 거동이므로 앞으로의 모든
   재생성에도 이 출처가 자동으로 붙는다.)
2. **재생성 전후 §6 지표 diff 표** — 아래.
3. **골든 전후 green** — 재생성 **전**(구 `metrics.json`, pairing 반영 직후 코드
   기준) `pytest backtest/test_golden.py -q` 6 green 확인 → 재생성 **후** 동일
   6 green 재확인(둘 다 이 세션에서 직접 실행).

`registry_version`은 AD-13 결정대로 **0.3.0-rc 그대로**(configs 무변경이므로
스탬프를 올릴 근거가 없다) — 의미론 변경은 `note`로만 구분한다.

### §6 재판정 diff (Stage A §5 예측 vs Stage B 실측 — 9창, 홀드아웃 포함)

| 항목 | 재생성 전(pairing 반영 전 엔진 기준값) | 재생성 후(pairing 적용) | Stage A §5 예측 | 일치 여부 |
|---|---|---|---|---|
| server 플래핑(양성 최대) | 25(`w2015`) | **13**(`w2020`) | 13 | **정확히 일치** |
| server 플래핑(음성 최대) | 0 | 0 | 0 | 일치 |
| mobile 플래핑(양성 최대) | 6(`w2026`, 여유 0) | **5** | 5 | **정확히 일치** |
| mobile 플래핑(음성 최대) | 2(`w2023_11`) | 2 | 2 | 일치 |
| mobile 오탐(`w2023_11_rally` AMBER틱) | 18(FAIL, 상한 3) | **18**(불변) | 18(불변) | **정확히 일치** — 짝지음이 이 창의 AMBER 체류를 늘리지도 줄이지도 않음(재확인) |
| 탐지율(양성, `w2026` 제외) | 6/6 두 프로파일 | 6/6 두 프로파일 | 불변 | 일치 |
| 리드타임 중앙값 | server 7.0 / mobile 7.5 | server 7.0 / mobile 7.5 | 불변 | **완전 일치** |
| 골든 무회귀 | PASS | PASS | 무손상 | 일치 |

**server 플래핑은 25→13으로 개선됐으나 여전히 §6 FAIL이다** — "해소됐다"고 서술하지
않는다(AD-1 iv 정직성 조항). 원인 귀속(설정 모순, MT0-05 §9.2b)이 부분적으로만
해소됐고(플래핑 방식 자체는 여전히 §6 상한 6을 넘는다), GM0 안건 2(server 플래핑
게이트 명시적 축소)는 그대로 유지된다.

## MT0-07.3 ① 변형(`or_any_extreme` + 짝지음) 재시뮬 — 설계 저널 §6.3 프로토콜 실행

`backtest/f06_variants.yaml` `selection` 블록(그리드·게이트·랭킹 SSOT, 무수정)을
그대로 승계해 `backtest/run_f06_variants.py`를 **재실행**했다(스크립트 자체는
무변경 — 엔진에 짝지음이 이미 영구 반영됐으므로 재실행 자체가 "① + 짝지음" 조건이
된다). 결과(3후보 16.0/18.0/20.0%):

| 후보 | 골든 | mobile 플래핑(하드 게이트, ≤6) | w2026 mobile 첫 ORANGE | w2026 server 첫 ORANGE | w2026 달성(양쪽) | 나머지 6창 손상 | 홀드아웃 신규 오탐 | server Δ전이(전 창) |
|---|---|---|---|---|---|---|---|---|
| 16.0% | PASS | **PASS**(5, 이전 9→FAIL이었음) | 2026-07-02 | **None(미탐지)** | **미달성** | 0/12 | 0 | 전 창 0 |
| 18.0% | PASS | **PASS**(5) | 2026-07-08 | **None** | **미달성** | 0/12 | 0 | 전 창 0 |
| 20.0% | PASS | **PASS**(5) | 2026-07-08 | **None** | **미달성** | 0/12 | 0 | 전 창 0 |

**랭킹(§4.3 규칙 1, 하드 게이트)**: **생존자 = 3/3**(BT-04 원 결과 0/3에서 반전 —
AD-11(iv)/O-i 구조 동형성 경고가 예고한 병리를 짝지음이 실제로 없앤 것이 실측으로
확인된다). 2단계(w2026 목표 달성) 기준으로는 **3후보 전부 미달성**이다 — mobile은
달성하지만 `server_intraday`가 여전히 `w2026_structural`을 전혀 탐지하지 못한다
(`distinct_axes_gte: 2`가 AD-10에 따라 `or_any_extreme`의 우회 대상이 아니기 때문 —
설계 저널 §6.3(3)이 예고한 정확한 지점). §4.3 3단계(tie-break)까지 내려가도 세 후보의
`other_6_positive_windows_damage`(0)·`holdout_negative_new_false_positive`(0)가
동률이라 `kotlin_parity_burden`(전부 `moderate`, f06_variants.yaml 동일 값)까지도
동률 — **3후보가 완전히 동점**이다.

**server distinct_axes 미탐지 문제**: 설계 저널 §6.3(3)이 명시한 대로 이번 재시뮬은
이 문제를 **측정만** 하고 해소를 시도하지 않았다(스코프 확대 금지). 대안(`or_any_
extreme`도 `distinct_axes_gte`를 우회하도록 확장)은 AD-10이 의도적으로 막은 안전장치
(단일 축 급변만으로 ORANGE 승격되는 경로)를 여는 것이라 이 재시뮬 범위 밖이며,
채택하려면 AD-10 자체의 재검토라는 별도 결정이 필요하다 — 제안으로만 기록한다.

**결론(정직 보고, AD-1 iv)**: 짝지음은 ①의 **플래핑 하드 게이트 탈락**(BT-04의
유일한 실격 사유)을 완전히 해소했다. 그러나 ①의 원래 목표(w2026 조기 탐지, 양
프로파일)는 **여전히 미달성**이다 — server의 distinct_axes 미탐지라는 별개 문제가
남아 있기 때문이다. **① 변형의 프로덕션 채택 여부는 이 재시뮬로 결정되지 않는다 —
사용자의 별도 결정 사항이다**(BACKTEST_PLAN §BT-04 "임의 구현 금지" 원칙, 설계
저널 §6.1). 채택 시에도 server 문제 해소 없이는 §6("두 프로파일 모두 통과") 자체를
충족하지 못한다.

## MT0-07.4 재현·검증

```bash
uv run ruff check . && uv run pytest -q                        # 176 green (기존 173 + D-26 증인 3)
uv run pytest backtest/test_golden.py -q                        # 6 green (pairing 반영 전후 모두 재확인)
uv run python backtest/run_replay.py --profile both --window all  # metrics.json 재생성(AD-13)
uv run python backtest/run_f06_variants.py                       # ① 재시뮬(샌드박스, metrics.json 불변 재확인)
```

산출물:
- `backtest/results/metrics.json` — **재생성됨**(AD-13 사전 승인, §MT0-07.2). 이전
  버전과 SHA-256 다름(의도된 변경) — `note` 필드로 사유 명시.
- `backtest/results/f06/f06_variants_result.json` — ① 재시뮬 갱신(랭킹 생존자
  0→3, w2026 미달성은 유지).
- `engine_ref/statemachine.py`·`tests/test_engine_ref.py`·`backtest/run_replay.py` —
  코드 변경분(D-26 짝지음 구현 + 증인 3종 + note 문구).

---

# MT0-08 — ① 변형 프로덕션 채택 (2026-08-04 사용자 결정)

> **근사-PIT — C1에서 실측 확정.** 이 절도 §0 고지를 상속한다. 근거: GATE_GM0 후속
> 결정(2026-08-04, ① 변형 20.0% 채택·C1 재확정 조건부) → `TASK_mobile_m0.md`
> §MT0-08. MT0-06/BT-04(설계·실측)·MT0-07(D-26 짝지음 프로덕션 반영)의 결과 위에서
> `kospi_drawdown` extreme 임계를 처음으로 **configs에 실반영**한다 — 이전까지는
> 전부 샌드박스 후보였다.

## MT0-08.1 반영 요약

- `configs/indicators.yaml`: `kospi_drawdown.thresholds.extreme: 20.0` 추가(보수
  후보 — 골든 안전 상한 15.557%보다 큼). `registry_version: 0.3.0-rc → 0.3.1-rc`.
- `configs/statemachine.yaml`: `upgrade.ORANGE.or_any_extreme: true` 추가. **D-26
  짝지음은 엔진 의미론(`engine_ref/statemachine.py` D-25 §4)으로 자동 적용** —
  configs에 짝지음 전용 키를 별도로 두지 않는다(MT0-07 §3 결정 그대로 승계).
- 스키마 가드: `tests/test_configs_schema.py::test_mt0_08_variant_a_adoption_reflected_in_ssot`
  신설(registry_version·extreme 값·or_any_extreme 위치를 SSOT에서 직접 확인, AMBER/RED엔
  없음을 AD-10대로 재확인).
- 기존 5개 테스트(`kospi_drawdown`/`ORANGE`가 이 값을 **아직 갖지 않는다**는 전제로
  작성됐던 것들)를 채택 사실에 맞춰 갱신했다 — 재조정이 아니라 전제 자체가 바뀐
  것을 반영: `test_classify_severity_extreme_key_ignored_at_default_max_severity`
  (실제 프로덕션 값 20.0으로 재작성), `test_is_extreme_absent_key_always_false`
  (대상 지표를 `vix_level_z`로 교체 — "부재" 경로는 여전히 다른 지표로 유효),
  `test_or_any_extreme_present_in_production_orange_escapes_composite_gate`(구
  "부재 시 noop" 자리 — 이제 반대 방향인 "실재 시 실제로 작동"을 프로덕션 config로
  직접 확인), `backtest/test_f06_variants.py`의 deepcopy 격리 증인 2건(값 비교 →
  객체 동일성 비교로 전환, BASE가 이미 후보와 같은 값을 갖게 됐으므로).
- `uv run ruff check . && uv run pytest -q` **177 green**(기존 176 + 신규 스키마
  가드 1), `pytest backtest/test_golden.py -q` **6 green**(반영 전후 모두 재확인 —
  D-08 골든은 예측대로 무손상, §MT0-08.2).

## MT0-08.2 골든 무회귀

반영 **전**(0.3.0-rc, D-26 짝지음만 있던 상태) 6 green, 반영 **후**(0.3.1-rc, ①
포함) 6 green — 위반 0. 예측 근거 그대로 성립: `extreme: 20.0`은 골든 양성 창
(`w2024_carry_unwind`)의 `kospi_drawdown` 관측 최댓값(15.557%, 2024-08-05)보다
크므로 골든 구간 내에서 이스케이프 자체가 발화하지 않는다(MT0-06 §3-A(b) 안전
조건). 중단·보고 조건(예상 밖 골든 위반)은 발생하지 않았다.

## MT0-08.3 `metrics.json` 재생성 — §6 판정표 갱신 (AD-13 절차 준용)

`note` 필드: "registry 0.3.1-rc(① 변형 채택, GATE_GM0 후속 결정 2026-08-04, MT0-08)
+ D-26 pairing semantics 적용(MT0-07, AD-13 절차 준용)."(`backtest/run_replay.py`
하드코딩 문자열 갱신 — registry_version 자체는 `configs/indicators.yaml`에서
동적으로 읽으므로 무변경).

### 재생성 전후 §6 diff (예상 vs 실측)

| 항목 | 반영 전(0.3.0-rc+D-26) | 반영 후(0.3.1-rc, ①) | MT0-07 재시뮬 예상(§MT0-07.3) | 일치 |
|---|---|---|---|---|
| mobile 탐지율(9창 양성 7/7) | 6/7(w2026 FAIL) | **7/7 PASS** | 7/7 전환 | **정확히 일치** |
| mobile `w2026` 첫 ORANGE | 미도달 | **2026-07-08** | 07-08(18/20% 후보 실측값) | **정확히 일치** |
| mobile `w2026` 전이수 | — | **5**(하드 게이트 ≤6 여유 1) | 5 | **정확히 일치** |
| mobile 플래핑(양성 최대, 9창) | 5 | 5(불변, `w2026`이 최대는 아님) | — | — |
| server `w2026` 탐지 | FAIL(None) | **FAIL(None) 그대로** | 변화 0 | **정확히 일치** |
| server `w2026` 전이수 | 8 | **8**(Δ=0) | server 측 변화 0 | **정확히 일치** |
| server 플래핑(양성 최대) | 13(`w2020`) | 13(불변) | 변화 0 | 일치 |
| mobile 오탐(`w2023_11_rally` AMBER틱) | 18(FAIL) | **18**(불변) | — | 일치 — 정직 보고, 지우지 않음 |
| 리드타임 중앙값(mobile) | 7.5 | **12**(`w2026`의 큰 리드가 표본에 편입) | — | 신규 정보(중앙값 계산 표본 변화) |
| 골든 무회귀 | PASS | PASS | 무손상 | 일치 |

**최종 §6 판정표 (verbatim, C1 이관 대상 명기)**:

| 항목 | server_intraday | mobile_daily | 판정 |
|---|---|---|---|
| 탐지율(9창 양성 7/7) | 6/7 (`w2026` 미탐지) | **7/7 PASS** | server **FAIL**(distinct_axes_gte 미면제, AD-10) / mobile **PASS** |
| 플래핑(양성≤6/음성≤2) | 양성 최대 13(`w2020`) → **FAIL** | 양성 최대 5·음성 최대 2 → PASS | server FAIL 잔존(원인: 상태기계 설정 모순, GM0 안건 2 — 스윕 대상 밖) |
| 오탐(홀드아웃 포함, AMBER≤3틱) | 0 → PASS | `w2023_11_rally` AMBER 18 → **FAIL** | mobile FAIL 잔존(AD-8, 재보정 금지) |
| 골든 무회귀 | PASS | PASS | PASS |

**server 탐지 FAIL의 원인은 ①로 해소되지 않는다** — `or_any_extreme`이 `distinct_axes_gte`
를 우회하지 않으므로(AD-10, 단일 축 급변만으로 ORANGE 승격되는 것을 막는 의도적
안전장치) server 그리드에서 raw drawdown이 extreme을 넘는 순간에도 `distinct_axes>=2`가
동시 충족되지 않으면 진입하지 못한다. 이 문제와 server 플래핑·mobile 오탐 FAIL
3건 전부 **C1 실측(30분 데이터)으로 이관**한다 — 근사-PIT 9창 표본으로는 추가
보정 근거가 없다(AD-8 정직성 조항, K-11).

## MT0-08.4 run_f06_variants 해석 주석

`backtest/run_f06_variants.py`를 재실행하면 A 후보 20.0%는 이제 "프로덕션 vs
프로덕션" 비교가 된다(baseline 자체가 이미 20.0%를 포함) — `other_6_positive_
windows_damage=0`·`holdout_negative_new_false_positive=0`·`server_delta_
transitions` 전 창 0이 **변형 효과가 아니라 항등 재확인**이다. 16.0/18.0% 두
후보만 실제 대조(프로덕션과 다른 임계값) 의미가 있으며, C1 재보정 시 이 두
후보 + 20.0%를 다시 비교 기준으로 쓴다(그리드 무변경, `backtest/f06_variants.yaml`
meta에 채택 사실만 부기).

## MT0-08.5 재현·검증

```bash
uv run ruff check . && uv run pytest -q                          # 177 green (기존 176 + 스키마 가드 1)
uv run pytest backtest/test_golden.py -q                          # 6 green (반영 전후 모두 재확인)
uv run python backtest/run_replay.py --profile both --window all  # metrics.json 재생성(AD-13 절차 준용)
uv run python backtest/run_f06_variants.py                        # 해석 주석 포함 재확인, metrics.json 불변
```

산출물:
- `configs/indicators.yaml`·`configs/statemachine.yaml` — ① 변형 값 실반영(SSOT).
- `backtest/results/metrics.json` — **재생성됨**(registry 0.3.1-rc). SHA-256
  변경(의도된 변경, `note`로 사유 명시).
- `backtest/results/f06/f06_variants_result.json` — 20.0% 후보가 baseline과
  동일해지는 점 반영해 재생성(§MT0-08.4).
- `tests/test_configs_schema.py`·`tests/test_engine_ref.py`·`backtest/test_f06_variants.py`
  — 신규 가드 1 + 전제 갱신 5.
- `backtest/f06_variants.yaml` — meta에 채택 사실 부기(candidate_grid 등 값 무변경).
