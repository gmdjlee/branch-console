# MT1-00a — 야후 공개 REST·Stooq 폴백 실측

- 작성: 2026-08-07, data-verifier Worker
- 대상: `configs/sources.yaml`(`yfinance`, `stooq` provider) · `configs/indicators.yaml`
  야후계 심볼(^VIX, ^VIX3M, ^MOVE, ^GSPC, DX-Y.NYB, KRW=X) — M1 모바일 네이티브 REST 호출·
  Stooq 폴백(K-01/K-18) 실측
- 실호출: 야후 9회(계열당 1~2회), Stooq 5회(엔드포인트 자체를 좁히는 소량 탐색) — 과호출 없음

## 0. 결론 (요지)

1. **야후 chart API는 crumb/쿠키 없이 UA 헤더만으로 6개 심볼 전부 200 응답** — 모바일 네이티브
   REST 직접 호출로 문제 없다.
2. **^MOVE·^VIX3M는 2026-07-17 이후 일별 종가 갱신이 정지된 상태가 오늘(2026-08-07 조회 기준)도
   그대로다.** M0에서 관측한 절단이 해소되지 않았다 — 중간 구간이 통째로 null이고, 최신
   타임스탬프 슬롯에는 정산된 일봉이 아니라 실시간 스냅샷 1건만 담긴다.
3. **Stooq CSV 폴백(`stooq.com/q/d/l/`)이 JS PoW(작업증명) 안티봇 챌린지로 차단됐다.** HTTP
   200이지만 body가 CSV가 아니라 브라우저에서 JS를 실행해야 풀리는 챌린지 페이지다. 심볼별
   문제가 아니라 **엔드포인트 전체가 차단**돼 있어(3개 심볼 동일 패턴 확인), 심볼 매핑표를 만드는
   것 자체가 무의미하다. 모바일 네이티브(JS 엔진 없음)로는 우회할 수 없다 — **K-18 폴백 경로가
   현재 작동하지 않는다.** 이 사실은 Advisor 에스컬레이션이 필요하다(§4).

## 1. 야후 chart API 실측

**엔드포인트**: `GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range={r}&interval={i}`
(symbol은 URL-encode, 예: `^GSPC` → `%5EGSPC`)

**요청 형식**: `User-Agent` 헤더만 필요. crumb·쿠키 없이도 아래 6개 심볼 전부 HTTP 200 확인.

| 심볼 | range/interval | 결과 | 비고 |
|---|---|---|---|
| `^GSPC` | 5d/1d | 200, 정상 5봉 | open/high/low/close/volume/adjclose 전부 존재 |
| `^VIX` | 5d/1d | 200, 정상 5봉 | volume은 항상 0(지수 특성, 이상 아님) |
| `DX-Y.NYB` | 5d/1d | 200, 정상 4봉(휴장 1일 제외) | 동일 구조 |
| `KRW=X` | 5d/1d | 200, 정상 6틱 | **high/low/close 전부 존재**(`usdkrw_intraday_force`
  modifier가 요구하는 필드 충족 확인). `meta.currency="KRW"`, volume은 항상 0 |
| `^MOVE` | 1mo/1d | 200, **결측 구간 확인**(아래 §2) | |
| `^VIX3M` | 1mo/1d | 200, **결측 구간 확인**(아래 §2) | |

**응답 구조**(공통):
```
chart.result[0].meta = {
  currency, symbol, exchangeName, instrumentType, regularMarketTime(unix),
  regularMarketPrice, regularMarketDayHigh, regularMarketDayLow, chartPreviousClose,
  dataGranularity, range, validRanges: [...]
}
chart.result[0].timestamp = [unix, ...]                       # 봉별 타임스탬프
chart.result[0].indicators.quote[0] = { open[], high[], low[], close[], volume[] }
chart.result[0].indicators.adjclose[0].adjclose = [...]
```
결측 봉은 각 배열에서 해당 인덱스가 `null`(배열 길이는 timestamp와 항상 동일하게 유지됨 — 파서가
길이로 정렬 가능, null 스킵 금지).

**range 상한**: `meta.validRanges`로 실측(6개 심볼 전부 동일) —
`["1d","5d","1mo","3mo","6mo","1y","2y","5y","10y","ytd","max"]`. 설계상 사용하는 것은
`interval=1d`뿐(모든 지표가 daily 봉 기반이며, `usdkrw_intraday_force`의 "일중 변동폭"도 오늘자
미확정 일봉의 `high/low`가 장중 갱신되는 것을 반복 폴링으로 관측하는 방식 — 별도 분(`1m`/`30m`)
interval 호출이 필요 없다). 분 단위 interval의 상한(1m=7일, 다른 분 단위=60일 등 야후의 알려진
제약)은 이번 설계가 쓰지 않으므로 실측 대상에서 제외했다(불필요한 실호출 회피).

**오류 형식 실측**(존재하지 않는 심볼 1회 호출):
```
HTTP 404
{"chart":{"result":null,"error":{"code":"Not Found","description":"No data found, symbol may be delisted"}}}
```
`HTTP status + chart.error.code/description` 조합으로 명확히 분류 가능. 429(레이트리밋)는
고의로 유발하지 않았다(브리프의 "과호출 금지" 원칙 — 강제 유발용 반복 호출은 계열당 실측 목적에
부합하지 않는다). MT1-04a 구현 시 429/5xx는 `retry` 정책(sources.yaml 기존 backoff)으로
일반화 처리하면 된다.

## 2. ^MOVE·^VIX3M 절단 재확인

`range=1mo&interval=1d` 응답의 `timestamp`/`quote` 배열을 날짜로 환산한 결과:

- 마지막으로 **정산된 정상 일봉**: 2026-07-17 (`open/high/low/close` 전부 non-null, volume=0은
  정상)
- 2026-07-20 ~ 2026-08-05(13개 타임스탬프 슬롯) 전 필드 **null**
- 최신 슬롯(2026-08-06)만 값이 있으나, `close`/`high`/`low` 값이 `meta.regularMarketPrice`
  /`regularMarketDayHigh`/`regularMarketDayLow`와 **정확히 일치** — 이는 그날의 정산된
  일봉이 아니라 **조회 시점의 실시간 스냅샷을 마지막 슬롯에 그대로 얹은 것**이다(야후가 이
  두 심볼의 일별 히스토리 집계를 더 이상 갱신하지 않고 있다는 뜻).

M0(2026-07-17 이후 절단 관측)과 완전히 동일한 상태가 3주 뒤 오늘도 지속. **일시적 지연이 아니라
구조적 단절로 간주해야 한다.** `^VIX`, `DX-Y.NYB`, `^GSPC`, `KRW=X`는 동일 기간 정상 갱신 확인
(§1) — 야후 API 전체 장애가 아니라 이 두 틱커 개별 문제.

**indicators.yaml 영향**: `move_index_z`(weight 1.5)는 K-01 stale 정책(server_intraday
daily_us: 36h, mobile_daily daily_us: 48h)의 stale 임계를 이미 훨씬 초과한 결측이 계속 발생 →
분모 제외(`missing_data_policy: exclude_from_denominator`)로 흡수되는 것이 현재도 맞는 처리다.
설정 변경 불필요, 다만 이 지표가 사실상 상시 결측 상태라는 점은 C1 재검토 시 weight 재배분
후보로 기록해 둔다(코드/설정 변경은 이번 과업 범위 밖).

## 3. Stooq CSV 폴백 실측

**시도 1 — 벌크 다운로드 엔드포인트**: `GET https://stooq.com/q/d/l/?s={symbol}&i=d`

| 심볼 시도 | HTTP | Content-Type | 결과 |
|---|---|---|---|
| `^spx` | 200 | `text/html` | JS PoW 챌린지 페이지 (CSV 아님) |
| `^vix` | 200 | `text/html` | 동일 챌린지 페이지(다른 nonce) |
| `usdkrw` | 200 | `text/html` | 동일 챌린지 페이지(다른 nonce) |

응답 본문 예(공통 패턴):
```html
<script nonce="...">
(async()=>{const c="...",d=4,t="0".repeat(d),e=new TextEncoder;let n=0;while(1){cons...
```
SHA류 해시 challenge-response(작업증명) — 브라우저의 JS 실행 없이는 통과 불가. 응답 헤더에서
`Content-Security-Policy: script-src 'nonce-...'; connect-src 'self'`까지 확인 — 챌린지를
우회하는 별도 API 콜(예: XHR로 직접 토큰 발급받기)도 CSP가 `'self'`로 제한해 막아둔 구조다.

3개 심볼에서 **동일한 차단 패턴**이 나왔으므로, 이는 심볼별 문제가 아니라 **엔드포인트 전체에
걸린 안티봇 게이트**다. 따라서 나머지 심볼(`^move`, `^vix3m`, DXY 후보)에 대한 심볼명 탐색은
실행하지 않았다 — 엔드포인트가 막힌 상태에서 심볼 매핑을 확정하는 것은 의미가 없고, 추가 호출은
브리프의 "실호출 최소화" 원칙에 반한다.

**시도 2 — 대체 경량 엔드포인트 탐색(1회)**: `GET https://stooq.com/q/l/?s=^spx&f=sd2t2ohlcv&h&e=csv`
→ HTTP 404 ("The page you requested does not exist") — stooq.com 도메인에는 이 경로 자체가
없다(다른 stooq 도메인/경로 조합이 있을 수 있으나 추측 탐색을 더 진행하지 않았다 — 아래 §4).

**결론**: 현재(2026-08-07) `stooq.com`의 공개 CSV 엔드포인트는 **일반 HTTP GET으로 조회 불가**.
모바일 네이티브(Kotlin, JS 엔진 없음)로는 이 챌린지를 풀 방법이 없으므로, K-18에서 설계한
"야후 실패 시 Stooq 폴백" 경로는 **현재 시점 기준 작동하지 않는다.**

## 4. 시도하지 않은 것 / fallback 미활성화 사유 (추측 금지)

- Stooq의 심볼 매핑표(예: `^MOVE`, `^VIX3M`, DXY 후보 심볼명 확정): **미시도**. 엔드포인트
  자체가 막혀 있어 매핑표를 채워도 검증 불가능한 추측이 되므로 작성하지 않았다.
- Stooq의 다른 서브도메인(예: `stooq.pl`) 또는 비공개 API 경로 탐색: **미시도**. 브리프 범위는
  "Stooq CSV 폴백" 실측이며, 공식적으로 알려진 진입점(`stooq.com/q/d/l/`)이 막혔다는 사실 확인이
  이번 과업의 핵심 결론이다. 대체 진입점 존재 여부는 별도 조사 과업(Advisor 승인 필요)으로
  남긴다.
- 이 브리프에는 K-18을 대체할 **지정된 3차 fallback 경로가 없다**(K-02의 `realized_vol_kospi_20d`
  같은 사전 정의 대체가 K-18에는 없음). 따라서 "fallback 활성화"를 수행하지 않았고, 대신
  `configs/sources.yaml`의 `stooq` provider 주석에 실측 사실과 미해결 상태를 기록했다(§5).
  코드값·심볼명을 추측으로 채우지 않았다.

## 5. 확정 반영

- `configs/sources.yaml`
  - `providers.yfinance.notes`에 2026-08-07 실측 결과 추가(crumb 불요, MOVE/VIX3M 절단 재확인,
    본 문서 링크).
  - `providers.stooq.notes`에 2026-08-07 실측 결과 추가(PoW 차단, 심볼 무관 전체 차단, 폴백
    미작동 상태, 본 문서 링크). 구조(키·필드) 변경 없음 — 기존 컨벤션대로 `notes` 문자열만 갱신.
- `configs/indicators.yaml` — 무변경(이번 과업 범위 아님, VERIFY 필드 없음).

## 6. MT1-04a 구현 계약 초안 (참고용, 이 문서 안에만 기록 — 별도 파일 생성 없음)

**요청 규격**:
- `GET https://query1.finance.yahoo.com/v8/finance/chart/{urlencode(symbol)}?range=5d&interval=1d`
- 헤더: `User-Agent: <일반 브라우저 UA 문자열>` 1개만 필수. crumb/쿠키 로직 불필요(구현 단순화).
- 파싱: `chart.result[0].timestamp[i]` ↔ `indicators.quote[0].{open,high,low,close,volume}[i]`를
  인덱스로 zip. `null` 값은 해당 날짜 결측으로 기록(스킵하지 말고 명시적 결측 레코드로 lake에 append
  — PIT 원칙).
- `KRW=X`의 `usdkrw_intraday_force`는 최신(오늘) 슬롯의 `high/low/close`를 30분 주기로 재폴링해
  갱신되는 값으로 판정(별도 intraday interval 호출 불필요, §1 근거).

**오류 분류**:
- HTTP 200 + `chart.error == null` → 정상.
- HTTP 404 + `chart.error.code == "Not Found"` → 심볼 없음/상장폐지, 재시도 금지, 즉시 결측 기록.
- HTTP 429/5xx → `sources.yaml.providers.yfinance.retry`(attempts 3, backoff 5/30/120s) 그대로
  적용.
- `chart.result[0]`은 존재하지만 최신 구간이 전부 `null`(^MOVE·^VIX3M 패턴) → 예외로 전파하지 말고
  stale 정책(K-01, indicators.yaml engine.stale_profiles)에 위임 — 이미 구현 방향이 맞다.

**폴백 매핑**: 현재 **없음**(§3, §4). MT1-04a는 Stooq 폴백 호출부를 스텁으로 두거나(호출 시도 →
실패 → 결측 기록) Advisor가 대체 소스를 확정할 때까지 보류하는 것 중 택1을 Advisor가 결정해야
한다 — 이 문서는 그 결정에 필요한 사실만 제공한다.

## 7. 검증

- 실호출 총량: 야후 9회, Stooq 5회(계열당 1~3회 제약 준수, 표 §1·§3 참조).
- `.env`·비밀값 미노출(이번 과업은 인증 불필요 공개 엔드포인트만 사용).
- `uv run pytest -q` → 178 passed(무변경 확인 — `sources.yaml` notes 문자열 수정은 스키마
  테스트가 키 존재만 검사하므로 회귀 없음, `tests/test_configs_schema.py:56` 확인).
- git status는 Advisor 보고에 원문 첨부.

## 8. 생성/변경 파일 목록

- `docs/journal/2026-08-07_MT1-00a_yahoo_stooq.md` (본 문서, 신규)
- `configs/sources.yaml` (수정 — `yfinance.notes`, `stooq.notes` 실측 결과 추가, 구조 변경 없음)
