# TASK: branch-console P4 — 선택 확장 (SNS 보조 · 공식 텍스트 · 앱 연동)

> **[v3 델타]** (CHANGES_V3 §4) 모듈 C(앱 연동 API)는 INT로 흡수·폐지한다. 모듈 A·B만 존치.

근거: 타당성 검토 4장(SNS 후순위 결정), 아키텍처 3장 · 선행: G4 통과, 상시 운영 안정 2주 이상
성격: **선택.** 각 모듈은 독립이며 개별 착수·개별 게이트. 사용자가 모듈 단위로 지시한다.

## 모듈 A — SNS 보조 신호 (무료 범위 한정)
- Reddit(praw, 무료 범위): r/stocks, r/investing, r/wallstreetbets 제목 스트림 → 토픽 매칭 기사량만 집계.
- StockTwits 공개 API: 지정 심볼 메시지 볼륨.
- 원칙: **감성 분석 없음, 볼륨 z만.** 트리거 원천 금지 — indicators.yaml에 `sns_volume_z`
  (axis: news, weight 1.0, enabled true) 1종만 신설(허가된 추가). X 공식 API 도입 금지(비용 결정 유지).
- 완료: 수집 픽스처 테스트 + 골든 무회귀 + 2주 관찰 후 유지/제거 판단 리포트.

## 모듈 B — 공식 텍스트 수집 ("방송" 대체)
- FOMC 성명·의사록, 한국은행 금통위 의결문·기자간담회 자료 페이지 수집기(공식 사이트, 발표 캘린더 연동).
- 처리: 원문 lake 보관 + Haiku로 3문장 요약 → 해당일 다이제스트에 첨부. 지표화하지 않는다(해석은 판단 계층 몫).
- 완료: 과거 발표 2건 픽스처 파싱 테스트 + 실발표 1회 파이프라인 통과.

## 모듈 C — TinyOscillator 연동 (read-only API)
- FastAPI 서비스: `GET /v1/phase`(현재 국면·composite·발화 상위 5), `GET /v1/snapshots/latest`,
  `GET /v1/snapshots/{id}` — 전부 읽기 전용, Bearer 토큰, **Tailscale 인터페이스에만 바인딩**(공인망 노출 금지).
- 응답 스키마는 contracts를 그대로 직렬화(신규 스키마 금지). OpenAPI 문서 자동 생성.
- Android 측 작업(Compose 탭, Retrofit 클라이언트)은 TinyOscillator 저장소의 별도 TASK로 분리하며,
  본 저장소 산출물은 API 서버 + OpenAPI 스펙 + 통합 테스트까지.
- 완료: API 계약 테스트 + 토큰 없는 요청 401 + 외부 인터페이스 바인딩 부재 검증.

## 게이트
모듈별 `docs/gates/GATE_P4_<모듈>.md`. 모듈 A는 "유지 가치" 판단이 게이트 항목에 포함된다
(2주 관찰에서 뉴스 축과 정보 중복이 크면 제거를 권고안으로 제시).
