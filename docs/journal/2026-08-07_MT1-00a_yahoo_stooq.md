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

## 9. 후속 실측(2026-08-07, Advisor 승인) — FRED 미러 계열의 K-18 대체 가능성

**배경**: §3~§4에서 Stooq CSV 폴백이 전면 차단(PoW)됨을 확인했고, 지정된 3차 fallback이 없어
미해결로 남겼다. Advisor가 이미 수집 경로인 FRED(신규 의존 0)를 대체 후보로 지정, 소량 실호출로
확정하라는 후속 지시를 내렸다.

**대상**: VIXCLS(↔^VIX), SP500(↔^GSPC), DEXKOUS(↔KRW=X), DTWEXBGS(↔DX-Y.NYB 대응 시도),
^MOVE·^VIX3M 부재 재확인.

### 9.1 실측값 — FRED observations vs 야후 종가 (표본 대조)

| FRED series | 날짜 | FRED 값 | 야후 대응 심볼 | 야후 종가 | 오차 |
|---|---|---|---|---|---|
| VIXCLS | 2026-08-05 | 15.81 | ^VIX | 15.81 | 0 |
| VIXCLS | 2026-08-04 | 16.50 | ^VIX | 16.50 | 0 |
| VIXCLS | 2026-08-03 | 15.86 | ^VIX | 15.86 | 0 |
| SP500 | 2026-08-05 | 7723.55 | ^GSPC | 7723.5498 | ≈0 |
| SP500 | 2026-08-04 | 7736.52 | ^GSPC | 7736.52 | 0 |
| SP500 | 2026-08-03 | 7600.50 | ^GSPC | 7600.50 | 0 |
| DEXKOUS | 2026-07-30 | 1424.05 | KRW=X | 1420.60 | 0.24% |
| DEXKOUS | 2026-07-31 | 1436.81 | KRW=X(08-02) | 1435.70 | 0.08% |
| DTWEXBGS | 2026-07-28 | 120.6247 | DX-Y.NYB | 101.38 | (지수 정의 상이 — 레벨 비교 무의미) |

VIXCLS·SP500은 **오차 0에 가깝다** — FRED가 CBOE/S&P 원천 데이터를 그대로 재배포하는 것으로
판단된다(같은 시계열의 다른 배급 경로). DEXKOUS는 레벨은 근접(0.1~0.25%, 스냅샷 시점 차이로
설명 가능한 정상 범위)하지만 **지연이 다르다**(§9.2). DTWEXBGS는 레벨 자체가 다른 지수(바스켓
상이)라 레벨 대조가 무의미 — 변화율로만 판단(§9.3).

**FRED 지연 실측**(`realtime_start`/`realtime_end`를 FRED의 실제 "오늘"인 2026-08-06으로
명시 고정해 캐시된 vintage 오염 배제):

- VIXCLS·SP500: `realtime_start=2026-08-06` 조회에서 최신 관측일 `2026-08-05` → **T+1**,
  기존 sources.yaml 주석과 일치.
- DEXKOUS·DTWEXBGS: 동일하게 `realtime_start=2026-08-06`으로 명시 조회해도 최신 관측일이
  `2026-07-31`에서 멈춤 → **영업일 기준 약 4일 지연**(2026-07-31→08-06: 07-31(금)·08-03(월)·
  08-04(화)·08-05(수)·08-06(목) 4영업일). 브리프가 가정한 "일별·T+1"과 다르다 — 이 두 계열은
  연준 H.10 성격의 발표 주기 특성상 T+1이 아니다(실측 사실, 최초 무-파라미터 조회에서
  `realtime_start=2026-08-03`으로 보이는 값은 CDN 캐시로 판단 — 명시 파라미터 재조회로 대조
  확인, 최종 확정치는 위 4영업일 지연이다).

### 9.2 DEXKOUS(KRW=X 폴백) 판정: 부분(값은 유효, 지연이 용도에 부적합)

값 정합성은 양호(0.1~0.25%)하지만, `usdkrw_z`의 cadence는 `intraday_30m`이고 `mobile_daily`
프로파일에서도 가장 느슨한 것이 `fred_daily: 96h`(4일)다. 방금 실측한 DEXKOUS의 지연(≈4영업일
=약 6일 캘린더 기준)은 **조회 시점에 이미 fred_daily stale 임계에 근접·초과**한다 — 매 순간
"막 만든 데이터도 이미 stale"인 소스는 실질적으로 폴백 역할을 하지 못한다. 또한 `usdkrw_z`의
기존 설정에는 이미 `fallback_provider: ecos`가 지정돼 있어(indicators.yaml:98), DEXKOUS는
애초에 K-18(스투크) 자리가 아니라 ecos마저 막힌 경우의 **3차** 대체 후보 위치다. ecos 경로는
MT1-00b에서 `ECOS_API_KEY` 부재로 이미 차단 확인(`docs/journal/2026-08-07_MT1-00b_ecos_item_codes.md`).
**판정**: DEXKOUS는 실시간/준실시간 대체 불가, "완전 결측보다는 나은 지연 참고치" 역할로만 제한적
사용 가능 — 코드 반영은 이번 과업 범위 밖(Advisor 결정 필요).

### 9.3 DTWEXBGS(DX-Y.NYB 폴백 시도) 판정: 부적격

`dxy_z`의 transform은 레벨이 아니라 `zscore(pct_change_5d, ..., absolute=true)` — 레벨 차이는
문제가 안 되지만 **일간 변화율의 방향까지 같아야** 유효한 대체다. 2026-07-27~07-31 4개 표본
일간 변화율 대조:

| 날짜 | DX-Y.NYB 변화율 | DTWEXBGS 변화율 | 부호 일치 |
|---|---|---|---|
| 07-28 | -0.128% | -0.124% | 일치 |
| 07-29 | -0.572% | +0.136% | **불일치** |
| 07-30 | -0.784% | -0.922% | 일치 |
| 07-31 | -0.210% | +0.024% | **불일치** |

표본 4일 중 2일(50%) 부호 불일치 — ICE 달러 인덱스(6개 통화, 유로 편중)와 연준 광의 무역가중
달러지수(DTWEXBGS, 약 26개 통화)의 바스켓 구성이 달라 특정 통화(유로 외 통화)가 반대로 움직이는
날에는 두 지수가 정반대로 움직인다. 브리프가 미리 경고한 "z-score 기준선이 갈리면 폴백이 아니라
다른 지표" 상황에 정확히 해당 — **폴백 부적격**으로 판정한다. 지연 문제(§9.1, DEXKOUS와 동일한
4영업일 지연)까지 겹쳐 이중으로 부적합.

### 9.4 ^MOVE·^VIX3M: FRED 부재 재확인

`series/search`를 검색어 2종으로 각 1회 호출(`"MOVE bond market volatility index"`,
`"CBOE 3-month volatility VIX3M"`) — 둘 다 `count: 0`(응답 크기 149바이트, 빈 `seriess` 배열
동일). ICE BofA MOVE, CBOE VIX3M 모두 FRED 미수록 확정. §2의 결론대로 R-01(결측 분모 제외,
`indicators.yaml engine.missing_data_policy: exclude_from_denominator`) 그대로 흡수 — 코드
변경 불필요.

### 9.5 계열별 폴백 매핑표 (최종)

| 야후 심볼 | 지표 | K-18 1차(Stooq) | 2차 후보(FRED) | 최종 판정 |
|---|---|---|---|---|
| ^VIX | vix_level_z | 차단(§3) | VIXCLS, 오차 0, T+1 | **가능** |
| ^GSPC | spx_drawdown_momentum | 차단(§3) | SP500, 오차 0, T+1 | **가능** |
| KRW=X | usdkrw_z | 차단(§3) | DEXKOUS, 값 유효·지연 4영업일 | **불가**(지연) — 기존 `fallback_provider: ecos`도 별도 차단 중 |
| DX-Y.NYB | dxy_z | 차단(§3) | DTWEXBGS, 바스켓 상이·부호 50% 불일치 | **부적격**(다른 지표) |
| ^MOVE | move_index_z | 차단(§3) | 미수록(§9.4) | **대체 없음** → stale/R-01 흡수 |
| ^VIX3M | vix_term_structure | 차단(§3) | 미수록(§9.4) | **대체 없음** → stale/R-01 흡수 |

무폴백 3계열(KRW=X·DX-Y.NYB·^MOVE·^VIX3M, VIX3M 포함 4개)은 코드 변경 없이 기존
`engine.missing_data_policy: exclude_from_denominator` + `stale_profiles`로 흡수하는 것이
현재도 맞는 처리이며, 이번 실측으로 "설계가 이미 옳다"는 것을 재확인했을 뿐 새 코드가 필요하지
않다. VIXCLS·SP500 2계열만 MT1-04a/04b 구현 시 실제 폴백 호출 대상으로 `sources.yaml.fred.series`
목록에 추가할 후보다 — 이번 과업(실측)에서는 구조 변경을 하지 않았으므로 그 반영은 구현 단계
(Advisor 승인 후)로 넘긴다.

## 10. 검증

- 실호출 총량: 야후 10회(§1~§2 9회 + §9.3 DX-Y.NYB 1mo 재조회 1회), Stooq 5회(§3),
  FRED 8회(§9 — series 4종×1회 + DEXKOUS/DTWEXBGS 재조회 2회[캐시 배제용, 동일 계열 2회차,
  ≤3회 제약 준수] + search 2회). 계열당 실호출 ≤3회 제약 준수.
- `.env`·비밀값 미노출(FRED 호출은 `export $(grep ...)`로 쉘 변수에만 적재, 터미널 출력·문서에
  키 값 미기록).
- `uv run pytest -q` → 178 passed(무변경 확인 유지 — `sources.yaml` notes 문자열만 추가 수정,
  `tests/test_configs_schema.py:56`가 키 존재만 검사해 회귀 없음).
- 동시 편집 중인 `scrape_wgb` 블록(00d 워커 소유)은 이번에도 커밋에서 분리 유지(Advisor 보고에
  git status 원문 첨부).

## 11. 생성/변경 파일 목록

- `docs/journal/2026-08-07_MT1-00a_yahoo_stooq.md` (본 문서 — §9~§11 후속 절 추가)
- `configs/sources.yaml` (수정 — `fred.notes`에 폴백 판정 요약 추가, `stooq.notes`에 후속 해결
  상태 1줄 추가, 구조 변경 없음)

## 12. 3차 후속(2026-08-07, A-6② 보완) — FRED HY OAS·T10Y2Y 응답 계약 실측

**배경**: `BAMLH0A0HYM2`·`T10Y2Y`는 이미 수집 경로(indicators.yaml `hy_oas_delta`,
`ust_2s10s_move`)로 지정돼 있으나, 계획상 00b(ECOS) 담당으로 분류돼 실제로는 미실측 상태로
남아 있었다(A-6② 판정). `FRED_API_KEY`는 보유하고 있으므로(§9와 동일 키) 이번 라운드에서
직접 실측한다.

### 12.1 응답 형태·메타데이터

`GET /fred/series?series_id={id}` (메타)와 `GET /fred/series/observations?series_id={id}&sort_order=desc&limit=10`
(최근값) 각 1회, 이후 공휴일 구간 재조회 1회(§12.3) — 계열당 3회 이내.

| 항목 | BAMLH0A0HYM2 | T10Y2Y |
|---|---|---|
| title | ICE BofA US High Yield Index Option-Adjusted Spread | 10-Year Treasury CM − 2-Year Treasury CM |
| frequency | Daily, Close | Daily |
| observation_start(메타) | **2023-08-07** | 1976-06-01 |
| observation_end(메타, 조회 시점) | 2026-08-05 | **2026-08-06**(당일) |
| 최신 관측치(observations desc[0]) | 2026-08-05 = 최근값 | 2026-08-06 = 0.44(당일치 존재) |
| 실측 지연 | **T+1**(어제 값이 오늘 확정) | **T+0**(당일 값이 당일 조회에 존재) |

### 12.2 계열 개시일 재확인 — "2023-08 이후만" 원인 확정

기존 관측(메모리 노트 "FRED HY OAS 2023-08 이후만")이 실측으로 **정확히 재확인**됐고, 원인도
확정됐다 — 추측이 아니라 FRED 자체 메타데이터의 `notes` 필드에 명시돼 있다:

> "Starting in April 2026, this series will only include 3 years of observations. For more
> data, go to the source."

즉 `BAMLH0A0HYM2`는 원래 1996년부터 존재하는 계열이지만, **2026년 4월부터 FRED가 공개
API/화면 노출을 최근 3년 롤링 윈도우로 제한하는 정책을 도입**했다. 오늘(2026-08-06 기준)
`observation_start=2023-08-07`인 것은 "3년 롤링"이 매 조회 시점마다 앞으로 밀리는 결과다 —
**고정된 하한이 아니라 매달 전진하는 이동창**이다. `T10Y2Y`는 이런 제한이 없다(1976년부터
전체 제공, 메타데이터에 유사 공지 없음).

**설계 영향**: `hy_oas_delta`는 `delta_bp(value, lookback=5)` — 5영업일만 필요해 3년 윈도우와
무관하게 항상 안전하다(현재·향후 몇십 년간 문제 없음). 다만 **backtest/replay가 2023-08-07보다
과거 시점의 HY OAS를 FRED 실시간 API로 재수집하려 하면 항상 실패**한다(고정 데이터가 아니라
매번 "최근 3년"만 보이므로, 과거로 갈수록 계속 잘려나간다). 과거 구간은 이미 lake에 append된
고정 fixture/과거 수집분에만 의존해야 한다 — backtest-analyst에게 공유할 사실.

### 12.3 결측 표기(".") 실재 위치 — 주말·공휴일 구간 실측(2026-06-15~06-23, Juneteenth 포함)

| 날짜 | 요일 | BAMLH0A0HYM2 | T10Y2Y |
|---|---|---|---|
| 06-15 | 월 | 2.66 | 0.40 |
| 06-16 | 화 | 2.71 | 0.38 |
| 06-17 | 수 | 2.63 | 0.29 |
| 06-18 | 목 | 2.66 | 0.27 |
| 06-19 | 금(Juneteenth, 채권시장 휴장) | **2.66**(값 존재) | **"."**(결측 표기) |
| 06-20~21 | 토·일 | (행 자체 없음) | (행 자체 없음) |
| 06-22 | 월 | 2.65 | 0.27 |
| 06-23 | 화 | 2.71 | 0.34 |

**핵심 발견 2건 (모두 반드시 MT1-04b 파서가 반영해야 함)**:

1. **주말은 `"."`이 아니라 행 자체가 없다.** 9일 구간 조회에 7개 관측치만 반환(`count: 7`) —
   토·일이 배열에서 완전히 빠진다. 브리프가 가정한 "결측 표기 위치: 주말"은 표기가 아니라
   **부재**로 실현된다 — 파서는 "매일 채워진 배열에서 `.`을 골라내는" 로직이 아니라 "반환된
   날짜만 신뢰하고, 없는 날짜는 원래 없는 것으로 처리"해야 한다(달력일 기준 forward-fill/보간을
   자체적으로 만들지 말 것 — as-of join이 이미 처리하는 영역).
2. **`.`(문자열 결측)은 평일 공휴일에만 등장하며, 두 계열이 서로 다르게 행동한다.** `T10Y2Y`는
   Juneteenth(06-19)에 정직하게 `"."`을 반환(재무부 수익률 자체가 그날 생성되지 않음)하지만,
   `BAMLH0A0HYM2`는 같은 날 **전날(06-18)과 동일한 값(2.66)을 그대로 반복 출력**한다 — ICE가
   해당 지수를 그날도 "발행"하기 때문으로 보이며 신규 관측이 아니라 이연된 값이다. 두 계열에
   동일한 "공휴일=`.`" 가정을 적용하면 `BAMLH0A0HYM2` 쪽에서 조용히 틀린다(결측으로 처리해야
   할 날을 유효 관측으로 오인). `delta_bp(lookback=5)`는 반환된 관측치 순서대로 5개를 세므로
   이 반복값이 창에 끼면 실질적으로 4영업일+공휴일 반복 1개가 되어 스프레드 변화량이 살짝
   과소평가될 수 있다 — 코드 수정 대상이 아니라(as_of 그대로 사용하는 것이 K-05 원칙에 맞음)
   구현자가 인지해야 할 계열별 특성으로 기록한다.

### 12.4 vintage 캐시 함정(§9.1) 재현 여부 — 이 2계열은 재현되지 않음

§9에서 DEXKOUS·DTWEXBGS가 `realtime_start`/`end`를 명시하지 않으면 캐시된 과거 vintage를
반환하는 현상을 발견했었다. 이번엔 **명시 파라미터 없이** 메타·최근관측 조회를 했는데도 두
계열 모두 즉시 `realtime_start="2026-08-06"`(FRED의 실제 오늘)로 정확히 응답했다 — **이 2계열
에는 그 함정이 재현되지 않는다.** 인기도(`popularity: 100`/`99`, 최상위권 계열)가 높아 캐시가
자주 갱신되는 것으로 추정되나 이건 추정일 뿐 확정 사실로 문서화하지 않는다 — 확정 사실은
"이번 실측에서는 문제없었다"까지다. MT1-04b 구현은 그래도 §13의 방어적 기본값(항상 realtime
명시)을 따르는 편이 계열별 예외 분기를 없애 더 단순하다(SSOT 원칙에도 맞음).

## 13. MT1-04b 구현 계약 추가분 (이 문서 안에만 기록)

**요청 규격**:
- `GET https://api.stlouisfed.org/fred/series/observations?series_id={id}&api_key={FRED_API_KEY}
  &file_type=json&sort_order=desc&limit={N}&realtime_start={today}&realtime_end={today}` —
  `realtime_start/end`는 계열별로 분기하지 말고 **항상 호출 시점의 날짜로 명시**(§12.4 근거 —
  일부 계열에서만 캐시 문제가 재현됐다는 것 자체가 "안전한 계열"을 미리 알 수 없다는 뜻이므로
  전 계열 공통 방어가 더 단순하고 안전하다).
- 백필(과거 구간)은 `observation_start`/`observation_end`로 범위 지정. `BAMLH0A0HYM2`는
  **호출 시점 기준 최근 3년 이전 구간은 항상 빈 배열**이 온다(§12.2) — 그 이전 구간은 API
  재수집이 아니라 기존 lake 적재분에서만 가져와야 한다.

**결측 처리(K-05 as-of 정렬 포함)**:
- 응답 배열에 없는 날짜(주말 등)는 "결측"이 아니라 "원래 그 날짜의 관측치가 존재하지
  않음" — 별도 결측 레코드를 만들지 않는다(§12.3-1). `as_of`는 응답의 `date` 필드를 그대로
  쓴다(기존 `providers.fred.notes`의 "as_of는 FRED observation date" 원칙 유지).
- `value == "."`인 행은 **명시적 결측**으로 lake에 append(문자열 그대로 저장하지 말고
  파싱 단계에서 None/NaN으로 변환 — `missing_data_policy: exclude_from_denominator`로
  분모 제외 흡수, 기존 엔진 규칙 그대로 적용, 코드 변경 불필요).
- `BAMLH0A0HYM2`의 공휴일 반복값(§12.3-2)은 결측이 아니라 **유효하지만 "새 정보 없음"인
  관측**으로 그대로 저장한다 — 추측으로 "."로 바꿔 쓰지 않는다(브리프의 추측 금지 원칙). 이
  특성은 구현자 주석으로만 남기고 transform 로직은 건드리지 않는다.
- `T10Y2Y`가 당일(T+0) 값을 이미 제공하는 것(§12.1)은 그 값이 **그날 안에 재조정(revision)될
  가능성을 배제하지 않는다** — 실측으로 재정정 여부까지 확인하지는 않았다(추가 실호출 없이는
  확인 불가, 이번 과업 범위 밖). `indicators.yaml`의 `ust_2s10s_move`가 이미
  `cadence: fred_daily, lag_days: 1`로 하루 지연을 두고 있는 것은 이 잠재적 재정정 리스크에
  대한 보수적 안전판으로 그대로 유지하는 편이 맞다 — **변경 제안 없음**(indicators.yaml은
  이번 과업 범위 밖이기도 하다).

## 14. 검증 (12~13절)

- 실호출 총량(이번 라운드): FRED 6회(메타 2 + 최근관측 2 + 공휴일구간 2) — 계열당 3회 이내.
- `.env`·비밀값 미노출(동일하게 쉘 변수 적재만, 키 값 미기록).
- `uv run pytest -q` 재실행 결과는 §15 요약 참조(브리프 지정 범위: `sources.yaml`의 `fred`
  블록만 수정 — 스키마 테스트 영향 없음, `tests/test_configs_schema.py:56`는 키 존재만 검사).
- 동시 편집 파일 충돌 여부는 커밋 직전 `git diff`로 재확인(§16, 다른 워커 hunk 있으면 분리).

## 15. 생성/변경 파일 목록 (12~14절)

- `docs/journal/2026-08-07_MT1-00a_yahoo_stooq.md` (본 문서 — §12~§15 3차 후속 절 추가,
  파일명 유지)
- `configs/sources.yaml` (수정 — `providers.fred.notes`에 HY OAS/T10Y2Y 실측 결과 추가,
  `yfinance`·`stooq`·기타 블록은 이번 라운드에서 손대지 않음 — 브리프 지시 범위 준수)
