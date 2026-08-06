# MT1-00g — 확정 틱 17:00 물리 전제 실측 계기 구축 + 1차 표본

- 작성: 2026-08-07, data-verifier Worker
- 대상: 확정 틱 17:00 KST가 전제하는 4개 데이터셋(① KOSPI 지수 종가·거래대금 ② 투자자별
  순매수 ③ VKOSPI(폴백) ④ KRW=X)의 **최초 확정 시각** 실측 — 이번 과업은 **계기 구축 + 1차
  표본**까지이며, 최종 판정(17:00 유지/상향)은 범위 밖(브리프 지시, 3거래일 표본이 모인 뒤 별도
  수행)

## 0. 결론 (요지)

- 계기 스크립트 `scripts/probe_confirm_time.py`를 작성하고 1회 실행해 1차 표본을 확보했다.
- 이번 실행은 **05:49 KST(장 시작 09:00 이전)**에 수행됐다 — "안전한 최소 확정 시각"을
  판정하기엔 이른 시점이지만, 브리프 §2가 명시한 대로 "장 마감 전/후 무엇이든 유효한 관측"이며
  실제로 계기가 **"오늘자 미확정"을 정확히 감지**함을 보여주는 유효한 증거다(사실이 그러하므로
  그렇게 나와야 정상 동작).
- **판정은 수행하지 않았다.** MT1-00g 본문의 사전 고정 규칙(`docs/plans/M1_PLAN_A.md` 919~924행)은
  4개 데이터셋 전부가 17:00 이전에 확정되는지를 **연속 3거래일 이상 × 7개 시점** 표본으로
  판정하라고 못박아 두었다 — 지금은 8개 시점 중 1개(사전 참고용, 장전) 표본뿐이므로 사후
  합리화를 피하기 위해 판정을 유보한다.
- `configs/sources.yaml`·`configs/statemachine.yaml`·`backtest/replay.yaml` 등 **SSOT 무변경**
  (반영할 확정 사실이 아직 없음).

## 1. 계기 설계 (`scripts/probe_confirm_time.py`)

**대상 4개 데이터셋과 조회 방식** (M1_PLAN_A.md MT1-00g 본문의 항목 번호 그대로):

| # | 데이터셋 | 조회 |
|---|---|---|
| ① | KOSPI 지수 종가·거래대금 | `pykrx.stock.get_index_ohlcv_by_date(from, to, "1001")` |
| ② | 투자자별 순매수(시장 전체) | `pykrx.stock.get_market_trading_value_by_date(from, to, "KOSPI", on="순매수")` |
| ③ | VKOSPI | **폴백 상속**(아래 설명) |
| ④ | KRW=X 일봉 | `yfinance.download("KRW=X", ...)` |

**③ VKOSPI는 재호출하지 않는다.** K-02가 2026-08-02에 이미 실측 확정한 사실
(`configs/sources.yaml` pykrx 항목 notes) — pykrx 인덱스(현물) API에는 VKOSPI 티커가
168종 전량 스캔 결과 존재하지 않는다. 폴백 `realized_vol_kospi_20d`는 ①과 **같은 KOSPI
OHLCV 시리즈**에서 파생되므로, 이 스크립트는 ③을 위해 KRX를 다시 호출하는 대신 ①의
확정 상태를 그대로 상속한 `fallback_active` 레코드를 기록한다(브리프 지시 "조회 불가로
판명되면 지정된 fallback 경로를 활성화하고 그 사실을 문서에 기록" 이행 — 추측 없음, 이미
확정된 사실의 재사용).

**판정 로직 없음.** 스크립트는 "오늘자 행이 이미 존재하는가 + 그 값"만 JSON으로 남긴다.
`confirmed_today`/`not_yet_today`/`no_data`/`error`/`blocked_missing_credentials`/
`fallback_active` 6개 상태 문자열뿐, "17:00을 유지할지"는 스크립트가 판단하지 않는다 —
MT1-00g 본문의 "사전 고정 — 사후 합리화 금지" 규율을 코드에도 그대로 반영했다.

**실호출 예산**: 폴링 1회당 pykrx 2회(①·②, ③은 상속이라 0회) + yfinance 1회 = 항목당
1회, 브리프의 "항목당 ≤ 3회" 한도 내. K-03 준수: pykrx 두 호출 사이 `time.sleep(1.0)`.
KRX_ID/KRX_PW 미설정 시 예외를 던지지 않고 `blocked_missing_credentials`로 기록 후 계속
(K-01/K-02와 같은 원칙 — 실패를 예외로 전파하지 않음).

**산출 위치**: `scripts/out/confirm_time_probe/<YYYY-MM-DD>.json` — 그날의 모든 폴링을
배열로 누적(기존 파일 있으면 append, 덮어쓰지 않음).

**자가 검증**: `tests/test_probe_confirm_time.py` 6종, 네트워크 0
(`_row_status`의 confirmed/not_yet/no_data 판정, yfinance MultiIndex 컬럼 드롭 재현,
NaN→None 변환, VKOSPI 상속 로직).

## 2. 1차 표본 — 실행 증빙

**사전 확인**: 오늘(2026-08-07, 금)이 거래일인지 `exchange_calendars`(XKRX)로 확인.

```
$ uv run python -c "import exchange_calendars as xcals; print(xcals.get_calendar('XKRX').is_session('2026-08-07'))"
True
```

거래일 확인됨 — 휴장 처리 불필요.

**실행 명령** (KRX_ID/KRX_PW는 `.env`에서 셸 환경변수로만 주입, 값은 어디에도 미노출):

```
$ set -a; source .env; set +a
$ uv run python scripts/probe_confirm_time.py --label 0544_preopen
```

**실행 시각**: `2026-08-07T05:49:44.545858+09:00` (label=`0544_preopen`, 장 시작 09:00
이전 — 이번 실행은 8개 정규 시점(16:00/16:30/17:00/17:30/18:00/19:00 + 이번 사전 참고 시점)
중 계기 동작 확인용 참고 표본이다).

**결과 요지** (원문 전체는 `scripts/out/confirm_time_probe/2026-08-07.json`):

| 항목 | 상태 | latest_date | 비고 |
|---|---|---|---|
| kospi_close | `not_yet_today` | 2026-08-06 | 종가 6296.38(전일 확정치), 거래대금 26.45조 |
| investor_net_buying | `not_yet_today` | 2026-08-06 | 외국인 -3.29조 / 개인 +3.34조(전일 확정치) |
| vkospi | `fallback_active` | — | `kospi_close_status=not_yet_today` 상속 |
| krwusd_fx | `not_yet_today` | 2026-08-06 | Close 1423.24(전일 확정치) |

**해석**: 05:49 KST는 장 시작 전이므로 4개 데이터셋 모두 "오늘자 미확정, 전일 종가만 확정"
으로 정확히 감지됐다 — 이는 계기의 결함이 아니라 **사실과 일치하는 정상 동작**이다(오늘
데이터가 실제로 아직 존재하지 않는 시점에 "미확정"이 나와야 계기가 올바른 것). 16:00 이후
정규 시점 폴링에서 이 상태가 언제 `confirmed_today`로 바뀌는지가 실제 판정 대상이다.

**부수 관찰**: pykrx 1.2.8이 KRX 로그인 시 로그인 ID를 콘솔에 자체적으로 echo했다
(2026-08-02 MT0-03 선례와 동일한 라이브러리 자체 동작 — 본 스크립트가 출력한 것이
아니며, 선례를 따라 본 문서에는 ID 값을 재기록하지 않는다). `.env`의 실제 값(KRX_ID/
KRX_PW)은 본 문서·터미널 캡처 어디에도 노출하지 않았다.

## 3. 남은 폴링 일정 (Advisor가 스케줄 관리)

MT1-00g 본문 요구: **연속 3거래일 이상 × 7개 시점**(16:00/16:30/17:00/17:30/18:00/19:00).
`exchange_calendars`(XKRX) 확인 결과, 다음 3거래일은:

| 거래일 | 상태 |
|---|---|
| 2026-08-07 (금) | 오늘 — 05:49 사전 표본 완료, **16:00 이후 6개 시점 남음** |
| 2026-08-10 (월) | 예정 (08-08/09 주말) |
| 2026-08-11 (화) | 예정 |

(참고: `exchange_calendars`는 2026년 임시공휴일을 놓칠 수 있다는 캐비어트가
`backtest/build_fixtures.py`에 이미 기록돼 있다(NEW-1 주석) — 실제 실행 직전에 KRX 공식
휴장일과 재대조 권장.)

**실행 지침** (매 시점마다 `--label`만 바꿔 그대로 재실행):

```
set -a; source .env; set +a
uv run python scripts/probe_confirm_time.py --label 1600   # 16:00
uv run python scripts/probe_confirm_time.py --label 1630   # 16:30
uv run python scripts/probe_confirm_time.py --label 1700   # 17:00
uv run python scripts/probe_confirm_time.py --label 1730   # 17:30
uv run python scripts/probe_confirm_time.py --label 1800   # 18:00
uv run python scripts/probe_confirm_time.py --label 1900   # 19:00
```

각 실행은 `scripts/out/confirm_time_probe/<그날 날짜>.json`에 레코드 1건을 append한다.
크론 없이 사람이 그때그때 실행하는 형태로 설계했다(브리프 지시).

**3거래일 표본이 모인 뒤** 적용할 판정 규칙(M1_PLAN_A.md 919~924행, 이 문서가 임의로
바꾸지 않음 — 그대로 인용):

> 4개 데이터셋 전부가 17:00 이전에 최종값을 내면 **17:00 확정**(§9의 논증이 그대로
> 성립). 하나라도 17:00 이후면 → (i) 가장 늦은 확정 시각 + 30분 여유로 확정 시각을
> **상향** 제안하고 §11 C-1·C-6의 값을 그에 맞춰 갱신, (ii) 동시에
> `backtest/replay.yaml` `confirm_time_kst`와의 불일치가 생기므로 **골든 재산출 필요
> 여부를 backtest-analyst가 판정**한다. 17:00보다 이르게 낮추는 방향은 검토하지 않는다.

이 판정은 **이번 과업 범위 밖**이다 — 표본이 갖춰지면 Advisor가 별도 서브태스크로
수행한다.

## 4. 확정 반영

- `configs/sources.yaml` / `configs/statemachine.yaml` / `backtest/replay.yaml` —
  **무변경**(판정 전이므로 반영할 확정 사실이 없음, 브리프 범위와 일치).
- 신규 생성: `scripts/probe_confirm_time.py`, `tests/test_probe_confirm_time.py`,
  `scripts/out/confirm_time_probe/2026-08-07.json`, 본 문서.

## 5. 검증

```
uv run ruff check scripts/probe_confirm_time.py tests/test_probe_confirm_time.py   # pass
uv run ruff format --check scripts/probe_confirm_time.py tests/test_probe_confirm_time.py  # pass
uv run pytest -q   # 184 passed (기존 178 + 신규 6, 회귀 없음)
```

`.env`의 어떤 키 값도 본 문서·터미널 출력에 기록하지 않았다(변수명·존재 여부만 사용).

## 6. 생성/변경 파일 목록

- `scripts/probe_confirm_time.py` (신규)
- `tests/test_probe_confirm_time.py` (신규)
- `scripts/out/confirm_time_probe/2026-08-07.json` (신규, 1차 표본 원자료)
- `docs/journal/2026-08-07_MT1-00g_confirm_time_probe.md` (본 문서)
