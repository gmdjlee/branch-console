# MT1-00d — kr_cds_5y_delta 모바일 수집 가능성 실측 (G-4, M-20)

- 일자: 2026-08-07
- 과업: `docs/plans/M1_PLAN_FINAL.md` §1.3 M-20 / `TASK_mobile_m1.md` MT1-04f
- 배경: 서버는 `scrape_wgb`로 한국 CDS 5Y를 수집하나(`configs/sources.yaml` provider `scrape_wgb`,
  대상 "worldgovernmentbonds KR CDS 5Y") 모바일 경로가 없다(G-4). `kr_cds_5y_delta` 지표는
  `optional: true`이므로 미수집이어도 composite 분모에서 제외되어 무왜곡.
- council 확정 기준(M-20): **(b) 미수집 확정 방향 권고, 단 실측이 "정적 GET + 정규식 1개
  수준·3일 연속 성공 전망"이면 (a) 수집 재검토**.

## 실측 절차 (실호출 4건 — 근거는 판정 절 참조)

모든 호출은 `curl`(User-Agent: 모바일 클라이언트 명시)로 서버가 아닌 모바일 네이티브 HTTP
클라이언트가 받을 원문(JS 미실행)을 그대로 확인하는 방식으로 수행. rate limit 없는 대상(K-03
KRX 스크레이핑과 무관한 별도 사이트)이라 호출 간 지연 없이 순차 수행.

### 호출 1 — robots.txt 확인
```
GET https://www.worldgovernmentbonds.com/robots.txt
→ HTTP 200, "User-agent: * / Disallow:" (전면 허용, Sitemap 명시). 크롤링 제약 없음.
```

### 호출 2 — 브리프 추정 URL
```
GET https://www.worldgovernmentbonds.com/cds-country/south-korea/
→ HTTP 404 Not Found (본문 18,843바이트, 표준 워드프레스 404 페이지)
```
URL 스킴이 실제와 다름. 404 본문의 내비게이션 링크에서 실제 경로 발견:
`href="https://www.worldgovernmentbonds.com/sovereign-cds/"`.

### 호출 3 — 실제 CDS 페이지
```
GET https://www.worldgovernmentbonds.com/sovereign-cds/
→ HTTP 200, Content-Type text/html, 26,559바이트
```
정적 HTML 검사 결과:
- `<table`/`<tbody` 태그 **0건** — 국가별 스프레드 표가 정적 마크업에 없음.
- 본문 텍스트에 "korea"(대소문자 무관) **0건** — 값이 서버 렌더링 시점에 아예 존재하지 않음.
- 인라인 스크립트에서 데이터 소스 발견(원문, 208행):
  ```
  var jsGlobalVars = {"JS_VARIABLE":"jsGlobalVars",
    "ENDPOINT":"https:\/\/www.worldgovernmentbonds.com\/wp-json\/cds\/v1\/main"};
  ```
  즉 페이지 로드 후 **클라이언트 JS가 이 REST 엔드포인트를 fetch해 테이블을 동적 주입**하는 구조.
  (Highcharts maps 스크립트도 함께 로드됨 — 지도 시각화 렌더링용, 값 자체와는 별개.)

### 호출 4 — 발견된 내부 API 엔드포인트 직접 검증
정적 GET만으로 값이 오는지 최종 확인하기 위해, 인라인 스크립트가 지목한 엔드포인트를 직접 호출
(브리프의 "1~2회" 권고를 1건 초과했으나, 판정을 좌우하는 결정적 지점이라 예외적으로 수행 — 이후
추가 호출은 하지 않음):
```
GET https://www.worldgovernmentbonds.com/wp-json/cds/v1/main
Accept: application/json
→ HTTP 404, {"code":"rest_no_route","message":"No route was found matching
  the URL and request method.","data":{"status":404}}
```
워드프레스 REST 라우터가 이 경로+메서드 조합에 매칭되는 핸들러를 찾지 못함. 페이지 인라인
스크립트에는 이 호출에 필요한 추가 헤더(Referer/Origin/nonce 등)나 파라미터가 노출되어 있지
않아, 정상 동작 조건을 규명하려면 브라우저 컨텍스트를 모사하는 추가 리버스엔지니어링이
필요하다 — 그 자체가 "정적 GET+정규식 1개 수준"의 반례.

## 판정: (b) 모바일 v1 미수집 확정

council 기준 미충족을 다음 3가지가 중첩 확인한다:
1. 브리프가 가정한 URL 자체가 틀렸다(스킴 변경 이력 — `notes`의 "구조 변경 잦음"과 일치).
2. 실제 페이지는 정적 HTML에 값이 없다 — 정규식 1개로 추출할 대상이 애초에 없음(클라이언트
   JS 렌더링 필수, Android 앱이 WebView 전체 렌더링을 붙이지 않는 한 불가).
3. 값이 로드되는 내부 REST API를 특정했음에도 직접 GET은 404 — 추가 조건(미규명)이 필요해
   "정적 GET 한 번"으로 끝나지 않는다.

→ **(b) 확정**. (a) 재검토 조건("정적 GET+정규식 1개·3일 연속 성공 전망")에 해당하지 않는다.

## 후속 조치
- `configs/sources.yaml` `providers.scrape_wgb.notes`에 위 실측 결과와 본 문서 경로를 기록,
  consumers 주석을 "모바일 v1은 CDS 미수집 확정(G-4, M-20)"으로 갱신.
- UI: 홈 화면 credit 축 카드에 "미수집" 배지 노출(MT1-08 범위, `kr_cds_5y_delta` optional=true
  이므로 composite 분모 무영향 — 코드 변경 불필요, 표시 레이어만).
- `GATE_GM1.md` 작성 시 credit 축 발화 표면 축소 사실을 기록(M-20 원문 조건).
- 추측으로 코드값·URL을 확정하지 않았음 — 위 4건 실호출 원문이 유일한 근거.

## 참고: 탐색하지 않은 대안 소스
"과확장 금지" 지침에 따라 대안 공개 소스(tradingeconomics.com 등)는 실호출로 검증하지 않았다.
(b) 확정이 이미 명확하고, `kr_cds_5y_delta`가 optional 지표라 시스템 정합성에 영향이 없어
추가 탐색의 기대가치가 낮다고 판단. 향후 필요 시 별도 실측 항목으로 분리 권고.
