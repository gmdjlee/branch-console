# MT1-00b — ECOS item_code 실측 확정: 차단(ECOS_API_KEY 부재)

- 작성: 2026-08-07, data-verifier Worker
- 대상: `configs/indicators.yaml`의 `krx_credit_spread_delta.source.item_codes`
  (`corp_aa3y: VERIFY`, `ktb_3y: VERIFY`, K-04) 실측 확정 — **미완료, 차단 상태로 보고**

## 0. 결론 (요지)

실호출을 시도하기 전 사전 점검(브리프 지시 1단계) 단계에서 **`ECOS_API_KEY`가 `.env`에
존재하지 않음**을 확인했다. `configs/sources.yaml:33`이 명시하는 `auth: env:ECOS_API_KEY` 없이는
ECOS StatisticItemList/StatisticSearch API를 인증 호출할 수 없다(공개 무인증 엔드포인트가 없음 —
ECOS는 모든 조회에 발급 키를 요구). 브리프 1단계 지시("키명은 .env에서 직접 확인. 없으면 중단하고
보고")에 따라 **API 실호출을 0회 수행**하고 여기서 중단한다.

`configs/indicators.yaml`의 VERIFY 2건은 **변경하지 않았다**(추측 코드 금지 원칙). K-02(VKOSPI)처럼
사전 정의된 fallback 경로가 K-04에는 없으므로 fallback도 활성화하지 않았다 — 이 항목은 그대로
"미해결" 상태로 남겨 사실을 문서로 대체한다.

## 1. 사전 점검 절차 및 실측 사실

**절차**: `.env` 파일의 변수명만 나열(값은 미출력) — `grep -oE "^[A-Z_]+=" .env`.

**실제 결과**:
```
ANTHROPIC_API_KEY=
FRED_API_KEY=
KRX_ID=
KRX_PW=
```
4개 키만 존재. `ECOS_API_KEY`는 파일에도, OS 환경변수(`printenv | grep -i ECOS`, 결과 없음)에도
없다.

**대조 확인**: `configs/sources.yaml:29-35`
```yaml
ecos:
  kind: rest
  base: "https://ecos.bok.or.kr/api"
  auth: env:ECOS_API_KEY
  stats:
    - { stat_code: "721Y001", note: "시장금리(일별) — item_code는 구현 시 API 응답으로 검증 (함정 K-04)" }
```
설정이 요구하는 환경변수명(`ECOS_API_KEY`)과 `.env`의 부재가 정확히 일치 — 오타·별칭 문제가
아니라 키 자체가 발급/등록되지 않은 상태다.

## 2. 시도하지 않은 것 (범위 외로 남김, 추측 방지)

- ECOS `StatisticItemList`(메타 조회) 호출: **미시도** — 인증키 없이는 401/오류만 반환할 것이
자명하여 실호출 자체가 무의미(브리프의 "실호출 최소화" 원칙과도 부합).
- ECOS `StatisticSearch`(데이터 검증 조회) 호출: **미시도**, 동일 이유.
- 웹 검색·공개 문서로 "국고채 3년/회사채 AA-3년 항목코드가 보통 이렇다"는 추정: **하지 않음** —
  브리프가 "추측으로 코드값을 채우지 마라"고 명시했고, ECOS `721Y001` 하위 item_code는 공개
  문서만으로 확정할 수 없는(계층형 코드가 버전마다 바뀔 수 있는) 값이라 실측 없이 기록하면
  위험하다.

## 3. 확정 반영

- `configs/indicators.yaml` — **무변경**(`VERIFY` 2건 그대로 유지).
- `configs/sources.yaml` — **무변경**.
- git 커밋 없음(변경 사항이 없어 커밋할 대상이 없음 — 브리프의 커밋 문구
  `m1-00b: verify ECOS item codes...`는 이번 세션에서 사용하지 않는다. 실제로 코드값을 확정한
  뒤에만 그 메시지가 정확하다).

## 4. 재개 조건 (Advisor·사용자 판단 필요)

1. ECOS(한국은행 경제통계시스템, ecos.bok.or.kr) Open API 페이지에서 **API 인증키를 신규 발급**
   (무료, 이메일 인증 — 계정 신청 자체는 이 Worker 권한 밖).
2. 발급받은 키를 `.env`에 `ECOS_API_KEY=<키>` 형식으로 추가(다른 3개 키와 동일 파일, 코드/yaml에
   노출 금지 — CLAUDE.md K-17류 원칙과 동일 적용).
3. 위 두 조건이 갖춰진 뒤 본 브리프(MT1-00b)를 그대로 재실행하면 2단계(메타 조회 1~2회 +
   검증 조회 2회)부터 정상 진행 가능하다. 이번 세션에서 확인한 사전 점검·환경 사실(§1)은
   재실행 시 다시 반복할 필요 없이 그대로 유효하다.

## 5. 검증

- `uv run pytest -q` → **178 passed**(변경 없음이 원인 — 회귀 아님, 베이스라인 그대로 유지 확인).
- `uv run pytest -q tests/test_configs_schema.py` → 스키마 테스트는 `item_codes` 값의 내용(문자열
  "VERIFY" 포함)을 검사하지 않음을 확인(grep 결과 없음) — VERIFY 미교체가 현재 스키마 검증을
  깨지 않는다.
- `.env`의 어떤 키 값도 본 문서·터미널 출력에 기록하지 않았다(변수명만 노출, §1).

## 6. 생성 파일 목록

- `docs/journal/2026-08-07_MT1-00b_ecos_item_codes.md` (본 문서)
- 그 외 파일 변경 없음.
