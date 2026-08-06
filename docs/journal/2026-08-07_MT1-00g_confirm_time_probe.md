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
  4개 데이터셋 전부가 17:00 이전에 확정되는지를 **연속 3거래일 이상 × 6개 시점**
  (16:00/16:30/17:00/17:30/18:00/19:00) 표본으로 판정하라고 못박아 두었다 — 지금은 그 6개
  정규 시점 중 0개, 정규 외 사전 참고 표본(장전) 1개뿐이므로 사후 합리화를 피하기 위해 판정을
  유보한다.
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

**자가 검증**: `tests/test_probe_confirm_time.py` 7종(초기 6종 + §7 라운드2 D-3 대응
1종), 네트워크 0 (`_row_status`의 confirmed/not_yet/no_data 판정 — 1행·다행 픽스처 양쪽,
yfinance MultiIndex 컬럼 드롭 재현, NaN→None 변환, VKOSPI 상속 로직).

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
이전 — 사전 등록된 **6개 정규 시점**(16:00/16:30/17:00/17:30/18:00/19:00)에는 포함되지
않는 **정규 외 참고 표본**이다. 목적은 판정 표본 축적이 아니라 계기 동작 확인).

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

MT1-00g 본문 요구: **연속 3거래일 이상 × 6개 시점**(16:00/16:30/17:00/17:30/18:00/19:00).
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
uv run pytest -q   # 184 passed at first submission (기존 178 + 신규 6). §7 라운드2 이후 185
                   # passed (D-3 증인 1종 추가) — 최신 수치는 §7 재검증 참조.
```

`.env`의 어떤 키 값도 본 문서·터미널 출력에 기록하지 않았다(변수명·존재 여부만 사용).

## 6. 생성/변경 파일 목록

- `scripts/probe_confirm_time.py` (신규)
- `tests/test_probe_confirm_time.py` (신규)
- `scripts/out/confirm_time_probe/2026-08-07.json` (신규, 1차 표본 원자료)
- `docs/journal/2026-08-07_MT1-00g_confirm_time_probe.md` (본 문서)

## 7. 라운드 2 — aaa-critic 반려 대응 (D-3·D-4·D-5·A-6①)

aaa-critic 1차 판정 **FAIL**(결함 3건 + 미인도 1건). 4건 전부 대응:

### D-3 — 뮤턴트 생존(퇴화 입력 증인 부재)

기존 테스트 6종이 전부 1행 DataFrame 픽스처였다 — `scripts/probe_confirm_time.py`의
`latest_ts = df.index[-1]`을 `df.index[0]`으로 바꿔도(뮤턴트) 1행뿐이라 `[-1]`과 `[0]`이
같은 행을 가리켜 전부 통과했다(생존). **≥3행, 날짜가 전부 다른 픽스처**로 증인 1건 추가
(`test_row_status_picks_last_row_not_first_multi_row`) 후 뮤턴트를 실제로 적용·실행·원복해
사망을 확인했다:

```
$ # mutant 적용: df.index[-1] -> df.index[0]
$ uv run pytest -q tests/test_probe_confirm_time.py
.F.....
FAILED tests/test_probe_confirm_time.py::test_row_status_picks_last_row_not_first_multi_row
AssertionError: assert 'not_yet_today' == 'confirmed_today'
1 failed, 6 passed in 1.19s
$ # 원복: df.index[0] -> df.index[-1]
$ uv run pytest -q tests/test_probe_confirm_time.py
.......
7 passed in 0.93s
```

새 증인이 정확히 이 뮤턴트만 잡고(다른 6종은 뮤턴트 적용 중에도 그대로 통과) 원복 후
전체 green으로 복귀함을 확인했다 — 뮤턴트는 이 커밋에 남아있지 않다(적용→실행→즉시 원복,
파일 diff 없음).

### D-4 — SSOT 이중화(`PYKRX_MIN_INTERVAL_S = 1.0` 리터럴)

리터럴 제거. `backtest/build_fixtures.py:193-194` `_pykrx_min_interval_s()` 선례와 동일한
패턴으로 `configs/sources.yaml` `providers.pykrx.rate_limit.min_interval_s`에서 직접
로드하도록 변경(파일 자체를 yaml로 읽는 방식 — `backtest` 모듈 임포트로 무거운 의존을
끌어오지 않음, 이 스크립트는 backtest 파이프라인과 무관). 네트워크 없이 확인:

```
$ uv run python -c "from scripts.probe_confirm_time import _pykrx_min_interval_s; print(_pykrx_min_interval_s())"
1.0
$ grep -n "min_interval_s" configs/sources.yaml
    rate_limit: { min_interval_s: 1.0 }
```

값 일치. `configs/sources.yaml`의 값을 바꾸면 스크립트의 대기 시간도 같이 바뀐다(이중화
해소).

### D-5 — 저널 표본 계획 오기(7/8개 → 6개)

M1_PLAN_A.md MT1-00g 본문의 사전등록은 **6개 시점**(16:00/16:30/17:00/17:30/18:00/19:00)이다.
§0·§2·§3에서 "7개 시점"·"8개 정규 시점"으로 잘못 적었던 것을 6으로 통일하고, 05:49 장전
표본은 "정규 외 참고 표본"으로 명확히 구분 표기했다(본 문서 상단 수정 완료 — 위 §0/§2/§3
참조. 6개 정규 시점 자체는 처음부터 실행 지침(§3)에 올바르게 적혀 있었다).

### A-6① — 미인도 산출물: collector별 range 조회 상한

M1_PLAN_A.md MT1-00g 행의 부수 산출("각 collector의 range 조회 상한 — MT1-06b 캐치업
상한의 입력")을 1차 제출에서 빠뜨렸다. 소량 실측 추가(K-03 간격 준수, 항목당 실호출
≤3회 — kospi_close·investor_net_buying·krwusd_fx 전부 §2의 1회 + 이번 1회 = 2회로 예산
내):

| collector | 요청 범위 | 반환 행수 | 반환 날짜 범위 | 상한 관측 |
|---|---|---|---|---|
| pykrx `get_index_ohlcv_by_date("1001")` | 2023-01-01~2026-08-07 (약 3.6년) | 877행 | 2023-01-02~2026-08-06 | **상한 없음** — 요청 구간이 그대로 반환(XKRX 캘린더 기준 예상 880거래일과 거의 일치, 차이 3일은 캘린더가 2026년 임시공휴일을 놓칠 수 있다는 기존 caveat(`backtest/build_fixtures.py` NEW-1)와 동일 성격) |
| pykrx `get_market_trading_value_by_date(..., "KOSPI", on="순매수")` | 2023-01-01~2026-08-07 | 877행 | 2023-01-02~2026-08-06 | **상한 없음** — 위와 동일 |
| yfinance `download("KRW=X", ...)` | 2003-01-01~2026-08-08 (약 23.6년) | 5883행 | 2003-12-01~2026-08-06 | **상한 없음** — 반환 시작일(2003-12-01)은 심볼 자체의 데이터 개시일이며 요청 파라미터가 자른 것이 아님 |

**결론**: 3개 collector 전부 단일 호출로 877거래일(pykrx)·23.6년(yfinance) 범위를 문제없이
반환했다 — `catchup_max_trading_days`(U-11, 잠정 20)의 40배 이상 범위에서도 상한이
관측되지 않았다. 즉 **collector 쪽 range 제약은 캐치업 상한 설계의 병목이 아니다** —
U-11 최종값은 range 상한이 아니라 §3 MT1-06b 5항의 다른 근거(하한: 최장 오프라인 구간,
상한: 원장 보유 이력 252거래일)로만 결정하면 된다는 사실을 뒷받침하는 실측이다.

**재현 절차**:

```
$ set -a; source .env; set +a
$ uv run python -c "
from datetime import date
from pykrx import stock
import exchange_calendars as xcals
fromdate, today = date(2023, 1, 1), date(2026, 8, 7)
cal = xcals.get_calendar('XKRX')
print(len(cal.sessions_in_range(fromdate.isoformat(), today.isoformat())))
df1 = stock.get_index_ohlcv_by_date(fromdate.strftime('%Y%m%d'), today.strftime('%Y%m%d'), '1001')
print(len(df1), df1.index.min(), df1.index.max())
import time; time.sleep(1.0)
df2 = stock.get_market_trading_value_by_date(fromdate.strftime('%Y%m%d'), today.strftime('%Y%m%d'), 'KOSPI', on='순매수')
print(len(df2), df2.index.min(), df2.index.max())
"
# -> 880 / 877 2023-01-02 2026-08-06 / 877 2023-01-02 2026-08-06

$ uv run python -c "
import yfinance as yf
df = yf.download('KRW=X', start='2003-01-01', end='2026-08-08', progress=False, auto_adjust=False)
print(len(df), df.index.min(), df.index.max())
"
# -> 5883 2003-12-01 00:00:00 2026-08-06 00:00:00
```

**범위 밖으로 남긴 것**: 더 긴 범위(10년+)의 추가 실측은 하지 않았다 — 이미 U-11
잠정값보다 훨씬 큰 범위에서 상한이 관측되지 않아, 추가 실호출로 얻을 정보 이득이
"최소 호출" 원칙을 넘어설 만큼 크지 않다고 판단했다. 이번 3회 호출은 이 절의 실측
전용이며, §2의 1차 표본과 겹치는 데이터셋(kospi_close·investor_net_buying·
krwusd_fx)이라도 목적(range 상한 vs confirm-time)이 달라 별도로 집계했다.

### 재검증

```
uv run ruff check .            # All checks passed
uv run pytest -q                # 185 passed (기존 184 + D-3 증인 1건, 회귀 없음)
```
