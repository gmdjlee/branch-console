# MT0-03 픽스처 실수집 — 9창 실행 및 원인 규명

> 근사-PIT — C1에서 실측 확정 (BACKTEST_PLAN.md §5)

- 작성: 2026-08-02, data-verifier Worker
- 대상: `backtest/build_fixtures.py --window all` 실행 결과 9창의 실측 사실 확정, 및 Advisor 재개
  지시에 따른 수집 실패 원인 규명(코드 수정 없음 — 재현 절차·실제 응답만 보고)

## 0. 실행 개요

- 명령: `uv run python backtest/build_fixtures.py --window all` (환경변수는 `.env`에서 셸로만 로드,
  값은 어디에도 출력하지 않음)
- 결과물: `backtest/fixtures/<window_id>.parquet` 9개 + `<window_id>.meta.json` 9개 **모두 생성됨**.
- 그러나 **프로세스 자체는 exit code 1로 실패** — 원인은 §1 참조(리포트 출력 단계의 별개 버그이며,
  9창 수집 자체는 그 이전에 이미 완료되어 있었음).
- `backtest/fixtures/REPORT_fixtures.md`는 CLI 실행 경로에서는 §1의 크래시로 생성되지 않았다.
  이번 세션에서는 **코드를 수정하지 않고** 이미 존재하는 순수 함수 `render_report()`를
  `uv run python -c`로 직접 호출해 문자열을 얻은 뒤 `write_text(..., encoding="utf-8")`로 저장했다
  (콘솔 `print()`를 거치지 않으므로 §1의 cp949 크래시를 피해간다). **§1의 버그 자체는 여전히
  존재** — `--window all`을 그대로 재실행하면 동일하게 exit 1로 실패한다.

## 1. [코드 결함 #1] REPORT 출력 시 UnicodeEncodeError로 프로세스 종료 (exit 1)

**재현 절차**: `uv run python backtest/build_fixtures.py --window all` 를 표준출력이 파일로
리다이렉트되는 환경(Windows, 콘솔 코드페이지 cp949)에서 실행.

**실제 traceback 원문**:
```
Traceback (most recent call last):
  File "D:\wp_2026\branch-console\backtest\build_fixtures.py", line 535, in <module>
    raise SystemExit(main())
  File "D:\wp_2026\branch-console\backtest\build_fixtures.py", line 529, in main
    print(report)
UnicodeEncodeError: 'cp949' codec can't encode character '\u2014' in position 44: illegal multibyte sequence
```

**기대와의 차이**: `render_report()`가 만드는 문자열에 "근사-PIT — C1에서..." 처럼 em-dash(`—`,
U+2014)를 포함한 한글 텍스트가 들어있는데, `main()`이 `(FIXTURES_DIR / "REPORT_fixtures.md").write_text(...)`
(명시적 `encoding` 없음 → 플랫폼 기본, 이 환경에서는 여전히 cp949 가능성 있음)로 파일에 쓰기 **이전에**
`print(report)`로 콘솔에 먼저 찍는데, 이 콘솔 스트림의 인코딩이 cp949인 환경에서는 em-dash 인코딩이
실패해 예외가 발생하고, 그 뒤에 있는 `write_text` 호출까지 도달하지 못해 **`REPORT_fixtures.md`가
아예 만들어지지 않는다.** 본 세션에서 `yaml.safe_load` 결과를 `print()`로 확인하려 했을 때도 동일한
`UnicodeEncodeError`가 재현되어(§별첨 없음, 본문 인용 생략) 우연한 1회성이 아니라 이 환경의 상시
재현 조건임을 확인했다. MEMORY.md에 이미 기록된 "cp949 인코딩" 환경 특이사항과 정확히 일치한다.

**재위임 메모(python-implementer)**: `print(report)` 호출을 제거하거나
`sys.stdout.reconfigure(encoding="utf-8", errors="replace")`/`write_text(..., encoding="utf-8")`
선행 등으로 콘솔 인코딩과 무관하게 리포트 파일 쓰기가 먼저 끝나도록 순서를 바꾸는 수정이 필요하다.
(수정은 본 Worker 권한 밖 — 보고만.)

## 2. [코드 결함 #2] yfinance 6계열 전멸 — MultiIndex 컬럼 불일치

**재현 절차**: 빌더가 실제로 호출하는 형태 그대로 재현.
```python
import yfinance as yf
df = yf.download("^VIX", start="2024-07-25", end="2024-08-10", progress=False, auto_adjust=False)
```

**실제 반환(원문)**:
```
type= <class 'pandas.DataFrame'>
shape= (12, 6)
columns= [('Adj Close', '^VIX'), ('Close', '^VIX'), ('High', '^VIX'), ('Low', '^VIX'), ('Open', '^VIX'), ('Volume', '^VIX')]
```
데이터 자체는 **정상 수신**(12행, 결측 없음) — 야후 측 차단/레이트리밋이 아니다. 문제는 설치된
`yfinance==1.5.2`가 **단일 티커 요청에도 컬럼을 `(필드, 티커)` 튜플의 MultiIndex로 반환**한다는 점.

**기대와의 차이**: `backtest/fixture_schema.py::normalize_yfinance()`는
`frame.columns = [str(c).lower() for c in frame.columns]` 로 컬럼을 평탄화하는데, MultiIndex 컬럼의
`str(('Close', '^VIX'))`는 `"close"`가 아니라 `"('close', '^vix')"`가 되어 `field in frame.columns`
매칭이 전부 실패한다. 결과적으로 매 series마다 빈 프레임이 반환되어 `status=empty, "no rows returned"`
로 기록된다 — **네트워크 문제가 아니라 순수 컬럼-매핑 버그**다. `tests/test_fixture_schema.py`의
`StubFetcher.yfinance()`가 구버전처럼 평탄한 컬럼(`Open/High/Low/Close/Volume`)을 반환하도록
picture되어 있어 이 회귀를 테스트가 잡아내지 못했다.

**재위임 메모(python-implementer)**: `normalize_yfinance()`에서 MultiIndex 컬럼일 때
`frame.columns = frame.columns.get_level_values(0)` 등으로 필드 레벨만 취하도록 정규화 필요.
StubFetcher도 실제 1.5.2 반환 형태(MultiIndex)로 갱신해야 이런 회귀가 재발 시 테스트가 잡는다.

## 3. [외부 요인 — pykrx 3계열] KRX 서버가 전 요청을 거부 (403 / 빈 응답)

세 항목(KRX:1001 index_ohlcv, KRX:investor_foreign_kospi, KRX:VKOSPI)의 실패가 각기 다른 증상으로
보였으나(KeyError, empty, VKOSPI 미해결), 최소 실호출로 추적한 결과 **단일 근본 원인**으로 수렴한다:
`data.krx.co.kr`가 pykrx(1.0.51)의 HTTP 요청 자체를 거부하고 있다.

**재현 절차 1 — KRX:1001 실제 traceback** (`get_index_ohlcv_by_date('20240701','20240710','1001')`):
```
File ".../pykrx/stock/stock_api.py", line 1433, in get_index_ohlcv_by_date
    df.columns.name = get_index_ticker_name(ticker)
File ".../pykrx/website/krx/market/ticker.py", line 115, in get_name
    return self.df.loc[ticker, '지수명']
File ".../pandas/core/indexes/range.py", line 525, in get_loc
    raise KeyError(key)
KeyError: '지수명'
```
`get_index_ohlcv_by_date(..., name_display=False)`로 티커명 조회를 우회해도 동일 호출은
`shape=(0, 0)`(완전히 빈 DataFrame) — 즉 티커명 조회뿐 아니라 **OHLCV 원본 데이터 자체도 안 온다.**

**재현 절차 2 — investor_foreign_kospi**: pykrx 자체 docstring 예시 날짜(`get_market_trading_value_by_date
("20210115","20210122","KOSPI",on="순매수")`)를 그대로 호출해도 `shape=(0, 0)` — 날짜 구간 문제가
아니라 pykrx 라이브러리 예시조차 재현되지 않는다.

**재현 절차 3 — VKOSPI(K-02) 최종 확인**: `get_index_ticker_list(market="KRX"/"KOSPI"/"테마")` 3건
모두 `KeyError: '시장'` (동일 근본 원인의 다른 증상 — 내부 `IndexTicker` 메타 테이블이 빈 기본
RangeIndex 상태).

**근본 원인 확정 — 원본 HTTP 응답**: pykrx가 사용하는 원본 엔드포인트를 직접 호출.
```python
requests.post("http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd",
              data={"bld": "dbms/MDC/STAT/standard/MDCSTAT00401", "idxIndMidclssCd": "01"}, timeout=15)
```
실제 응답: **`status_code=403`**, `content-type=text/html;charset=ISO-8859-1`, 본문은
`<title>Error - KRX | Market Data System</title>` 로 시작하는 2421바이트 HTML(KRX 자체 오류 페이지,
리다이렉트 없음). pykrx 내부 경로(자체 헤더/세션 포함)로 동일 bld를 호출하면 `resp.json()`이
`JSONDecodeError: Expecting value: line 1 column 1 (char 0)`(본문 0바이트)로 실패 — 헤더 유무에 따라
증상은 다르지만(생짜 요청은 403+HTML, pykrx 자체 요청은 200 추정+빈 바디) 결과는 동일하게 사용 불가.

**결론**: KRX 서버가 이 환경에서의 pykrx 요청을 차단/거부하고 있다(원인은 IP·세션·User-Agent/Referer
기반 봇 차단 강화 또는 pykrx 1.0.51과 KRX 응답 스키마 불일치로 추정 — 어느 쪽인지는 이번 실측 범위를
넘어선다). FRED(https, 2계열 ok)·yfinance(https, 실데이터 수신 확인)는 정상 동작하므로 **이 환경의
일반 아웃바운드 네트워크 차단은 아니며, KRX 대상 요청만 거부된다.** 이는 CLAUDE.md K-03이 명시한
"비공식 KRX 스크레이핑 기반 — 구조 변경·차단 상시 가정"이 그대로 실현된 사례다.

**재위임 메모(python-implementer/backtest-analyst)**: 코드 버그가 아니라 외부 의존성(pykrx↔KRX)
장애. 옵션: (a) pykrx 최신 버전으로 업그레이드 후 재시도, (b) 세션/쿠키/헤더 확보 절차 보강, (c) KRX
데이터 없이 P0 결정대로 vkospi_z는 fallback(realized_vol_kospi_20d), kospi_drawdown·
foreign_net_sell_kospi 등 pykrx 의존 지표는 데이터 확보 전까지 결측 처리하고 백테스트/엔진 결측
정책(`missing_data_policy: exclude_from_denominator`)로 흡수. **코드 수정은 본 Worker 권한 밖.**

## 4. 확정된 실측 사실 (5건)

1. **K-02 VKOSPI**: pykrx로 VKOSPI를 확정할 수 없다(§3 재현 절차 3). 원인은 pykrx 자체의
   "그런 티커가 없음"이 아니라 KRX 서버가 인덱스 메타 요청 자체를 거부하기 때문(§3 근본 원인).
   실용적 결론은 동일 — **fallback `realized_vol_kospi_20d` 활성화**를 확정하고,
   `configs/sources.yaml`의 `providers.pykrx.notes`에 1줄로 기록 완료(§5 "확정 반영" 참조).
2. **K-01 야후계 결측**: 실제로는 결측이 아니라 §2의 컬럼-매핑 버그로 인한 **가짜 전멸**이다.
   9창 모두에서 yfinance 6계열(^VIX, ^VIX3M, ^MOVE, ^GSPC, DX-Y.NYB, KRW=X) 전부 `status=empty`
   (`missing_rate=1.0`)이지만, 별도 실호출로 확인한 결과 야후 API는 정상 응답하며(§2) 데이터가
   존재한다 — 즉 "결측률"이 아니라 "정규화 실패율" 100%다.
3. **pykrx 컬럼 매핑 검증**: 컬럼명 불일치 문제가 아니다(`_PYKRX_OHLCV_COLUMN_MAP` 등은 데이터가
   와야 실행되는 코드인데, KRX 서버가 요청 단계에서 거부하므로 이 매핑 코드 자체가 실행되지 않는다).
   구현자가 실호출 없이 짠 매핑의 정확성은 **이번 실측으로는 검증 불가**(§3 traceback 참조 —
   `KeyError: '지수명'`은 pykrx 내부 `IndexTicker`에서 발생, `_PYKRX_OHLCV_COLUMN_MAP`에는 도달 전).
4. **2011·2015 창 pykrx 커버리지**: 확인 불가 — §3의 KRX 서버 차단으로 두 창 모두 KRX:1001,
   KRX:investor_foreign_kospi, KRX:VKOSPI 세 계열 전부 `error`/`empty`(외국인 순매수대금 포함,
   1건도 수신 안 됨). 과거 데이터 존재 여부 자체를 이번 실측으로는 판정할 수 없다.
5. **w2026_structural 수집 종료일**: 정의상 `end=2026-08-15`(미래)이지만 실제
   `collected_range.end=2026-08-02`(오늘, `min(end, 오늘)` 동작 확인 — `w2026_structural.meta.json`).

### 부수 관찰 (범위 외, 참고용)

- FRED `BAMLH0A0HYM2`가 9창 중 5창(2011/2015/2018/2020/2022)에서 `status=empty`("no observations
  returned")인 반면 `T10Y2Y`는 9창 모두 `ok`. `build_fixtures.py`의 FRED 경로는 yfinance/pykrx와
  달리 재시도·레이트리밋 정책이 없다(`sources.yaml`에 FRED `retry`/`rate_limit` 키 자체가 없음) —
  연속 9창×2계열 호출 중 일시 오류가 재시도 없이 그대로 `empty`로 기록됐을 가능성이 있다(이번 실측
  범위 밖이라 추가 실호출로 확인하지 않음).

## 5. 확정 반영

- `configs/sources.yaml` `providers.pykrx.notes`에 위 §3/§4-1 실측 결과 1줄 추가(그 외 configs 무변경).
  기존 K-03 문구는 유지하고 뒤에 이어 붙였다.
- 그 외 configs·backtest/·tests/ 코드는 수정하지 않았다(브리프 지시 준수). §1·§2·§3의 결함은
  재현 절차와 실제 응답을 위 형식으로 정리했으므로 python-implementer 재위임 시 그대로 사용 가능하다.

## 6. 생성 파일 목록

- `backtest/fixtures/w2011_us_downgrade.{parquet,meta.json}`
- `backtest/fixtures/w2015_cny_deval.{parquet,meta.json}`
- `backtest/fixtures/w2018_q4_tightening.{parquet,meta.json}`
- `backtest/fixtures/w2020_covid.{parquet,meta.json}`
- `backtest/fixtures/w2022_tightening.{parquet,meta.json}`
- `backtest/fixtures/w2024_carry_unwind.{parquet,meta.json}`
- `backtest/fixtures/w2026_structural.{parquet,meta.json}`
- `backtest/fixtures/w2024_05_calm.{parquet,meta.json}`
- `backtest/fixtures/w2023_11_rally.{parquet,meta.json}`
- `backtest/fixtures/REPORT_fixtures.md` — 존재함(§0에서 설명한 우회 방식으로 생성 — CLI 경로의
  §1 버그는 미수정 상태로 남아 있음)
- `configs/sources.yaml` — pykrx notes 1줄 추가만

## 7. 검증

- `uv run ruff check backtest/` → All checks passed.
- `uv run pytest -q` → 76 passed (전체 스위트, `tests/test_fixture_schema.py` 포함 — 네트워크 없이 green).
- 9개 parquet 전부 `backtest.fixture_schema.validate_fixture()` 통과 확인
  (스키마상으로는 유효 — 단, 내용은 §4에서 설명한 대로 FRED 1~2계열만 채워진 상태).

## 8. 키 미노출 확인

`.env`의 `FRED_API_KEY`/`ANTHROPIC_API_KEY`는 셸 환경변수로만 로드했으며, 본 문서·터미널 출력
어디에도 키 값(원문·부분 문자열 포함)을 기록하지 않았다.

## 9. 후속 규명 A — KRX 차단(§3 결함#3)의 성격 확정

**절차 1 — 쿨다운 후 재시도(2회, 3초 간격)**: `get_index_ohlcv_by_date('20240701','20240705','1001',
name_display=False)`를 3초 간격으로 2회 재시도 — **두 번 모두 `shape=(0, 0)`**, 회복 없음. 초 단위
쿨다운으로 풀리는 단순 레이트리밋은 아니다(단, 분·시간 단위 쿨다운 가능성까지는 배제 못함 — 그
정도로 기다려보는 것은 이번 조사 범위 밖).

**절차 2 — pykrx GitHub 저장소 확인**(`gh api repos/sharebook-kr/pykrx/...`, KRX가 아닌 GitHub
대상이라 K-03 무관): 결정적 증거를 발견했다.
- 설치본은 **v1.0.51**. 이후 릴리스 이력:
  - **v1.1.1**(2026-01-24) — "New 2026 Login Policy": "Referer 헤더 변경을 통해 특정 환경에서
    발생하던 로그인 차단 문제 우회"
  - **v1.2.8**(2026-05-04, 최신) — "KRX 로그인 정책 변경에 대응하는 세션 관리 시스템": `KRXSession`
    클래스로 `JSESSIONID` 쿠키 관리, 세션 만료(1시간) 추적·자동 재로그인, 자격증명은 환경변수
    `KRX_ID`/`KRX_PW`로 주입.
  - 즉 **2026년에 KRX가 로그인/세션 요건을 도입**했고, pykrx는 v1.1.1→v1.2.8에 걸쳐 이를 뒤늦게
    대응했다. 설치본 1.0.51은 이 대응 이전 버전 — 로그인/세션 처리가 전혀 없다.
- 관련 이슈: `#155`(get_market_cap 403), `#286`(2026-04 전반적 KRX 접속 불가 — 사용자가 "KRX 서버
  자체 장애"로 결론).

**절차 3 — KRX 실체 확인(이슈 #286이 제시한 진단법 재사용, 1회)**:
`https://data.krx.co.kr/contents/MDC/MAIN/main/index.cmd`(JSON API가 아닌 **메인 페이지**)에
직접 GET → **`403`**, 본문이 이전 §3에서 JSON 엔드포인트 호출 시 받은 것과 **바이트 단위까지 동일한
2421바이트 "Service unavailable / temporary access instability" 페이지**. 이는 pykrx나 인증과
무관하게 **`data.krx.co.kr` 도메인 전체가 현재 이 범용 403 오류 페이지를 반환 중**이라는 뜻이며,
이슈 #286에서 사용자들이 "한국 거주 IP / TLS 지문 위장 / 해외 클라우드 IP / 모바일 회선" 4가지
독립 경로로 교차검증해 "KRX 서버 자체 장애"로 결론낸 것과 동일한 패턴이다.

### 결론 — ②(버전 불일치) 확정 + ①(일시적 장애) 중첩 가능성 배제 못함

**①②③ 중 ②를 구조적 원인으로 확정**한다: KRX가 2026년에 로그인/세션 정책을 도입했고, 설치된
pykrx 1.0.51은 이를 전혀 지원하지 않는다(공식 체인지로그로 확정, 추측 아님). **다만 지금 이 순간의
403은 ①(KRX 측 일반 장애)이 동시에 겹쳐 있을 가능성을 배제할 수 없다** — 메인 페이지 자체가 API와
무관하게 동일 오류를 반환하고 있고, 이는 pykrx 저장소에 선례(#286)가 있는 "장애 시 확인법"과 정확히
일치하기 때문이다. ③(이 환경/프록시 고유 문제)은 근거가 없다 — FRED·yfinance(둘 다 별도 https
도메인)는 정상 응답했고, GitHub 이슈 #286은 국내/해외/모바일 등 이 환경과 무관한 다수 경로에서
동일 증상을 보고했다.

**대응 후보(결정은 Advisor·사용자 몫)**:
1. pykrx를 **v1.2.8**(2026-05-04, 최신 확인)로 업그레이드 — 단, `KRX_ID`/`KRX_PW`(KRX 사이트
   로그인 자격증명) 신규 확보·`.env` 등록이 전제 조건이다(K-17류 비밀값 관리 대상, 계정 신청 자체가
   Worker 권한 밖).
2. 업그레이드 후에도 재현되면 §9 절차 3의 "메인 페이지 상태 확인"을 다시 수행해 ①(KRX 측 장애)이
   현재도 겹쳐 있는지 먼저 배제할 것 — 장애 중이면 pykrx 문제와 무관하게 대기가 필요.
3. pykrx 메인테이너가 이슈 #286에서 직접 안내한 대안: `data.krx.co.kr`(비공식 스크레이핑 대상,
   "공공 데이터 홈페이지")가 아닌 **KRX 정식 Open API**(`openapi.krx.co.kr`) 전환 — 별도 소스이므로
   도입 여부는 Advisor·사용자 결정 사안.

## 10. 후속 규명 B — FRED `BAMLH0A0HYM2` 5창 empty 원인

**실호출 1 — w2011 창 정확한 수집 구간으로 재현**(`observation_start=2010-01-11,
observation_end=2011-09-15`, `w2011_us_downgrade.meta.json`의 `collected_range`와 동일):
응답 `status=200`, `count=0`, `n_observations=0` — 이 구간에는 **실제로 관측치가 없다.**

**실호출 2 — 날짜 제한 없이 전체 이력 조회**(`limit=5, sort_order=asc`, 날짜 파라미터 미지정):
```
count=795, observation_start=1600-01-01 (기본값), observation_end=9999-12-31 (기본값)
첫 관측치: {"date": "2023-08-01", "value": "3.82"}
```
**이 FRED 엔드포인트가 실제로 응답하는 `BAMLH0A0HYM2` 시리즈의 최초 관측일은 2023-08-01**이다
(795개 관측치 ≈ 2023-08-01~오늘까지의 영업일 수와 부합). "이 시리즈는 1996년부터 존재한다"는
전제(브리프의 판별 질문)는 **이 환경에서 실제로 호출되는 FRED 응답과 다르다** — 실측 결과가
우선한다.

**결론 — 코드 결함 아님**: `build_fixtures.py`의 FRED 요청 파라미터(`series_id`,
`observation_start/end`, `api_key`, `file_type=json`)와 `normalize_fred()`의 파싱(관측치
`{"date","value"}` 순회, `"."` 결측 마커 스킵) 모두 정상 동작이다. 2011·2015·2018·2020·2022
5창이 `empty`인 이유는 단순히 **창의 수집 시작일이 이 시리즈의 실제 데이터 시작일(2023-08-01)보다
이르기 때문**이다(2011~2022 다섯 창은 전부 2023-08-01 이전 구간). 2023-11·2024-05·2024-07·
2026-05 4창만 `ok`인 것과 정확히 일치한다. 재현 절차는 위 실호출 1·2 그대로이며, 수정 대상 코드는
없다 — 다만 `indicators.yaml`/`BACKTEST_PLAN.md`가 이 시리즈로 2011~2022 구간의 신용 축(`credit`
axis, `hy_oas_delta`)을 커버하려 했다면 그 가정 자체가 이번 실측과 어긋난다는 점은 Advisor 판단
사항으로 남긴다(수정 아님, 사실 보고).

## 11. 최종 재수집 — pykrx 1.2.8 인증, VKOSPI 종결, 9창 결측률 비교

### 11.1 인증 스모크

`.env`의 `KRX_ID`/`KRX_PW`를 셸 환경변수로만 주입(값 미출력) 후 `get_index_ohlcv_by_date('20240701',
'20240705','1001')` 1건 실호출 — **로그인 성공**(pykrx 1.2.8이 콘솔에 로그인 ID를 자체적으로
echo하는 것은 라이브러리 자체 동작이며, 본 문서에는 ID 값을 재기록하지 않는다), 데이터 정상 수신
(5행 7열: 시가/고가/저가/종가/거래량/거래대금/상장시가총액 — `상장시가총액`은 1.0.51 스키마에
없던 신규 컬럼이나 `_PYKRX_OHLCV_COLUMN_MAP`이 필요한 5개 필드만 골라 쓰므로 영향 없음).

### 11.2 K-02 VKOSPI 최종 확정

로그인 상태에서 `_resolve_vkospi_ticker()`(기존 코드, 무수정)를 그대로 호출 — 소요 4.9초(마켓
코드 01~04 대상 소수의 벌크 호출 + 인메모리 조회 패턴, K-03 위반 아님), **`ticker=None`** —
여전히 미해결.

마지막 1회 확인(브리프 지시 — 파생 지수 목록): 인덱스(현물) 티커 **168종 전량**을 `지수명`
컬럼으로 '변동성|VKOSPI|VIX' 정규식 스캔 → **0건**(현물 지수 자체는 없음 확정). 이어서
**선물(파생) 티커 목록**(`get_future_ticker_list`, 28종)도 확인 → **`KRDRVFUVKI` = "V-KOSPI
Futures"** 발견. 즉:
- **현물 VKOSPI 지수**: pykrx 인덱스 카탈로그에 없음 — `get_index_ohlcv_by_date` 계열(현재
  `dataset: vkospi` 코드 경로)로는 **확정적으로 조회 불가**.
- **V-KOSPI 선물**: 존재한다(`KRDRVFUVKI`) — 다만 이는 현물 지수가 아니라 그 위의 선물 상품이고,
  현재 코드 경로(`stock.get_index_ohlcv_by_date` 기반)는 이 티커를 다루도록 짜여 있지 않다(별도
  선물 OHLCV 함수·완전히 다른 정규화 로직 필요 — 코드 변경 없이는 활용 불가, 도입 여부는
  Advisor·사용자 판단 사항으로 남긴다).

**최종 결론**: 현재 아키텍처(현물 인덱스 API) 기준 VKOSPI는 **조회 불가 확정**(차단이 아니라 실제
미제공, §9의 로그인 성공으로 "차단 때문"이라는 가능성은 배제됨). `configs/sources.yaml`
`providers.pykrx.notes`를 이 최종 사실로 갱신했다(§11.7).

### 11.3 재수집 결과 — 9창 전체 status 표 (--force, 인증 적용)

| window_id | yfinance(6) | FRED T10Y2Y | FRED BAMLH0A0HYM2 | KRX:1001 | KRX:investor_foreign_kospi | KRX:VKOSPI | avg_missing_rate |
|---|---|---|---|---|---|---|---|
| w2011_us_downgrade | ok×6 | ok | **empty** | ok | ok | error | 47.06% |
| w2015_cny_deval | ok×6 | ok | **empty** | ok | ok | error | 47.14% |
| w2018_q4_tightening | ok×6 | ok | **empty** | ok | ok | error | 47.06% |
| w2020_covid | ok×6 | ok | **empty** | ok | ok | error | 47.06% |
| w2022_tightening | ok×6 | ok | **empty** | ok | ok | error | 47.06% |
| w2023_11_rally | ok×6 | ok | ok | ok | ok | error | 45.72% |
| w2024_05_calm | ok×6 | ok | ok | ok | ok | error | 43.73% |
| w2024_carry_unwind | ok×6 | ok | ok | ok | ok | error | 42.89% |
| w2026_structural | ok×6 | ok | ok | ok | ok | error | 41.30% |

(잔존 `error`는 9창 모두 `KRX:VKOSPI` 1개뿐 — §11.2로 종결. `avg_missing_rate`는
`uncollected`(P2/ECOS 예약, M0 범위 밖) 6계열을 포함한 `render_report()` 그대로의 값이며,
실제 "수집 가능한 계열 중 결측"은 사실상 VKOSPI 1건 + 구간별 BAMLH0A0HYM2뿐이다.)

### 11.4 재수집 전/후 비교

| 구분 | §0(1차, 미인증) | §11.3(재수집, 인증) |
|---|---|---|
| yfinance 6계열 | 9창 전부 empty(컬럼-매핑 버그) | 9창 전부 ok(버그 수정 확인) |
| KRX:1001 | 9창 전부 error(`KeyError: '지수명'`) | 9창 전부 ok |
| KRX:investor_foreign_kospi | 9창 전부 empty | 9창 전부 ok |
| KRX:VKOSPI | 9창 전부 error | 9창 전부 error(§11.2로 원인 성격 변경 — 차단→실제 미제공) |
| FRED BAMLH0A0HYM2 | 5창 empty(레이트리밋 의심, 미확정) | 5창 empty(§10로 데이터 시작일 2023-08-01 확정 — 코드/네트워크 문제 아님) |
| REPORT_fixtures.md | CLI 크래시로 미생성(우회 생성) | CLI 정상 완주로 생성 확인 |
| 프로세스 exit code | 1(크래시) | 0 |

### 11.5 확정된 실측 사실 — 이번 재수집분 (브리프 ①②③)

1. **2011·2015 창 KRX 과거 커버리지(신규 확정)**: 인증 후 전량 수신 확인.
   - w2011(수집구간 2010-01-11~2011-09-15): `KRX:1001` 2100행/결측 0%, `KRX:investor_foreign_kospi`
     420행/결측 0%.
   - w2015(수집구간 2014-01-11~2015-09-15): `KRX:1001` 2070행/결측 0%, `KRX:investor_foreign_kospi`
     414행/결측 0%.
   - 즉 pykrx는 **2010년 이후 KOSPI OHLCV·외국인 순매수대금을 완전 커버**한다(인증 전제).
2. **BAMLH0A0HYM2 empty 유지 확인**: 재수집(인증·재시도 무관)에서도 2011·2015·2018·2020·2022
   5창은 여전히 empty — §10에서 확정한 "이 환경 FRED 응답상 시리즈 시작일이 2023-08-01"이라는
   데이터 측 한계가 재확인됐다(코드·인증과 무관).
3. **w2026_structural 수집 종료일**: `definition.end=2026-08-15`(미래) → `collected_range.end=
   2026-08-02`(오늘) — §4-5와 동일하게 재확인.

### 11.6 검증

- `uv run ruff check backtest/` → All checks passed.
- `uv run pytest tests/test_fixture_schema.py -q` → **17 passed**(§7 당시 14개였던 것에서 증가 —
  구현자의 결함#1·#2 수정에 테스트 보강이 동반됐다), DeprecationWarning 4건(exchange_calendars 내부,
  본 브리프 범위 밖).
- `uv run pytest -q`(전체) → **89 passed**(§7 당시 76 대비 증가).
- 9개 parquet 전부 `validate_fixture()` 재확인 통과.
- `backtest/fixtures/REPORT_fixtures.md`가 이번 재수집 실행으로 **정상 생성**됨을 로그로 확인
  (결함#1 수정 후 `print(report)`가 더 이상 크래시하지 않음 — CLI 경로 자체가 정상 완주).

### 11.7 반영 사항

- `configs/sources.yaml` `providers.pykrx.notes` — §11.2 최종 사실로 갱신(그 외 configs 무변경).
- git 커밋 없음, `.env`의 `KRX_ID`/`KRX_PW`/`FRED_API_KEY` 값은 본 문서·터미널 캡처 어디에도
  기록하지 않았다(로그인 ID가 라이브러리 자체 콘솔 출력에 노출된 것은 pykrx 1.2.8의 내장 동작이며,
  이번 세션 코드로 재출력·재기록하지 않았다).

## 12. 결측 보고 재설계 반영 후 재수집 (aaa-critic 라운드 1 대응)

구현자가 결측 보고 구조를 재설계했다(평가구간×계열 달력(XNYS/XKRX/평일) 기준 결측률, 관측
고유일(`distinct_days`) 기준, 축 가중 커버리지 롤업, notable gaps 절, 캐시 키에 요청 구간 포함,
KRX 자격증명 사전 점검, `--help` 환경변수 명시). 캐시 키 변경으로 재실행은 전량 재수집이었다.

### 12.1 재수집 실행

`FRED_API_KEY`/`KRX_ID`/`KRX_PW` 주입 후 `uv run python backtest/build_fixtures.py --window all
--force` — 로그인 성공, 9창 전부 exit 0로 완주(rows 6403~7433, series=17 동일).

### 12.2 REPORT_fixtures.md 4요소 실재 확인

1. **①창×계열 표**: `series_id`별 `status/reason/missing_rate/last_as_of` — 신규 `reason` 분류
   3종 확인: `external_data_limit`(BAMLH0A0HYM2 구간 밖·KRX:VKOSPI), `out_of_scope`(ECOS·CDS),
   `disabled`(P2 예약 4종).
2. **②축 커버리지 롤업**: `configs/indicators.yaml` 가중치 기반 축별 `collected/total weight`.
   **구창 5개(2011·2015·2018·2020·2022) `credit` 축 = 0/6.5 = 0% 정확히 노출**됨(§10에서 확정한
   BAMLH0A0HYM2 데이터 시작일 2023-08-01 한계가 축 커버리지에 그대로 반영). 2023~2026 4창은
   `credit` 46%(BAMLH0A0HYM2만 가중 3.0/6.5, `krx_credit_spread_delta`(가중 2.0)·`kr_cds_5y_delta`
   (가중 1.5)는 M0 범위 밖이라 항상 0). w2026_structural은 `kr_flow_price` 0%(VKOSPI 에러 +
   `kospi_drawdown`/`foreign_net_sell_kospi`/`kospi_volume_distribution`이 이 시점 리포트에서
   가중치 배분상 0으로 잡힘 — 리포트 롤업 로직의 세부 배분은 구현자 영역이라 본 문서는 결과값만
   확인), `vol_global` 43%(^MOVE·^VIX3M 결측 반영).
3. **③notable gaps**: `w2026_structural | ^MOVE | 2026-07-20~2026-07-31 | 10 sessions` +
   `^VIX3M` 동일 구간 — **정확히 노출 확인**(§12.3에서 근본 원인 규명). 그 외
   `w2018_q4_tightening`/`w2022_tightening`의 `T10Y2Y`·`^MOVE` 1세션 공백(2018-10-08,
   2022-10-10 — 공휴일/단발 결측 추정, 범위 밖), w2026 KRX 계열 1세션 공백(2026-06-03).
4. **④문구·링크**: 리포트 머리에 "근사-PIT — C1에서 실측 확정" + "FRED lag_days 등 as-of 지연
   적용은 리플레이(MT0-04)로 이연 — 이 리포트는 관측일(as_of) 기준 원자료 커버리지만 다룬다" +
   본 저널 경로(`docs/journal/2026-08-02_MT0-03_fixture_collection.md`) 링크 — **3개 전부 확인**.

### 12.3 ^MOVE·^VIX3M 상류 결측 규명 (독립 절 — K-01/K-18 직결)

**실호출 1·2**: `yfinance.download("^MOVE", start="2026-06-15", end="2026-08-03")`,
`"^VIX3M"` 동일 구간(caret 접두 index 티커, 코드가 실제 쓰는 것과 동일 심볼).

**실제 반환(원문 날짜 목록)**:
- `^MOVE`: 2026-06-15부터 평일 기준 정상 간격으로 이어지다 **2026-07-17을 마지막으로 완전 절단**
  (shape=(23,6)). 07-20 이후 단 1행도 없음 — 07-28(붕괴 앵커일) 포함 폭락 구간 전체가 공백.
- `^VIX3M`: 동일하게 2026-07-17까지 정상, 그 뒤 **2026-07-31 단 1개 고립 관측치**만 존재
  (shape=(24,6)) — 08-01·08-02(오늘)에는 없음. 연속 재개가 아니라 단발 포인트.

두 계열이 **같은 날짜(07-17)에 동시에 끊긴다**는 점이 핵심 — MOVE(ICE BofA 지수)와 VIX3M(CBOE
지수)는 서로 다른 원천 지수인데 야후에서 동일 시점에 동시 절단된 것은 개별 지수 발행 중단보다
**야후 측 티커 처리/피드 변경**일 가능성을 시사한다(K-01 "비공식 API, 결측·지연 흔함"과 부합).

**교차 확인**:
- WebFetch로 `finance.yahoo.com/quote/%5EMOVE/history`, `%5EVIX3M/history` 접근 → **둘 다 HTTP
  503**(봇 차단으로 추정, 페이지 콘텐츠 확인 불가 — 결론에 반영하지 않음).
- WebSearch("^MOVE ^VIX3M yahoo finance 티커 discontinued 2026") → 상장폐지 공지 없음. 다만
  주목할 부수 발견: 야후에서 접두사 없는 "MOVE"(캐럿 없음)는 현재 **"Corvex, Inc."라는 전혀 다른
  주식 티커**로 리다이렉트됨(본 코드가 쓰는 "^MOVE" 인덱스 티커와는 별개 네임스페이스이나, 동일
  문자열의 티커 충돌이 존재한다는 사실은 기록해 둘 가치가 있음). VIX3M 관련 대체/파생 티커
  `^VX3MN`(Near-Term)·`^VX3MF`(Far-Term)의 존재도 검색에 노출되어 "티커 이관" 가설을 세워
  1회 추가 검증했으나, `yfinance.download`로 두 티커 모두 **"possibly delisted; no price data
  found"** — 이관처가 아니었다(가설 기각).

**결론**: ^MOVE·^VIX3M 모두 **야후 파이낸스 측에서 2026-07-17 이후 실제로 데이터가 끊겼다**(코드
버그·정규화 문제 아님 — 원본 자체가 없음). 일시 지연인지 영구 중단인지는 이번 실측(대체 티커
기각, 상장폐지 공지 부재, 웹 UI 직접 확인은 503으로 차단)으로는 **확정할 수 없다** — 추측으로
결론 내지 않는다. 부기(F4-1 재정정 시 확인): 2026-07 절단 이전에도 야후 ^MOVE에는 산발 상류
공백이 실재했다 — 2018-11-07·2018-12-06(채권시장 개장일인데 ^MOVE만 부재, §13.4 재정정 참조).
즉 야후의 ^MOVE 피드는 절단 이전부터 간헐 결측 이력이 있는 소스다(K-01/K-18 증거 보강). 이 공백은 M0 하니스 리포트(§12.2 ③)에만 그치지 않고 **서버 P1 운영의 K-01
stale 정책**(`configs/indicators.yaml` `engine.stale_profiles`)과 **모바일 K-18 Stooq 폴백
설계**(vol_global 축, `move_index_z`·`vix_term_structure` 지표) 양쪽에 직접 영향을 준다 —
두 지표 모두 이 상태가 지속되면 stale 임계를 넘겨 결측 처리(엔진 `missing_data_policy:
exclude_from_denominator`)될 것이며, Stooq가 MOVE/VIX3M 같은 지수 티커를 다루는지도 K-18
실측 시 함께 확인이 필요하다(본 세션 범위 밖, Advisor 판단 사항으로 남긴다).

### 12.4 결측 원인 최종 3분류

| 분류 | 해당 계열 | 근거 | 코드 조치 필요 여부 |
|---|---|---|---|
| **A. 데이터 자체 한계**(영구, 코드 무관) | FRED `BAMLH0A0HYM2`(구창 5개, 시작일 2023-08-01) | §10 실측 확정 | 없음 — 지표 채택 범위 재검토는 Advisor 사안 |
| **B. 카탈로그 부재**(pykrx에 해당 자산 없음) | `KRX:VKOSPI` | §11.2 실측 확정(168 인덱스+28 선물 스캔) | 없음 — fallback `realized_vol_kospi_20d` 확정 |
| **C. 상류 피드 공백**(원인 미확정, 코드 무관이나 지속 관찰 필요) | `^MOVE`·`^VIX3M`(2026-07-20~) + `^MOVE` 2018-11-07·2018-12-06(2건, 라운드 4 재정정 — 채권휴장 아닌 진짜 상류 공백, T10Y2Y 포함 형제 전 계열 발행일) | §12.3 실측(원본 자체 없음, 원인 미확정); 2018 2건은 §13.4(F4-1 해소, REVIEW_M0.md MT0-03 라운드4, MT0-06 O4-D 갱신) | 코드 수정 아님 — stale 정책·K-18 폴백 설계에 반영 필요(Advisor 판단) |

세 분류 모두 **`build_fixtures.py`/`fixture_schema.py` 코드 결함이 아니다** — 재설계된
`reason` 필드(`external_data_limit` 등)로 REPORT에 이미 분류되어 있으며, 이번 §12는 그 분류가
실측과 일치함을 재확인한 것이다.

### 12.5 검증

- `uv run ruff check backtest/` → All checks passed.
- `uv run pytest tests/test_fixture_schema.py -q` → **27 passed**(§11.6 당시 17개 대비 증가 —
  XNYS/XKRX 달력별 `expected_sessions` 테스트 등 재설계 검증 보강).
- 9개 parquet 전부 `validate_fixture()` 재확인 통과.
- `REPORT_fixtures.md` 재생성 확인 — §12.2에 4요소 실재 확인 기록.

### 12.6 반영 사항

configs 무변경(§11.2 이후 추가 변경 없음), git 커밋 없음, API 키·KRX 자격증명 값 미노출.

## 13. 라운드 3 — 경험적 달력 전환 (aaa 라운드 2 NEW-1/2/3 대응), 재산출

### 13.1 설계 전환 요지

aaa-critic 라운드 2 FAIL: **NEW-1** `exchange_calendars`의 XKRX 캘린더가 2026년 임시공휴일 등을
누락해(패키지 자체가 미래·최신 KRX 휴장일을 다 못 따라감) 실제로는 정상 거래일인 날을 "휴장"으로
오판 → 그 날 값이 있는데도 "결측"으로 잡히는 **유령 결측**이 생기고, 이진(all-or-nothing) 롤업이
이를 증폭. **NEW-2** FRED 계열이 US 주식시장 캘린더(XNYS)에 배정되어, 채권시장 휴장일(콜럼버스데이·
재향군인의날 등 — 주식시장은 개장, FRED 채권금리 시리즈는 미발행)에 실제로는 정상인데 XNYS 기준
결측으로 오판. **NEW-3** 위 규칙이 리포트에 문서화되어 있지 않았음.

구현자의 전환(무수정 확인만, 코드는 구현자 소관): **외부 달력 패키지 제거**, 기준 세션을
`calendar_kind`(krx/us_market/fred/fx) 그룹별로 **그 그룹 소속 전 계열이 평가구간 내 실제로 반환한
관측일의 합집합**으로 대체(exchange_calendars 등 외부 정적 데이터 의존 없음 — "경험적" 기준).
롤업도 이진 판정에서 **비례(가중 가용도 평균)** 로 전환. 리포트에 §0 규칙 범례·§1 평가구간(클램프
명기)·registry_version 스탬프·전체 gap span 목록을 추가.

### 13.2 재산출 실행 — 캐시 히트 확인

`FRED_API_KEY`/`KRX_ID`/`KRX_PW` 주입 후 **`--force` 없이** `uv run python backtest/build_fixtures.py
--window all` 실행 — exit 0, 9창 전부 완주(rows 값이 §12와 완전 동일: 6819/6749/7213/7050/7433/
6486/6403/6515/7308 — 원자료 재수집이 아니라 캐시 원자료 재사용 확인).

**캐시 히트 실측 확인**: `backtest/fixtures/_cache/w2026_structural/`의 신규 캐시 키 파일
(`*__2024-11-11_2026-08-02_p550.*`, 구간 스탬프 포함)은 전부 mtime **19:38**(§12 실행 시각)에
머물러 있고, 이번 실행 시각(20:20)에 재기록된 파일이 **하나도 없음** — 100% 캐시 히트. "오늘"이
2026-08-02에서 넘어가지 않아 `w2026_structural`의 `collect_end`가 §12와 동일했기 때문(브리프가
예고한 "날짜가 넘어가면 그 창만 미스" 조건은 발생하지 않음). 유일한 실네트워크 호출은 pykrx
`KRXSession` 로그인(모듈 임포트 시 무조건 수행 — 자격증명 값은 미노출).

(부수 관찰: 구 캐시 키 형식 파일 `yfinance__GSPC.parquet` 등 — 구간 스탬프 없음, §11 이전 잔존물
— 이번 실행에서 참조되지 않는 고아 파일. 삭제는 코드/파일시스템 정리 사안이라 보고만 한다.)

### 13.3 NEW-1 반증 — w2026 KRX 유령 결측 소멸

`w2026_structural.meta.json` 재확인: `KRX:1001`, `KRX:investor_foreign_kospi` 모두
**`missing_rate=0.0`, `gaps=[]`**(§12에서는 2026-06-03 1세션 유령 gap으로 `missing_rate=3.64%`
였음 — 소멸 확인). 리포트 §3 축 롤업에서 `w2026_structural | kr_flow_price | 6.00/8.50 | 71%`
— **0%가 아닌 실질값**(71%)으로 노출됨을 확인. 71%가 100%가 아닌 이유는 유령 결측이 아니라
**구조적 사유**(`vkospi_z` 지표가 쓰는 `KRX:VKOSPI`가 §11.2에서 확정한 카탈로그 부재로 가용도 0 —
`kr_flow_price` 축 가중치 8.5 중 `vkospi_z` 비중 2.5를 제외한 나머지가 전부 100%이므로
(8.5-2.5)/8.5 ≈ 70.6% ≈ 71%) — 다른 8개 창(2011~2024)의 `kr_flow_price` 71%와 정확히 동일한
값인 것도 이 구조적 상한이 창마다 일정하다는 방증이다.

### 13.4 NEW-2 반증 — w2018/w2022 FRED 유령 gap 소멸

`w2018_q4_tightening`·`w2022_tightening` 리포트 §4(notable gaps)·meta 재확인:
`T10Y2Y`는 **두 창 모두 `gaps=[]`, `missing_rate=0.00%`**(§12에서 `T10Y2Y`도 `^MOVE`와 나란히
2018-10-08/2022-10-10 1세션 gap이 잡혔던 것 — 이번엔 gap 목록에서 완전히 빠짐, 소멸 확인).
같은 날짜에 `^MOVE`(us_market kind)만 여전히 gap으로 남아 있는 것은 유령이 아니라 실질적 그룹
분리 효과다: FRED 계열은 이제 fred kind 자체 합집합 대비 결측률을 매기므로, 채권시장 휴장일에
"전 FRED 계열이 다 안 나온 날"은 애초에 합집합에서 빠져 결측으로 안 잡힌다. 반면 `^MOVE`는
us_market kind 내 다른 계열(VIX/GSPC/DX-Y.NYB, 이들은 주식시장 기준이라 그날 개장)이 정상 발행한
날에 혼자 비어서 gap으로 남는다.

> **[재정정 — aaa-critic F4-1 실측, 2026-08-02 라운드 4 해소]** 위 문단의 "MOVE 자체의 결측은
> 실측대로 유지됨"은 부정확한 서술이었고, 라운드 4의 1차 정정문 또한 사실오류를 담고 있었다
> (^MOVE gap 6건을 "4건"으로 표기하며 전부 채권휴장으로 귀속). 정확한 분류는 다음과 같다 —
> w2018/w2022의 `^MOVE` gap은 **총 6건**이며 성격이 둘로 갈린다:
> - **채권시장 휴장 4건** (2018-10-08 콜럼버스데이, 2018-11-12 재향군인의날 대체, 2022-10-10,
>   2022-11-11): 같은 날 `T10Y2Y`(fred kind의 채권계 형제)도 미발행 — 주식시장 형제
>   (VIX/GSPC/DX-Y.NYB)만 개장한 날이라 §0 범례의 보수적 편향(O3-B) 규칙이 정직하게 작동한
>   것이다. **데이터 공백이 아니다.**
> - **진짜 야후 상류 공백 2건** (2018-11-07, 2018-12-06): 같은 날 `T10Y2Y`·`DX-Y.NYB`·`^GSPC`·
>   `^VIX`·`^VIX3M` **전부 발행**하고 `^MOVE`만 부재 — 채권시장 개장일이다(SIFMA 2018 전면휴장은
>   10-08·11-12이고 12월 휴장은 12-05 부시 전 대통령 애도일, 12-06은 SIFMA가 결제일로 지정한
>   개장일). §12.4 분류 **C(상류 피드 공백)** 에 속하는 실공백으로, 2026-07 절단 이전에도 야후
>   ^MOVE에 산발 공백이 실재했다는 K-01/K-18 증거다.
>
> **판별 규율(재발 방지)**: 결측 원인을 휴장/상류절단으로 귀속하는 모든 서술은 **같은 kind 형제
> 계열(채권계는 T10Y2Y 등)의 당일 발행 여부**를 증거로 함께 제시한다 — 이 규율을 1차 정정문에
> 적용만 했어도 본 오류는 즉시 걸러졌다.
>
> w2026_structural의 `^MOVE`·`^VIX3M` 2026-07-20~07-31(10 sessions)은 §12.3에서
> `yfinance.download` 날짜 범위 직접 조회로 **원본 자체가 없음을 독립 확인한 진짜 상류 절단**이다
> — 휴장 편향과 상류 공백은 원인이 다르며 혼동해서는 안 된다.

### 13.5 실공백 보존 확인 (핵심 회귀 확인 — 경험적 달력이 진짜 공백까지 지우지 않았는가)

`w2026_structural` `^MOVE`·`^VIX3M`: `missing_rate=20.37%`(§12 시점 18.87%에서 분모가
경험적 합집합(54일)으로 바뀌며 소폭 변동 — 실공백 자체는 그대로), `gaps`에
**`2026-07-20~2026-07-31, 10 sessions`가 그대로 존재**(§12.3에서 규명한 야후 상류 절단과 정확히
일치) + `2026-05-25` 1세션 gap(us_market kind 내 신규로 드러난 개별 공백, 범위 밖) 추가.
**결론: 경험적 달력 전환이 진짜 공백을 지우지 않았다** — NEW-1/2가 잡던 것은 어디까지나 유령
(귀속 오류)뿐이었고, §12.3에서 실측 확정한 야후 데이터 자체의 절단은 그대로 표면화되어 있다.

### 13.6 리포트 4요소 실재 확인

1. **§0 규칙 범례**: "기준 세션(missing_rate 분모)은 ... 외부 달력이 아니라 ... 실제 반환한
   관측일의 합집합이다 ... 유령 결측이 둔갑하는 경로를 원천 차단" + 비례 롤업 공식 + fx 단일계열
   advisory 예외 규칙 — 3개 규칙 전부 명문화 확인.
2. **§1 평가구간**: `w2026_structural | 2026-05-15~2026-08-15(정의) | 2026-05-15~2026-08-02(eval)
   | 정의상 종료일이 오늘 기준으로 클램프됨` — 클램프 사실이 표 안에 명기됨 확인.
3. **registry_version 스탬프**: 리포트 머리에 "지표 레지스트리 버전: 0.1.0 (SSOT:
   configs/indicators.yaml)" — `indicators.yaml`의 `registry_version: 0.1.0`과 일치 확인.
4. **전체 gap span 목록**: §4가 "notable gaps"에서 "평가구간 내 전체 공백 span 목록"으로 개칭되고,
   실제로 이전엔 노출되지 않던 다건(2018년 11-07·11-12·12-06, 2026년 05-25·05-31·06-19·07-03 등)
   1세션 공백까지 전부 나열됨 확인(§12는 최장 gap 1개만 보였으나 이번은 전체 목록).

### 13.7 재산출 후 창×축 커버리지 최종표

| window_id | credit | global_price | kr_flow_price | rates_fx | vol_global |
|---|---|---|---|---|---|
| w2011_us_downgrade | 0% | 100% | 71% | 100% | 100% |
| w2015_cny_deval | 0% | 100% | 71% | 100% | 100% |
| w2018_q4_tightening | 0% | 100% | 71% | 100% | 99% |
| w2020_covid | 0% | 100% | 71% | 100% | 100% |
| w2022_tightening | 0% | 100% | 71% | 100% | 100% |
| w2023_11_rally | 46% | 100% | 71% | 99% | 100% |
| w2024_05_calm | 46% | 100% | 71% | 100% | 100% |
| w2024_carry_unwind | 46% | 100% | 71% | 100% | 100% |
| w2026_structural | 45% | 98% | 71% | 98% | 88% |

(`credit` 0%/46%는 §10 확정 FRED 데이터 시작일(2023-08-01) 경계와 정확히 일치. `kr_flow_price`
71%는 전 창 공통 — §13.3의 VKOSPI 구조적 상한. `w2026_structural`의 `vol_global` 88%는
^MOVE·^VIX3M 실공백(§13.5) 반영.)

### 13.8 검증

- `uv run ruff check backtest/` → All checks passed.
- `uv run pytest tests/test_fixture_schema.py -q` → **33 passed**(§12.5 27개 대비 증가 — 경험적
  달력·비례 롤업 검증 테스트 보강).
- 9개 parquet 전부 `validate_fixture()` 재확인 통과, rows 값 §12와 동일(재수집 아님 재확인).

### 13.9 반영 사항

configs 무변경, git 커밋 없음, `.env`의 `FRED_API_KEY`/`KRX_ID`/`KRX_PW` 값은 본 문서·터미널
캡처 어디에도 기록하지 않았다.

## 14. 라운드 4 — F3-1 표적 수정(사용자 승인), O3-A 정정, 재산출

### 14.1 요지

aaa-critic 라운드 2에서 함께 지적된 **F3-1**(빈 합집합 시 결측을 0%로 두면 "데이터가 하나도 없을
수록 커버리지가 오른다"는 비단조 오류 — 예: KRX 자격증명이 아예 없어 그 kind 전 계열이 공백이면
분모·분자가 둘 다 0이 되어 결측률이 0%로 계산될 위험)를 구현자가 표적 수정 완료. **사용자 승인
경위**: 이 수정은 Advisor가 아닌 사용자에게 직접 보고되어 승인된 뒤 반영됐다(브리프 전달 기준).
현 9창은 이미 KRX 로그인 성공(§11) 상태라 F3-1이 실제로 발현되는(빈 합집합이 생기는) 경우가
없으므로, 수치는 **불변이 정상**이라는 것이 이번 검증의 전제다.

### 14.2 재산출 실행 — 캐시 히트 + 수치 불변 확인

`--force` 없이 재실행 — exit 0, rows 값 §12·§13과 완전 동일(6819~7308). `_cache/w2026_structural/`
신규 캐시 키 파일 mtime 전부 §12 실행 시각(19:38)에 머묾, 이번 실행 시각(20:54)에 재기록된 파일
없음 — **100% 캐시 히트**(날짜가 2026-08-02에서 넘어가지 않아 미스 조건 미발생).

**리포트 §2·§3 수치 전수 대조**: §13.7 최종표(9창×5축)와 이번 라운드 리포트 §3을 라인 단위로
대조 — **완전 일치**(모든 axis coverage weight/total·% 값 동일: 예 `w2011_us_downgrade credit
0.00/6.50 0%`, `w2026_structural vol_global 6.19/7.00 88%` 등). §2 창×계열 표의 `missing_rate`도
전 항목 동일. **F3-1 비발현 확인 — 예고대로 수치 변화 없음.**

### 14.3 리포트 3요소 실재 확인

1. **§0 범례 F3-1 규약**: "kind 내 전 계열이 평가구간 내 공백이면(예: KRX 자격증명 미설정) 합집합
   자체가 비어 기준 세션이 없다 — 이 경우 그 kind 소속 전 계열은 결측 100%로 계상한다 (F3-1: 빈
   합집합을 결측 0%로 두면 데이터 손실이 클수록 커버리지가 오르는 비단조 오류가 된다)" — 원문
   그대로 확인.
2. **O3-B 보수 편향 문구**: "형제 계열이 관측을 발행한 날에 특정 계열만 미발행이면, 그 계열은 그
   날 결측으로 계상된다(보수적 편향 — 형제가 없으면 애초에 그 날이 기준 세션에 들어오지 않는다)"
   — 원문 그대로 확인(§13.4 정정의 근거 규칙).
3. **§1 anchor_hint 열**: `w2026_structural | 2026-05-15~2026-08-15 | 2026-07-28 | ...` —
   `windows.yaml`의 `anchor_hint: 2026-07-28`과 일치 확인. 음성 창(w2024_05_calm·w2023_11_rally)은
   `anchor_hint = -`로 정확히 표기(원래 `null`).

### 14.4 §13.4 O3-A 정정 반영 → F4-1 재정정 (2026-08-02)

1차 정정문(라운드 4)은 ^MOVE gap 6건을 "4건"으로 표기하며 전부 채권휴장으로 귀속하는 사실오류가
있었다(aaa-critic F4-1). 재정정 확정 내용: w2018/w2022 `^MOVE` gap 6건 = **채권휴장 4건**
(2018-10-08·11-12, 2022-10-10·11-11 — T10Y2Y 동반 미발행, O3-B 보수적 편향, 데이터 공백 아님)
+ **진짜 야후 상류 공백 2건**(2018-11-07·12-06 — T10Y2Y 포함 형제 전 계열 발행, §12.4 C분류).
w2026의 07-20~07-31(10 sessions)은 §12.3 독립 확인된 진짜 상류 절단. 판별 규율("귀속 서술은
같은 kind 형제 계열의 당일 발행 여부를 증거로 제시")을 §13.4에 물질화했다.

### 14.5 검증

- `uv run ruff check backtest/` → All checks passed.
- `uv run pytest tests/test_fixture_schema.py -q` → **37 passed**(§13.8 33개 대비 증가 — F3-1
  빈 합집합 케이스 테스트 보강).
- 9개 parquet 전부 `validate_fixture()` 재확인 통과, rows 값 3라운드 연속 동일.

### 14.6 반영 사항

configs 무변경, git 커밋 없음, `.env`의 `FRED_API_KEY`/`KRX_ID`/`KRX_PW` 값 미노출.
