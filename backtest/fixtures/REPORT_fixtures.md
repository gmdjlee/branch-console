# BT-01 Fixture Collection Report

근사-PIT — C1에서 실측 확정 (BACKTEST_PLAN.md §5). FRED lag_days 등 as-of 지연 적용은 리플레이(MT0-04)로 이연 — 이 리포트는 관측일(as_of) 기준 원자료 커버리지만 다룬다.
실수집 이력·결함 재현 절차: docs/journal/2026-08-02_MT0-03_fixture_collection.md
지표 레지스트리 버전: 0.2.0 (SSOT: configs/indicators.yaml)

## 0. 규칙 범례

- 기준 세션(missing_rate 분모)은 exchange_calendars 등 외부 달력이 아니라, 같은 calendar_kind(krx/us_market/fred/fx) 소속 전 계열이 평가구간 내 실제 반환한 관측일의 합집합이다 — 경험적 기준. 외부 달력 패키지의 휴장일 드리프트가 유령 결측으로 둔갑하는 경로를 원천 차단한다.
- missing_rate = 1 − |합집합 ∩ 계열 관측일| / |합집합|.
- 지표 가용도 = min(1 − missing_rate)(그 지표가 쓰는 계열들 중 최악값). 축 커버리지 = Σ(weight × 가용도) / Σweight — 비례 배분이며 이진(all-or-nothing) 판정이 아니다(SSOT: configs/indicators.yaml weight).
- kind 내 전 계열이 평가구간 내 공백이면(예: KRX 자격증명 미설정) 합집합 자체가 비어 기준 세션이 없다 — 이 경우 그 kind 소속 전 계열은 결측 100%로 계상한다 (F3-1: 빈 합집합을 결측 0%로 두면 데이터 손실이 클수록 커버리지가 오르는 비단조 오류가 된다).
- 형제 계열이 관측을 발행한 날에 특정 계열만 미발행이면, 그 계열은 그 날 결측으로 계상된다(보수적 편향 — 형제가 없으면 애초에 그 날이 기준 세션에 들어오지 않는다).
- fx(KRW=X)처럼 kind에 계열이 하나뿐이면 합집합=자기 자신이라, 관측이 1건이라도 있으면 missing_rate는 0이다 — 관측이 전혀 없으면(전멸) 위 규칙대로 100%로 잡히고, 그 사실은 advisory에도 별도로 표기된다. 대신 head/tail 절단·내부 평일(주 5일 기준) 공백은 "독립 기준 없음(단일 계열)" advisory로만 §4에 표기한다(비율 미반영).

## 1. 창별 평가구간

| window_id | 정의(start~end) | anchor_hint | 평가구간(eval) | 비고 |
|---|---|---|---|---|
| w2011_us_downgrade | 2011-07-15~2011-09-15 | 2011-08-08 | 2011-07-15~2011-09-15 |  |
| w2015_cny_deval | 2015-07-15~2015-09-15 | 2015-08-24 | 2015-07-15~2015-09-15 |  |
| w2018_q4_tightening | 2018-09-15~2018-12-31 | 2018-10-29 | 2018-09-15~2018-12-31 |  |
| w2020_covid | 2020-01-15~2020-04-15 | 2020-03-19 | 2020-01-15~2020-04-15 |  |
| w2022_tightening | 2022-08-01~2022-11-30 | 2022-09-30 | 2022-08-01~2022-11-30 |  |
| w2023_11_rally | 2023-11-01~2023-11-30 | - | 2023-11-01~2023-11-30 |  |
| w2024_05_calm | 2024-05-13~2024-05-24 | - | 2024-05-13~2024-05-24 |  |
| w2024_carry_unwind | 2024-07-25~2024-08-09 | 2024-08-05 | 2024-07-25~2024-08-09 |  |
| w2026_structural | 2026-05-15~2026-08-15 | 2026-07-28 | 2026-05-15~2026-08-02 | 정의상 종료일이 오늘 기준으로 클램프됨 |

## 2. 창×계열 커버리지 (평가구간 기준, padding 제외)

| window_id | series_id | status | reason | missing_rate | last_as_of |
|---|---|---|---|---|---|
| w2011_us_downgrade | BAMLH0A0HYM2 | empty | external_data_limit | 100.00% | - |
| w2011_us_downgrade | DX-Y.NYB | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | KRW=X | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | KRX:1001 | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2011_us_downgrade | KRX:investor_foreign_kospi | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | T10Y2Y | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | ^GSPC | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | ^MOVE | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | ^VIX | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | ^VIX3M | ok |  | 0.00% | 2011-09-15 |
| w2011_us_downgrade | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2011_us_downgrade | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2011_us_downgrade | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2011_us_downgrade | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2011_us_downgrade | news_novelty | uncollected | disabled | 100.00% | - |
| w2011_us_downgrade | news_volume_z | uncollected | disabled | 100.00% | - |
| w2015_cny_deval | BAMLH0A0HYM2 | empty | external_data_limit | 100.00% | - |
| w2015_cny_deval | DX-Y.NYB | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | KRW=X | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | KRX:1001 | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2015_cny_deval | KRX:investor_foreign_kospi | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | T10Y2Y | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | ^GSPC | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | ^MOVE | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | ^VIX | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | ^VIX3M | ok |  | 0.00% | 2015-09-15 |
| w2015_cny_deval | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2015_cny_deval | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2015_cny_deval | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2015_cny_deval | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2015_cny_deval | news_novelty | uncollected | disabled | 100.00% | - |
| w2015_cny_deval | news_volume_z | uncollected | disabled | 100.00% | - |
| w2018_q4_tightening | BAMLH0A0HYM2 | empty | external_data_limit | 100.00% | - |
| w2018_q4_tightening | DX-Y.NYB | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | KRW=X | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | KRX:1001 | ok |  | 0.00% | 2018-12-28 |
| w2018_q4_tightening | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2018_q4_tightening | KRX:investor_foreign_kospi | ok |  | 0.00% | 2018-12-28 |
| w2018_q4_tightening | T10Y2Y | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | ^GSPC | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | ^MOVE | ok |  | 5.48% | 2018-12-31 |
| w2018_q4_tightening | ^VIX | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | ^VIX3M | ok |  | 0.00% | 2018-12-31 |
| w2018_q4_tightening | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2018_q4_tightening | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2018_q4_tightening | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2018_q4_tightening | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2018_q4_tightening | news_novelty | uncollected | disabled | 100.00% | - |
| w2018_q4_tightening | news_volume_z | uncollected | disabled | 100.00% | - |
| w2020_covid | BAMLH0A0HYM2 | empty | external_data_limit | 100.00% | - |
| w2020_covid | DX-Y.NYB | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | KRW=X | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | KRX:1001 | ok |  | 0.00% | 2020-04-14 |
| w2020_covid | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2020_covid | KRX:investor_foreign_kospi | ok |  | 0.00% | 2020-04-14 |
| w2020_covid | T10Y2Y | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | ^GSPC | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | ^MOVE | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | ^VIX | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | ^VIX3M | ok |  | 0.00% | 2020-04-15 |
| w2020_covid | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2020_covid | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2020_covid | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2020_covid | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2020_covid | news_novelty | uncollected | disabled | 100.00% | - |
| w2020_covid | news_volume_z | uncollected | disabled | 100.00% | - |
| w2022_tightening | BAMLH0A0HYM2 | empty | external_data_limit | 100.00% | - |
| w2022_tightening | DX-Y.NYB | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | KRW=X | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | KRX:1001 | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2022_tightening | KRX:investor_foreign_kospi | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | T10Y2Y | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | ^GSPC | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | ^MOVE | ok |  | 2.33% | 2022-11-30 |
| w2022_tightening | ^VIX | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | ^VIX3M | ok |  | 0.00% | 2022-11-30 |
| w2022_tightening | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2022_tightening | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2022_tightening | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2022_tightening | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2022_tightening | news_novelty | uncollected | disabled | 100.00% | - |
| w2022_tightening | news_volume_z | uncollected | disabled | 100.00% | - |
| w2023_11_rally | BAMLH0A0HYM2 | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | DX-Y.NYB | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | KRW=X | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | KRX:1001 | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2023_11_rally | KRX:investor_foreign_kospi | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | T10Y2Y | ok |  | 4.55% | 2023-11-30 |
| w2023_11_rally | ^GSPC | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | ^MOVE | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | ^VIX | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | ^VIX3M | ok |  | 0.00% | 2023-11-30 |
| w2023_11_rally | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2023_11_rally | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2023_11_rally | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2023_11_rally | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2023_11_rally | news_novelty | uncollected | disabled | 100.00% | - |
| w2023_11_rally | news_volume_z | uncollected | disabled | 100.00% | - |
| w2024_05_calm | BAMLH0A0HYM2 | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | DX-Y.NYB | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | KRW=X | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | KRX:1001 | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2024_05_calm | KRX:investor_foreign_kospi | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | T10Y2Y | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | ^GSPC | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | ^MOVE | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | ^VIX | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | ^VIX3M | ok |  | 0.00% | 2024-05-24 |
| w2024_05_calm | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2024_05_calm | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2024_05_calm | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2024_05_calm | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2024_05_calm | news_novelty | uncollected | disabled | 100.00% | - |
| w2024_05_calm | news_volume_z | uncollected | disabled | 100.00% | - |
| w2024_carry_unwind | BAMLH0A0HYM2 | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | DX-Y.NYB | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | KRW=X | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | KRX:1001 | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2024_carry_unwind | KRX:investor_foreign_kospi | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | T10Y2Y | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | ^GSPC | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | ^MOVE | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | ^VIX | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | ^VIX3M | ok |  | 0.00% | 2024-08-09 |
| w2024_carry_unwind | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2024_carry_unwind | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2024_carry_unwind | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2024_carry_unwind | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2024_carry_unwind | news_novelty | uncollected | disabled | 100.00% | - |
| w2024_carry_unwind | news_volume_z | uncollected | disabled | 100.00% | - |
| w2026_structural | BAMLH0A0HYM2 | ok |  | 1.75% | 2026-07-30 |
| w2026_structural | DX-Y.NYB | ok |  | 1.85% | 2026-07-31 |
| w2026_structural | KRW=X | ok |  | 0.00% | 2026-08-02 |
| w2026_structural | KRX:1001 | ok |  | 0.00% | 2026-07-31 |
| w2026_structural | KRX:VKOSPI | error | external_data_limit | 100.00% | - |
| w2026_structural | KRX:investor_foreign_kospi | ok |  | 0.00% | 2026-07-31 |
| w2026_structural | T10Y2Y | ok |  | 7.02% | 2026-07-31 |
| w2026_structural | ^GSPC | ok |  | 1.85% | 2026-07-31 |
| w2026_structural | ^MOVE | ok |  | 20.37% | 2026-07-17 |
| w2026_structural | ^VIX | ok |  | 0.00% | 2026-07-31 |
| w2026_structural | ^VIX3M | ok |  | 20.37% | 2026-07-17 |
| w2026_structural | kr_cds_5y_delta | uncollected | out_of_scope | 100.00% | - |
| w2026_structural | krx_credit_spread_delta | uncollected | out_of_scope | 100.00% | - |
| w2026_structural | krx_halt_events | uncollected | disabled | 100.00% | - |
| w2026_structural | margin_leverage_stress | uncollected | disabled | 100.00% | - |
| w2026_structural | news_novelty | uncollected | disabled | 100.00% | - |
| w2026_structural | news_volume_z | uncollected | disabled | 100.00% | - |

## 3. 축 커버리지 롤업 (비례 배분, 가중치: configs/indicators.yaml SSOT)

| window_id | axis | coverage weight/total | coverage |
|---|---|---|---|
| w2011_us_downgrade | credit | 0.00/6.50 | 0% |
| w2011_us_downgrade | global_price | 3.50/3.50 | 100% |
| w2011_us_downgrade | kr_flow_price | 6.00/8.50 | 71% |
| w2011_us_downgrade | rates_fx | 5.50/5.50 | 100% |
| w2011_us_downgrade | vol_global | 7.00/7.00 | 100% |
| w2015_cny_deval | credit | 0.00/6.50 | 0% |
| w2015_cny_deval | global_price | 3.50/3.50 | 100% |
| w2015_cny_deval | kr_flow_price | 6.00/8.50 | 71% |
| w2015_cny_deval | rates_fx | 5.50/5.50 | 100% |
| w2015_cny_deval | vol_global | 7.00/7.00 | 100% |
| w2018_q4_tightening | credit | 0.00/6.50 | 0% |
| w2018_q4_tightening | global_price | 3.50/3.50 | 100% |
| w2018_q4_tightening | kr_flow_price | 6.00/8.50 | 71% |
| w2018_q4_tightening | rates_fx | 5.50/5.50 | 100% |
| w2018_q4_tightening | vol_global | 6.92/7.00 | 99% |
| w2020_covid | credit | 0.00/6.50 | 0% |
| w2020_covid | global_price | 3.50/3.50 | 100% |
| w2020_covid | kr_flow_price | 6.00/8.50 | 71% |
| w2020_covid | rates_fx | 5.50/5.50 | 100% |
| w2020_covid | vol_global | 7.00/7.00 | 100% |
| w2022_tightening | credit | 0.00/6.50 | 0% |
| w2022_tightening | global_price | 3.50/3.50 | 100% |
| w2022_tightening | kr_flow_price | 6.00/8.50 | 71% |
| w2022_tightening | rates_fx | 5.50/5.50 | 100% |
| w2022_tightening | vol_global | 6.97/7.00 | 100% |
| w2023_11_rally | credit | 3.00/6.50 | 46% |
| w2023_11_rally | global_price | 3.50/3.50 | 100% |
| w2023_11_rally | kr_flow_price | 6.00/8.50 | 71% |
| w2023_11_rally | rates_fx | 5.45/5.50 | 99% |
| w2023_11_rally | vol_global | 7.00/7.00 | 100% |
| w2024_05_calm | credit | 3.00/6.50 | 46% |
| w2024_05_calm | global_price | 3.50/3.50 | 100% |
| w2024_05_calm | kr_flow_price | 6.00/8.50 | 71% |
| w2024_05_calm | rates_fx | 5.50/5.50 | 100% |
| w2024_05_calm | vol_global | 7.00/7.00 | 100% |
| w2024_carry_unwind | credit | 3.00/6.50 | 46% |
| w2024_carry_unwind | global_price | 3.50/3.50 | 100% |
| w2024_carry_unwind | kr_flow_price | 6.00/8.50 | 71% |
| w2024_carry_unwind | rates_fx | 5.50/5.50 | 100% |
| w2024_carry_unwind | vol_global | 7.00/7.00 | 100% |
| w2026_structural | credit | 2.95/6.50 | 45% |
| w2026_structural | global_price | 3.44/3.50 | 98% |
| w2026_structural | kr_flow_price | 6.00/8.50 | 71% |
| w2026_structural | rates_fx | 5.40/5.50 | 98% |
| w2026_structural | vol_global | 6.19/7.00 | 88% |

## 4. 주목 공백 (notable gaps — 평가구간 내 전체 공백 span 목록)

| window_id | series_id | gap_start | gap_end | sessions | last_as_of | 비고 |
|---|---|---|---|---|---|---|
| w2018_q4_tightening | ^MOVE | 2018-10-08 | 2018-10-08 | 1 | 2018-12-31 | |
| w2018_q4_tightening | ^MOVE | 2018-11-07 | 2018-11-07 | 1 | 2018-12-31 | |
| w2018_q4_tightening | ^MOVE | 2018-11-12 | 2018-11-12 | 1 | 2018-12-31 | |
| w2018_q4_tightening | ^MOVE | 2018-12-06 | 2018-12-06 | 1 | 2018-12-31 | |
| w2022_tightening | ^MOVE | 2022-10-10 | 2022-10-10 | 1 | 2022-11-30 | |
| w2022_tightening | ^MOVE | 2022-11-11 | 2022-11-11 | 1 | 2022-11-30 | |
| w2023_11_rally | T10Y2Y | 2023-11-23 | 2023-11-23 | 1 | 2023-11-30 | |
| w2026_structural | BAMLH0A0HYM2 | 2026-07-31 | 2026-07-31 | 1 | 2026-07-30 | |
| w2026_structural | DX-Y.NYB | 2026-05-25 | 2026-05-25 | 1 | 2026-07-31 | |
| w2026_structural | T10Y2Y | 2026-05-25 | 2026-05-25 | 1 | 2026-07-31 | |
| w2026_structural | T10Y2Y | 2026-05-31 | 2026-05-31 | 1 | 2026-07-31 | |
| w2026_structural | T10Y2Y | 2026-06-19 | 2026-06-19 | 1 | 2026-07-31 | |
| w2026_structural | T10Y2Y | 2026-07-03 | 2026-07-03 | 1 | 2026-07-31 | |
| w2026_structural | ^GSPC | 2026-05-25 | 2026-05-25 | 1 | 2026-07-31 | |
| w2026_structural | ^MOVE | 2026-05-25 | 2026-05-25 | 1 | 2026-07-17 | |
| w2026_structural | ^MOVE | 2026-07-20 | 2026-07-31 | 10 | 2026-07-17 | |
| w2026_structural | ^VIX3M | 2026-05-25 | 2026-05-25 | 1 | 2026-07-17 | |
| w2026_structural | ^VIX3M | 2026-07-20 | 2026-07-31 | 10 | 2026-07-17 | |
