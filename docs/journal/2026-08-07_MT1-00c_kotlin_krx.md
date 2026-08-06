# MT1-00c — kotlin_krx 기보유 프로젝트 실측

- 작성일: 2026-08-07 · 소속: M1 W0 / MT1-00c · 역할: data-verifier Worker
- 근거: 사용자 확정(2026-08-02, 모바일 KRX 수집은 `D:\android_2025\kotlin_krx` 벤더링) ·
  `docs/plans/M1_PLAN_A.md` §2.3(AD-A2) · K-02·K-03·K-19(제안, `docs/plans/M1_PLAN_C.md` F-11)
- **대상 저장소 변경 범위: 0.** `D:\android_2025\kotlin_krx`는 읽기·실행만 수행했다.
  실측 코드는 저장소 밖 scratchpad의 독립 Gradle 프로젝트에서 그 빌드 산출물(jar)을
  라이브러리로 참조해 실행했다(§6). 종료 시점 `git status` 확인 결과 사전에 존재하던
  미추적 파일 5건(`MIGRATION_MAP.md`·`MIGRATION_REVIEW_REPORT.md`·`ManualMarketCapTest.kt`·
  `MarketCapComparisonTest.kt`·`.kotlin/`) 외 변경 없음 — M1_PLAN_A §2.3의 "미추적 파일 5건"
  서술과 정확히 일치. HEAD `6cc8180`도 M1_PLAN_A가 인용한 값과 동일(드리프트 없음).
- 자격증명: branch-console `.env`의 `KRX_ID`/`KRX_PW` (kotlin_krx 자체 `local.properties`에도
  동일 계정이 이미 평문 등록되어 있었음 — 이 파일은 `.gitignore` 대상이라 벤더링 시
  Keystore 이전 대상에서 자동 제외된다).

## 0. 결론 요약 (TL;DR)

| # | 질문 | 답 |
|---|---|---|
| 1 | `login()`이 실계정으로 성공하는가 | **예.** 1회 시도로 성공(`loginOk=true`), CD011 재시도 분기는 이번 실행에서 관찰되지 않음(재시도 없이 즉시 CD001) |
| 2 | KOSPI/KOSDAQ 지수 OHLCV 필드·단위 | **실재.** 단, `tradingValue` 필드의 KDoc 주석("백만원")이 **틀렸다** — 실측값은 원(KRW) 단위다(§3) |
| 3 | 투자자별 순매수 필드·단위 | **실재**, 원(KRW) 단위. 단 시장전체(`mktId=ALL`) 응답의 `total` 필드는 항상 0 — `TRDVAL_TOT` 필드 부재/미매핑 추정(§3) |
| 4 | `getVkospi()` 실데이터 반환 여부 | **예, 반환한다.** M1_PLAN_A §10 U-3가 제기한 우려("K-02의 서버 측 결론이 모바일에서 성립 안 할 수 있다")가 **사실로 확인**됨 — VKOSPI는 모바일 경로(로그인 후)에서 실제로 조회 가능 |
| 5 | `getBusinessDays` 계열 동작 | **정상.** 주말을 실제로 걸러낸다(토·일 포함 구간 조회 시 월요일만 반환) |
| 6 | K-19(파싱 실패와 휴장이 동일한 반환값) | **소스 코드로 확증.** 실거래일 조회로는 재현할 필요가 없는, 구조적으로 결정된 사실(§4) |

가장 중요한 발견은 **(4)**다: K-02는 pykrx(서버 사이드)에서 VKOSPI 조회가 불가하다고
결론지었으나, kotlin_krx는 `login()` 이후 동일 엔드포인트(MDCSTAT01201)를 **기본
Referer(outerLoader)만으로 성공**시킨다. 즉 "VKOSPI는 세션이 있으면 mdiLoader Referer가
필요하다"는 kotlin_krx 자체 KDoc/CLAUDE.md 서술도 실측과 불일치했다 — 실제 코드
(`getDerivativeIndex`)는 커스텀 Referer를 전혀 쓰지 않고 기본 `post(params)`(outerLoader)를
호출하며, 그것만으로 충분했다.

## 1. 빌드 확인

```
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr (JDK 21, gradlew.bat이 기대하는 버전)
cd D:\android_2025\kotlin_krx
.\gradlew.bat build
```

`BUILD SUCCESSFUL in 1m 27s` (4 tasks). `test`는 `excludeTags("integration")`이 기본이라
MockWebServer 기반 단위 테스트만 실행되며 실네트워크 호출은 0건 — CLAUDE.md의
"단위 테스트는 네트워크 불필요" 서술과 일치. `build/libs/krxkt-1.0.0-SNAPSHOT.jar` 산출
확인.

기존 `integration/` 폴더의 `fun main()` 16개 중 로그인을 실제로 호출하는 것은
`SamsungCloseTest.kt`·`EtfPortfolio423170Test.kt` 2개뿐이다. 나머지(예:
`IndexExtensionTest.kt`, `DerivativeIntegrationTest.kt`, `KrxIndexIntegrationTest.kt` 등)는
`git log`상 로그인 필수화 커밋(`8682288 "feat: KRX 로그인 필수 인증 및 통합 테스트 추가"`)
**이전에** 작성되어 `login()`을 호출하지 않는다 — 지금 그대로 실행하면
`KrxClient.post()`가 `loggedIn=false`를 보고 네트워크 호출 없이 즉시
`KrxError.AuthenticationError`를 던진다(코드 읽기로 확인, 실행 불필요). **벤더링 시
이 구식 통합 테스트들을 그대로 복사하면 즉시 깨진 상태가 되므로, M1_PLAN_A §2.3이 이미
명시한 "`integration/` 하위 제외" 결정이 정확히 이 문제를 피한다.**

## 2. 실측 방법 (K-03 준수: 항목당 1회, 호출 간 1.2초)

kotlin_krx를 수정하지 않고 실호출하기 위해, **scratchpad에 독립된 소형 Gradle 프로젝트**를
만들어 `krxkt-1.0.0-SNAPSHOT.jar`(방금 빌드한 산출물)를 라이브러리 의존성으로 참조했다.
이 프로젝트는 kotlin_krx 밖에 있으며 kotlin_krx의 어떤 파일도 건드리지 않는다(§6에 위치·
재현 절차 기록). 실행 순서(단일 `KrxClient` 인스턴스·단일 세션 공유, 항목 사이 1.2초 대기):

1. `client.login(krxId, krxPw)`
2. `KrxIndex.getKospi("20260731","20260805")`
3. `KrxIndex.getKosdaq("20260731","20260805")`
4. `KrxIndex.getVkospi("20260731","20260805")`
5. `KrxStock.getMarketTradingByInvestor("20260731","20260805", Market.ALL, VALUE, NET_BUY)`
6. `KrxIndex.getBusinessDays("20260801","20260803")` (토~월 구간)

총 실호출: 로그인 내부 HTTP 3건(로그인 페이지 GET·login.jsp GET·POST 인증, CD011 재시도
없음) + 데이터 호출 5건 = 8건. 항목당 실호출은 모두 1회(≤3회 budget 이내), 호출 간
1.2초 대기로 K-03(순간 호출 간격 규정)을 준수했다.

## 3. 실측 결과

### 3.1 로그인 (`KrxClient.login`)

```
login result=true, isLoggedIn=true
```

성공. 세션은 이후 5개 호출 전부에서 재사용됐고 `LOGOUT` 응답이나
`AuthenticationError`가 한 번도 발생하지 않았다 — 최소 이 세션 창(호출 6건, 총 소요
약 6초) 동안 세션이 유효함을 확인. 세션 만료 주기 자체(수 분~수 시간)는 이 단발
실행으로는 측정 불가 — 별도 장기 관찰이 필요하면 후속 태스크로 이월.

### 3.2 KOSPI/KOSDAQ 지수 OHLCV (`KrxIndex.getKospi`/`getKosdaq`, MDCSTAT00301)

```
IndexOhlcv(date=20260805, open=6603.48, high=6674.66, low=6540.27, close=6598.26,
           volume=338499583, tradingValue=25657753879758, changeType=1, change=239.31)
... (KOSPI 4행, KOSDAQ 4행 — 20260731·0803·0804·0805)
```

필드 실재 확인: `date, open, high, low, close, volume, tradingValue, changeType, change`
전부 채워짐(null 없음). **`tradingValue` 단위 불일치 발견**: `IndexOhlcv.kt`의 KDoc은
"ACC_TRDVAL → tradingValue (누적거래대금, **백만원**)"이라고 적어놨지만, 실측값
25,657,753,879,758을 백만원으로 해석하면 하루 거래대금이 약 2.57×10¹⁹원이 되어
현실과 맞지 않는다. 이 값을 **원(KRW) 그대로**로 해석하면 약 25.7조원으로, KOSPI
하루 거래대금의 정상 범위(수조~수십조원)에 정확히 들어맞는다. → **실제 단위는
원(KRW)이며, 소스 코드 KDoc 주석이 잘못됐다.** `mobile/krx/PROVENANCE.md`(MT1-01g
벤더링 시)와 MT1-04c 계약에 이 정정을 반드시 반영해야 한다(§5).

### 3.3 VKOSPI (`KrxIndex.getVkospi`, MDCSTAT01201)

```
DerivativeIndex(date=20260805, close=78.55)
DerivativeIndex(date=20260804, close=82.05)
DerivativeIndex(date=20260803, close=80.78)
DerivativeIndex(date=20260731, close=84.35)
```

**성공.** 4개 거래일 전부 0이 아닌 종가를 반환했다. `getDerivativeIndex()` 구현을
다시 확인한 결과, `client.post(params)` (단일 인자 오버로드, 즉 기본 Referer =
`KrxEndpoints.REFERER` = outerLoader)를 호출한다 — CLAUDE.md·KDoc이 서술하는
"파생상품은 mdiLoader Referer 필요"라는 이중화 전략을 **실제로는 쓰지 않는다.**
로그인된 세션 + 기본(outerLoader) Referer만으로 VKOSPI가 조회된다는 뜻이다.
→ **K-02의 결론(서버 사이드 pykrx는 VKOSPI 조회 불가 → realized_vol 폴백)이
모바일(kotlin_krx, 로그인 경로)에는 적용되지 않는다.** M1_PLAN_A §10 U-3에서
제기한 질문에 대한 실측 답이다.

### 3.4 투자자별 순매수 (`KrxStock.getMarketTradingByInvestor`, MDCSTAT02203)

```
InvestorTrading(date=20260805, financialInvestment=-28799818552, insurance=-7009938372,
  investmentTrust=165150622478, privateEquity=-511707108428, bank=2405915443,
  otherFinance=21332394103, pensionFund=-35201697491, institutionalTotal=35576102556,
  otherCorporation=-790239060348, individual=1154143165996, foreigner=-5650577385, total=0)
... (4행)
```

`foreigner`(외국인 순매수) 필드 실재, 단위는 원(KRW)(예: -5,650,577,385원 = 약 -56.5억원,
`mktId=ALL, valueType=VALUE, askBidType=NET_BUY` 기준). **`total` 필드가 4행 모두
정확히 0** — `InvestorTrading.fromJson()`이 읽는 `TRDVAL_TOT` 키가 이 응답 형태에는
없거나 다른 이름으로 오는 것으로 추정(파서의 `?: 0L` 기본값이 조용히 채움 — K-19와
같은 유형의 "결측이 유효값으로 위장"하는 사례). NET_BUY 총합은 이론상 0에 가까워야
하므로 우연히 그럴듯해 보이지만, 실제로는 필드 매핑 결측이지 계산 결과가 아니다.
**MT1-04c 계약에서 `total`/`institutionalNetBuy` 등 파생 getter를 시장전체(ALL) 응답에
그대로 신뢰하지 말 것.**

### 3.5 영업일 (`KrxIndex.getBusinessDays`)

```
getBusinessDays("20260801", "20260803") → [20260803]
```

2026-08-01(토)~08-03(월) 구간에서 월요일 하루만 반환 — 주말이 정상적으로 걸러짐을
확인(KOSPI OHLCV 기간 조회 결과를 재사용하는 구현이므로, 실제 거래소 캘린더가 아니라
"그 구간에 실제 시세가 찍힌 날"을 영업일로 정의한다는 점에 유의 — 임시 휴장·시스템
점검일도 같은 방식으로 자동 제외될 것으로 추정되나 이번 실측 범위 밖).

## 4. K-19 확증 (파싱 실패 ≡ 휴장, 둘 다 빈 리스트)

`docs/plans/M1_PLAN_C.md` F-11이 제안한 K-19는 **소스 코드 구조로 확증**됐다(실거래일
호출로 재현할 필요가 없는, 결정론적 사실이므로 §2의 실호출 예산에서 별도 호출을
쓰지 않았다):

- 모든 모델의 `fromJson()`(`IndexOhlcv`, `InvestorTrading`, `DerivativeIndex`,
  `MarketOhlcv` 등)은 `try { ... } catch (e: Exception) { null }` 패턴이다 — 파싱
  실패 시 해당 행은 조용히 `null`.
- 호출부는 예외 없이 `jsonArray.mapNotNull { X.fromJson(it) }`로 null을 걸러낸다.
- 휴장일에는 KRX가 `OutBlock_1: []`(빈 배열)를 정상 응답하며, 이 경우도 `mapNotNull`
  결과가 빈 리스트다.
- **결과: "행 전부가 파싱 실패"와 "정말로 휴장이라 데이터가 없음"이 호출자 관점에서
  완전히 동일한 빈 리스트로 수렴한다.** 둘을 구분할 신호(경고 로그·예외·플래그)가
  코드에 없다.

→ MT1-04c/06a 어댑터 계약에서 **영업일 캐시(`getBusinessDays`)를 1차 근거로 삼고
빈 응답 자체는 보조 신호로만 사용**해야 한다는 `M1_PLAN_C.md` RC-10의 처방이 실측으로
뒷받침된다.

## 5. MT1-04c 구현 계약 (메서드 × 필드 × 단위 × 오류 거동)

| 메서드 | bld | 필드(반환 타입) | 단위 | 오류/결측 거동 |
|---|---|---|---|---|
| `KrxClient.login(id, pw)` | (로그인 3-step) | `Boolean` | — | 실패 시 `false`(예외 아님). 세션 만료 시 이후 `post()`가 `AuthenticationError` — 재로그인 필요 |
| `KrxIndex.getKospi/getKosdaq(start,end)` | MDCSTAT00301 | date:String(yyyyMMdd), open/high/low/close:Double, volume:Long(주), **tradingValue:Long(원 KRW — KDoc "백만원" 오기, §3.2)**, changeType:Int?(1↑/2↓/3보합), change:Double? | 지수 포인트, KRW | 휴장/파싱실패 모두 빈 리스트(§4) |
| `KrxIndex.getVkospi(start,end)` | MDCSTAT01201 | date, close:Double | 지수 포인트 | 로그인 필수. 기본 Referer(outerLoader)로 충분 — mdiLoader 전환 불필요(실측, §3.3) |
| `KrxStock.getMarketTradingByInvestor(start,end,market,valueType,askBidType)` | MDCSTAT02203 | 투자자군별 Long(원 KRW) 11종 + `institutionalTotal`(직접합산, 신뢰 가능) + **`total`(시장전체 응답에서 0 고정, 미신뢰, §3.4)** | KRW (또는 valueType=VOLUME이면 주) | 동일 |
| `KrxIndex.getBusinessDays(start,end)` | MDCSTAT00301 재사용 | `List<String>`(yyyyMMdd, 오름차순) | — | "그 구간 시세가 있는 날"을 영업일로 정의(휴장·주말 자동 제외, §3.5) |
| 공통 | — | — | — | 로그인 세션 없이 `post()` 호출 시 네트워크 요청 없이 즉시 `AuthenticationError`(§1) |

## 6. 벤더링(MT1-01g) 적응 필요 지점 — M1_PLAN_A §2.3 대조

| # | 지점 | M1_PLAN_A §2.3 서술과의 관계 |
|---|---|---|
| 1 | **자격증명 주입**: 현재 `local.properties`(Gradle 프로퍼티) 경유, 평문 | §2.3 ①과 일치 확인. 앱에서는 Keystore/EncryptedSharedPreferences로 교체 필요(K-17) — `login(id, pw)`가 순수 문자열 파라미터만 받으므로 호출부만 바꾸면 됨, 라이브러리 자체 변경 불필요 |
| 2 | **rate limit/휴장 스킵**: `KrxClient`에는 없음, 전부 호출자 책임 | §2.3 ②와 일치. 어댑터 계층에서 K-03(1초 이상 간격) 삽입 필요 — 이번 실측에서도 어댑터 밖(호출 스크립트)에서 1.2초 delay를 넣어야 했다 |
| 3 | **`integration/` 테스트 제외**: 실네트워크 의존, 로그인 필수화 이후 절반이 이미 깨진 상태 | §2.3 ③과 일치, 오히려 근거가 하나 더 늘었다(§1의 "구식 테스트는 즉시 AuthenticationError") |
| 4 | **(신규 발견) `tradingValue` 단위 KDoc 오류** | §2.3에 없던 항목. 벤더링 복사 시 KDoc 주석을 "원(KRW)"로 정정하거나, 최소한 `PROVENANCE.md`의 "우리가 가한 변경" 목록에 주석 정정을 1줄 추가할 것 |
| 5 | **(신규 발견) 시장전체 `InvestorTrading.total` 필드 결측** | §2.3에 없던 항목. MT1-04c 계약에서 이 필드를 소비하지 않도록 명시(§5) — 필요하면 `institutionalTotal + otherCorporation + individual + foreigner`로 직접 재계산 |
| 6 | **(신규 발견) VKOSPI mdiLoader Referer 미사용** | 벤더링 시 "이중 Referer 전략" 관련 코드·주석이 실제로는 단일 경로임을 확인했으니, 향후 유지보수 시 혼동 방지를 위해 PROVENANCE.md에 기록 권고 |

## 7. 재현 절차 (기록용)

실측 스크립트는 branch-console 저장소 밖(`scratchpad/verify-project/`, session-scoped
temp 디렉터리)에 있으며 이 커밋에는 포함되지 않는다(자격증명 파일 `credentials.properties`가
같은 디렉터리에 있었기 때문에 저장소에 들이지 않음). 재현이 필요하면:

1. `D:\android_2025\kotlin_krx`에서 `gradlew.bat build` → `build/libs/krxkt-1.0.0-SNAPSHOT.jar` 확보.
2. 별도 디렉터리에 `kotlin("jvm") version "2.1.0"` + `application` 플러그인 프로젝트를 만들고
   위 jar를 `implementation(files(...))`로, okhttp/gson/coroutines-core/kotlinx-datetime을
   kotlin_krx와 **동일 버전**으로 의존성에 추가.
3. `src/main/kotlin/MyVerify.kt`에 §2의 6단계를 그대로 구현(각 호출 사이 `delay(1200)`).
4. `gradlew.bat run`으로 실행, `-DkrxId`/`-DkrxPw`는 `tasks.named<JavaExec>("run") { systemProperty(...) }`로 주입.

이 절차 자체가 MT1-04c의 "어댑터 계층에서 세션·rate limit·자격증명을 감싼다"(§2.3 ①②)
설계를 미니어처로 선연습한 것이기도 하다.
