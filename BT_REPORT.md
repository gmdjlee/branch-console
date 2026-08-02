# BT_REPORT — 백테스트 하니스 결과 (BT-02·BT-03)

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
