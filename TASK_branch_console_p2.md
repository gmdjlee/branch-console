# TASK: branch-console P2 — 뉴스 계층 + HMM 확인 + 관측

> **[v3 델타]** (CHANGES_V3 §4) 명칭 매핑: G3=GS3 (MASTER_PLAN v3 Track S).

근거: D-01, D-05, 아키텍처 L1/L2 · 선행: G2 통과 · **AL과 병렬 진행 가능** · 규칙: CLAUDE.md
소비 파일: configs/news_topics.yaml, configs/sources.yaml(news_pipeline), configs/indicators.yaml(뉴스 2종)

## 목표
뉴스 수집→임베딩 중복제거→토픽 클러스터→기사량·노벨티 지표를 가동해 news 축을 활성화하고,
HMM 확인 계층과 Grafana 관측을 붙인다. **골든 리플레이 무회귀가 절대 조건.**

## 서브태스크

```
ST-P2-01 ──▶ ST-P2-02 ──▶ ST-P2-03 ──▶ ST-P2-04 ──▶ ST-P2-07
ST-P2-05 (독립, 병렬)      ST-P2-06 (독립, 병렬)
```

### ST-P2-01 뉴스 수집기 3종
- naver: 뉴스 검색 API, 토픽별 keywords_kr 질의, 시간당 1회. **일 25,000콜 쿼터 내 설계** — 토픽 10개×키워드 상위 3개×24회 = 720콜/일 상한 검증 로직 포함 (함정 K-10).
- rss: sources.yaml에 피드 목록 신설(연합인포맥스·로이터·주요 통신 헤드라인 등 8~12개, Worker가 유효 피드 실측 선정 후 기록).
- gdelt: DOC 2.0 API, 토픽별 keywords_en 질의로 volume timeline + tone (함정 K-09: 노이즈 크므로 원시값 저장만, 판정은 z에서).
공통: lake append(payload=제목·url·게시시각·토픽후보), URL 정규화 1차 중복 제거.
완료: 픽스처 파서 테스트 + 수동 1회 실행에서 3소스 모두 기사 유입 확인.

### ST-P2-02 임베딩 서비스
bge-m3(sentence-transformers)로 제목+리드 임베딩, pgvector 저장.
함정 K-08: 최초 실행 시 모델 다운로드 큼 — 모델 리비전 고정(pin), CPU 스레드 수 설정, 배치 처리.
중복 판정: 72시간 창 내 코사인 ≥ 0.90 → 동일 스토리로 대표 1건만 활성.
완료: 한/영 동일 사건 기사쌍 픽스처가 중복으로 묶이는 테스트 + 처리량 스모크(1천 건 < 5분, 8745HS CPU).

### ST-P2-03 토픽 배정·노벨티
토픽 배정: Longest-Match 키워드 우선, 무배정 기사는 임베딩 최근접 토픽 센트로이드(코사인 ≥ 0.45)로 보조 배정.
novelty_score: 최근 90일 활성 기사 임베딩 kNN(k=10) 평균 거리. 클러스터 집계는 시간당 갱신.
완료: 합성 픽스처로 기사량 z·노벨티 산출 골든 테스트.

### ST-P2-04 뉴스 지표 활성화 (허가된 config 수정)
indicators.yaml의 news_volume_z, news_novelty를 `enabled: true`로 전환, 엔진 연결.
**허가 범위는 enabled 플래그와 news_pipeline provider enabled뿐** — 임계·가중치 변경 금지.
완료: 엔진 테스트에 뉴스 2종 케이스 추가 + **골든 리플레이 재실행 무회귀**
(뉴스 픽스처 부재 구간은 결측 처리로 기존 결과와 동일해야 함 — D-02 분모 제외 규칙 검증 겸용).

### ST-P2-05 HMM 확인 계층 (병렬 가능)
KOSPI 일수익률 2상태 가우시안 HMM(hmmlearn), 월 1회 재적합, 고변동 국면 사후확률 산출.
**함정 K-11(look-ahead)**: 리플레이에서는 각 틱 시점까지의 데이터로만 적합된 파라미터 사용
(월별 파라미터 스냅샷을 lake에 저장하는 walk-forward 구조).
statemachine.yaml `hmm_confirmation.enabled: true` 전환(허가된 수정, 완화 규칙은 정의 그대로).
완료: 합성 국면 데이터 검출 테스트 + 2020-02 리플레이에서 posterior ≥ 0.8 도달 + 골든 무회귀.

### ST-P2-06 Grafana 프로비저닝 (병렬 가능)
datasource(postgres) + 대시보드 JSON 2장: ① composite·phase 타임라인 + 지표 severity 히트맵
② 뉴스 토픽별 기사량·노벨티. 프로비저닝 파일로 저장소에 커밋(수동 설정 금지).
완료: `docker compose up` 직후 대시보드 자동 표시.

### ST-P2-07 evidence 연결
EvidencePack.news_clusters 채움(활성 토픽 상위 5, 대표 헤드라인 ≤ 5).
일일 다이제스트 프롬프트에 뉴스 섹션 추가(prompts/daily_digest.md의 지정 슬롯만 수정 허가).
완료: 통합 테스트 — once 실행 시 다이제스트에 뉴스 섹션 포함.

## 완료 기준 (P2 파트)
전 서브태스크 테스트 green + 골든 무회귀 + 3영업일 섀도 가동 로그(뉴스 유입·쿼터·오류율) 첨부.
`docs/gates/GATE_G3_P2.md` 작성 → AL 파트와 합쳐 G3 사용자 승인.
