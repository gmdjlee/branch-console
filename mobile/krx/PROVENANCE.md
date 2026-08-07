# PROVENANCE — mobile/krx (kotlin_krx 벤더링)

- 업스트림: `D:\android_2025\kotlin_krx` (원격 `https://github.com/gmdjlee/kotlin_krx.git`)
- 복사 시점 커밋 SHA: `6cc8180eb49ddc2b4982535aef38206f90531172`
- 복사 일시: 2026-08-07 (MT1-01g)
- 근거: `docs/plans/M1_PLAN_A.md` §2.3(AD-A2, 벤더링 채택) ·
  `docs/journal/2026-08-07_MT1-00c_kotlin_krx.md`(실측 계약·수정 의무, §5·§6·§8)
- 원본 저장소 변경 범위: **0**. 이 벤더링 작업은 `D:\android_2025\kotlin_krx`를 읽기만 했다
  (git status 확인 결과는 아래 §5 참조).

## 1. 복사 범위

- `src/main/kotlin/com/krxkt/**` 전체 (29개 파일, 미사용 API 포함 — 재수입 diff를 단순하게
  유지하는 편이 코드 몇 KB보다 싸다는 M1_PLAN_A §2.3 규율).
- `src/test/kotlin/com/krxkt/**` 중 **비네트워크 단위 테스트만** (24개 파일).

## 2. 제외 목록

| 대상 | 사유 |
|---|---|
| `src/test/kotlin/com/krxkt/integration/**` (17개 파일) | M1_PLAN_A §2.3 사전 결정. 로그인 필수화 커밋(`8682288`) 이후 다수가 `login()` 미호출 상태로 즉시 `AuthenticationError`를 던진다(00c 저널 §1 확증) — 실네트워크 의존 + 이식 즉시 깨진 상태 |
| `src/test/kotlin/com/krxkt/ManualMarketCapTest.kt` | **브리프 범위 밖 판단 — 능동적 배제.** `integration/` 폴더 밖에 있지만 실제 KRX API를 호출하고 `login()`을 선행하지 않아 즉시 `AuthenticationError`로 실패한다. CLAUDE.md "테스트는 네트워크 금지: 픽스처 기반" 규율 위반이므로 벤더링에서 제외했다. kotlin_krx 저장소 자체에서도 미추적(untracked) 상태(00c 저널 서두 확인) — 커밋된 공식 테스트 스위트가 아니다 |
| `src/test/kotlin/com/krxkt/MarketCapComparisonTest.kt` | 상동. 실 KRX API를 `runBlocking`으로 직접 호출하는 탐색용 스크립트, 미추적 파일, 무결과 검증(콘솔 출력만) — 벤더링 부적격 |

## 3. 우리가 가한 변경

### 3.0 기계적 변경 — 전 53개 벤더 파일, ktlint 서식 정합화 (의미 변화 없음)

`ktlintFormat`으로 전 벤더 파일에 프로젝트 ktlint 규약(후행 콤마, 멀티라인 호출부 개행,
함수 파라미터 개행 등)을 적용했다 — CLAUDE.md "ktlint·detekt 0 경고" 완료 기준을 만족시키기
위함이며 로직·동작 변화는 없다. 추가로 자동 교정 불가능했던 2건을 수동 수정했다:

- `api/KrxEndpoints.kt`: `// === 섹션 === ` 스타일 주석과 바로 다음 KDoc 사이에 빈 줄 삽입
  (ktlint `kdoc-wrapping` 규칙 — "KDoc은 EOL 주석 바로 뒤에 올 수 없다").
- `KrxStock.kt`: `import com.krxkt.model.*` 와일드카드 임포트를 명시적 임포트 14개로 전개
  (ktlint `no-wildcard-imports`) + `mapOf(...)` 인자 목록 안의 트레일링 주석 1건을 별도 줄로 이동
  (ktlint `value_argument_list` 주석 배치 규칙).

`krx-manifest.sha256`은 이 기계적 서식 정합화까지 반영한 **최종 상태**의 해시다 — "복사 시점
원본과 바이트 동일"이 아니라 "이 저장소가 벤더링·수정·서식 정합화를 거쳐 확정한 버전"의
무결성을 보장한다(재이식 절차는 REIMPORT.md 참고).

`detekt-baseline.xml`도 같은 이유로 벤더링 시점(서식 정합화 이후) 상태를 스냅샷한다 —
upstream 스타일의 기존 위반(TooManyFunctions·CyclomaticComplexMethod·MagicNumber·
TooGenericExceptionCaught 등 103건, 전부 `fromJson(){ ... catch(Exception) }` 패턴·날짜
검증·API 표면 크기 등 벤더 코드 고유 특성)을 동결해 detekt를 통과시킨다. 이 파일이 늘어나면
(신규 위반 추가) 리뷰 대상이고, 우리가 벤더 코드를 더 고칠 때마다 줄어드는 것이 정상 방향이다.

### 3.1 의미 있는 변경 (4건, 파일별)

#### (필수·치명) `model/InvestorTrading.kt` — TRDVAL8~11 슬롯 오정렬 수정 (D-1)

- **결함**: `fromJson()`(전체시장 일별추이 MDCSTAT02203용)이 TRDVAL8~11을
  institutionalTotal/otherCorporation/individual/foreigner 순으로 잘못 가정했다. 실제로는
  두 엔드포인트(MDCSTAT02203/MDCSTAT02303)가 동일한 슬롯 배치를 쓰며 정답은
  TRDVAL8=기타법인, TRDVAL9=개인, TRDVAL10=외국인, TRDVAL11=기타외국인,
  외국인합계=TRDVAL10+11, 기관합계=TRDVAL1~7 직접합산이다(`fromTickerJson()`의 KDoc이
  처음부터 정확했다).
- **실측 근거**: 2026-08-05 raw JSON 캡처 + pykrx(`detail=True`) 독립 대조,
  `docs/journal/2026-08-07_MT1-00c_kotlin_krx.md` §3.4. 외국인합계 재현값
  +1,148,492,588,611 KRW, 기관합계 -393,829,630,819 KRW, 11분류 배타 합=0(항등식,
  TRDVAL_TOT은 결측이 아니라 이 항등식의 참값).
- **수정**: `fromJson()`을 `fromTickerJson()`에 위임(중복 로직 제거, 동일 슬롯 배치이므로
  단일 구현으로 충분 — root-cause fix). 클래스·함수 KDoc을 정정된 배치로 재작성.
- **테스트**: `InvestorTradingTest.kt` 기존 3개 테스트(`fromJson should parse valid...`,
  `...handle negative values`, `...handle empty and missing fields`)의 단언값을 새 매핑에
  맞게 갱신 + 신규 증인 테스트 2건(`...D-1 witness`, `...D-2 witness`) — 2026-08-05 실측
  raw JSON을 그대로 픽스처로 사용해 외국인합계·기관합계·11분류 항등식을 재현.

#### `model/IndexOhlcv.kt` — `tradingValue` KDoc 단위 정정

- **결함**: KDoc이 "백만원(million KRW)"이라 서술했으나 실측값(2026-08-05 KOSPI
  25,657,753,879,758)은 원(KRW) 단위와만 정합한다(현실적 일일 거래대금 범위와 대조).
- **실측 근거**: `docs/journal/2026-08-07_MT1-00c_kotlin_krx.md` §3.2, MDCSTAT00301
  한정 실측.
- **수정**: KDoc을 "원 KRW"로 정정 + 실측 범위(MDCSTAT00301 한정) 명시. **범위 밖 유의**:
  `model/IndexOhlcvByTicker.kt`(MDCSTAT00101, 별도 엔드포인트)도 동일 "백만원" 문구를
  갖지만 이번 실측 대상이 아니므로 **미변경** — 근거 없는 일반화를 피했다(추측 금지,
  CLAUDE.md 원칙). 실측되면 별도 서브태스크에서 정정한다.
- **테스트**: 기존 `IndexOhlcvTest.kt`는 단위 문구가 아니라 원시 숫자만 단언하므로 변경 불요.

#### `api/KrxClient.kt` — 재시도 정책 파라미터화

- **변경**: private companion 상수 `MAX_RETRIES`(3)·`RETRY_DELAYS_MS`
  (`[1000, 2000, 4000]`)를 생성자 파라미터 `maxRetries`·`retryDelaysMs`로 승격
  (기본값은 upstream 원값 그대로 — 동작 변화 0). 기존 상수는 `DEFAULT_MAX_RETRIES`·
  `DEFAULT_RETRY_DELAYS_MS`로 이름을 바꿔 공개 기본값으로 보존.
- **사유**: M1_PLAN_A §2.3 ②(rate limit·휴장 스킵은 호출자 책임 — 어댑터 계층에서 감싼다)에
  따라, 실제 호출 간격 SSOT 배선은 MT1-04c(어댑터) 소관이다. 여기서는 그 배선이 가능하도록
  "상수 → 주입 가능한 파라미터"로만 바꿨다 — SSOT 값 자체는 이 서브태스크의 범위가 아니다.
- **비고**: 이 클라이언트에는 "호출 간 최소 간격"(K-03) 자체가 원래 없다 — 실측
  (00c 저널 §6 #2)이 이미 "KrxClient에는 없음, 전부 호출자 책임"이라고 명시했다.
  파라미터화 대상은 그나마 존재하는 재시도 backoff뿐이며, 호출 간격 삽입은 MT1-04c의 몫이다.
- **테스트**: 기존 `KrxClientTest.kt`의 재시도 관련 테스트(`should retry 3 times...` 등)는
  기본값이 그대로이므로 무변경으로 green.

#### `api/KrxClient.kt` — 로그인 URL 3종 파라미터화 (MT1-01f)

- **배경**: MT1-01f(Kover 커버리지 게이트) 정밀화 과정에서 `com.krxkt.*` 패키지 전체를
  뭉뚱그려 배제하던 이전 필터를 파일 단위 배제로 좁혔더니(§4 매니페스트 절 참고), 우리가
  실제로 수정한 이 세 파일이 측정 대상에 들어왔고 `:krx` 모듈 커버리지가 57.84%로
  드러나(AAA §2.3 요구 70% 미달) `login()`/`postLogin()` 56줄이 전부 미검증 상태임이
  실측됐다. 원인은 이 두 메서드가 `KrxEndpoints.LOGIN_PAGE`/`LOGIN_JSP`/`LOGIN_URL`(실제
  KRX 프로덕션 URL)을 직접 참조해 `MockWebServer`로 가로챌 수 없었기 때문이다
  — CLAUDE.md "테스트는 네트워크 금지: 픽스처 기반" 규율상 실 네트워크를 두드리는 테스트는
  작성할 수 없다.
- **수정**: 이미 존재하는 `baseUrl`/`sessionInitUrl` 생성자 파라미터화 패턴(위 "재시도 정책
  파라미터화" 항목과 동일 패턴)을 그대로 확장해 `loginPageUrl`·`loginJspUrl`·`loginUrl`
  3개 생성자 파라미터를 추가했다. 기본값은 각각 `KrxEndpoints.LOGIN_PAGE`/`LOGIN_JSP`/
  `LOGIN_URL` 그대로이므로 **동작 변화 0**(인자를 넘기지 않으면 기존과 완전히 동일).
  `login()`/`postLogin()` 본문의 `KrxEndpoints.LOGIN_*` 직접 참조 3곳을 해당 필드 참조로
  치환했다.
- **테스트**: `KrxClientTest.kt`에 `login()`(CD001 성공·CD011 중복 로그인 재시도·오인식
  에러코드 실패)·`postLogin()`(3구간 각각의 IOException → NetworkError, 빈 본문/파싱
  실패 → ParseError) 신규 9건 + `initSession()` 3건(성공·2회 호출 시 무동작·IOException
  흡수) + `InMemoryCookieJar` 3건(URL 매치 조회·동일 name+domain 교체·만료 쿠키 축출)을
  추가했다 — 전부 `MockWebServer`/순수 인메모리 로직만 사용, 실 네트워크 호출 없음.
  결과: `:krx` 라인 커버리지 57.84% → 82.76%(재시도·로그인 실패 분기 다수 포함).
- **매니페스트**: `krx-manifest.sha256`의 `KrxClient.kt`·`KrxClientTest.kt` 두 해시를
  갱신했다(§4).

#### 자격증명 주입 — **변경 없음 (이미 충족)**

- 브리프 항목: "자격증명 주입 구조: 하드코딩·환경 직접 접근 제거, 생성자/인터페이스 주입".
- **확인 결과**: `main/kotlin/com/krxkt/**` 전체를 `local.properties|System.getenv|getProperty|
  KRX_ID|KRX_PW|krxId|krxPw` 패턴으로 grep한 결과 0건. `KrxClient.login(loginId: String,
  loginPw: String)`은 이미 순수 문자열 파라미터만 받으며 환경 변수·프로퍼티 파일을 직접
  읽지 않는다 — 하드코딩·환경 직접 접근은 벤더링 대상(main 소스)에 애초에 없었다.
  `local.properties` 경유 평문 자격증명은 **build.gradle.kts의 `runIntegrationTest` 태스크**
  (벤더링 대상 아님, §2 제외 목록 참고)와 `integration/` 테스트(제외됨)에서만 쓰였다.
- **결론**: 벤더링 범위(main 소스)는 이미 K-17의 "주입 가능한 경계"를 만족한다. 실제 Keystore/
  EncryptedSharedPreferences 배선은 여기서 만들지 않는다 — 호출부(향후 MT1-04c 어댑터)가
  `login(id, pw)`에 값을 넘기기만 하면 되고, 그 값을 어디서 읽어오는지는 어댑터 책임이다.
  없는 문제를 새 인터페이스로 해결하려 하지 않았다(YAGNI).

## 4. 매니페스트·재이식·검증

- `krx-manifest.sha256`: 벤더링된 모든 파일(main+test, 53개)의 SHA-256, 경로 오름차순.
  `verifyKrxProvenance` Gradle 태스크(`:krx:check`에 배선)가 이 매니페스트와 실제 파일을
  대조해, 등재되지 않은 변경이나 매니페스트 갱신 누락을 빌드 실패로 잡는다.
- 재수입 절차: `REIMPORT.md` 참고.

## 5. 원본 저장소 무변경 확인

```
$ git -C D:/android_2025/kotlin_krx status --porcelain
?? .kotlin/
?? MIGRATION_MAP.md
?? MIGRATION_REVIEW_REPORT.md
?? src/test/kotlin/com/krxkt/ManualMarketCapTest.kt
?? src/test/kotlin/com/krxkt/MarketCapComparisonTest.kt
$ git -C D:/android_2025/kotlin_krx rev-parse HEAD
6cc8180eb49ddc2b4982535aef38206f90531172
```

MT1-00c 실측 시점과 정확히 동일한 5개 미추적 파일 + 동일 HEAD — 이번 벤더링 작업 동안
원본 저장소에 어떤 파일도 추가·수정·삭제되지 않았다.
