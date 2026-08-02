# MT0-01 모델 ID 스모크 검증 (D-20 §20.1 확정값)

- 작성: 2026-08-02, data-verifier Worker
- 대상: `configs/statemachine.yaml` `llm_tiering`에 이미 기입된 D-20 확정 ID 3개
  (`claude-opus-5`, `claude-sonnet-5`, `claude-haiku-4-5-20251001`)
- 성격: **실측 후 선택이 아닌 확정값의 스모크 검증.** 본 문서는 검증 결과만 기록하며,
  configs는 어떤 경우에도 수정하지 않았다(브리프 지시 준수).

## 1. 검증 일시 / 호출 총 횟수

- 검증 시각: 2026-08-02 05:59:43 ~ 06:00:22 UTC (2026-08-02 14:59:43 ~ 15:00:22 KST)
- 총 실호출: **4회** (계획된 4회 + 예비 1회는 미사용)
  1. `GET /v1/models?limit=100`
  2. `POST /v1/messages` (`claude-opus-5`)
  3. `POST /v1/messages` (`claude-sonnet-5`)
  4. `POST /v1/messages` (`claude-haiku-4-5-20251001`)
- 예비 재시도 미사용 사유: 발생한 오류가 429/5xx(일시 오류)가 아닌 `400 invalid_request_error`
  (계정 크레딧 잔액 부족)로, 재시도해도 결과가 달라지지 않는 결정적 오류이기 때문.

## 2. 결과 표

| 모델 ID | 목록 존재(완전 일치) | 호출 HTTP 상태 | 지연(ms) | input/output 토큰 | 구조화 출력 파싱 |
|---|---|---|---|---|---|
| `claude-opus-5` | **O** | 400 (`invalid_request_error`) | 306 | N/A (호출 차단) | N/A |
| `claude-sonnet-5` | **O** | 400 (`invalid_request_error`) | 340 | N/A (호출 차단) | N/A |
| `claude-haiku-4-5-20251001` | **O** | 400 (`invalid_request_error`) | 318 | N/A (호출 차단) | N/A |

- `GET /v1/models` 응답(`has_more: false`, 총 11개 모델) 중 3개 ID가 각각 리스트에 **완전
  일치(부분일치 아님)**로 존재함을 확인.
- 3건의 `POST /v1/messages` 호출(강제 `tool_choice: {"type":"tool","name":"record_phase"}`,
  `max_tokens: 256`, 스키마: `phase` enum(GREEN/AMBER/ORANGE/RED) + `composite` number)은
  **3건 모두 동일한 응답 원문**으로 실패:

  ```json
  {"type":"error","error":{"type":"invalid_request_error",
   "message":"Your credit balance is too low to access the Anthropic API. Please go to Plans & Billing to upgrade or purchase credits."},
   "request_id":"req_011CddQp..."}
  ```

  (request_id 3건: `req_011CddQpmnbLejWpohzZkwk7`, `req_011CddQptCJaxSaqctwzbb4q`,
  `req_011CddQpzgz261owqA6YPYhD` — opus/sonnet/haiku 순)

- 구조화 출력(도구 강제 호출 → JSON 스키마 부합) 검증은 메시지 호출 자체가 차단되어
  **수행 불가**.

## 3. 판정

**3/3 유효 아님 — FAIL (부분).**

- (a) 목록 존재: **3/3 통과** — 3개 ID 모두 `/v1/models`에 완전 일치로 존재.
- (b) 메시지 호출 200: **0/3 통과** — 3건 모두 `400 invalid_request_error`.
- (c) 구조화 출력 파싱 성공: **0/3 통과** — (b) 차단으로 시도 불가.

완료 기준(3개 ID 전부 a·b·c 통과)을 충족하지 못했다. 단, 실패 원인은 **모델 ID 자체의
존재/유효성 문제가 아니라 호출에 사용된 계정의 API 크레딧 잔액 부족(billing)**이다.
D-20이 확정한 3개 ID는 (a) 기준으로는 Anthropic 카탈로그에 실재함이 확인되었고, (b)(c)는
계정 크레딧 충전 후 재검증이 필요한 상태로 남는다.

## 4. 조치 및 제약 준수

- **configs 수정 없음.** `configs/statemachine.yaml`의 `llm_tiering` 블록은 변경하지
  않았다(브리프 지시: "어떤 경우에도 configs를 수정하지 마라").
- **대안 코드값 적용 없음.** 실패가 확인되었으므로 브리프 규칙에 따라 대안 검토·추측
  코드값 채움을 하지 않고 본 문서로 FAIL을 보고한다.
- **후속 조치 제안**(Advisor 승인 필요, 본 문서는 제안만 기록): 계정 Plans & Billing에서
  크레딧을 충전한 뒤, 동일 스모크 절차(호출 ≤5회, 본 문서의 payload 재사용 가능:
  `C:\Users\gmdjl\AppData\Local\Temp\claude\D--wp-2026-branch-console\5525d2c2-837b-4a82-8d38-4eb2b2806fe5\scratchpad\payload_{opus,sonnet,haiku}.json`)를
  재실행하여 (b)(c)를 확정할 것을 권고한다.

## 5. 키 미노출 확인

`ANTHROPIC_API_KEY`는 `.env`에서 셸 환경변수로만 로드했으며(`export
ANTHROPIC_API_KEY="$(grep '^ANTHROPIC_API_KEY=' .env | cut -d= -f2- | tr -d '\r\n')"`),
curl 요청 헤더는 `-H "x-api-key: $ANTHROPIC_API_KEY"` 변수 참조 형태로만 실행했다. 본
문서, 터미널 출력, 응답 캡처본(`resp_*.json`, `models_list.json`) 어디에도 키 값(원문·
부분 문자열 포함)이 기록되지 않았음을 확인한다.
