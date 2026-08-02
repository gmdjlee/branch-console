# TASK: AL — Analogue Library 구축

> **[v3 델타]** (CHANGES_V3 §4) 명칭 매핑: G3=GS3 (MASTER_PLAN v3 Track S).

근거: D-01, D-06, 아키텍처 6.1장 · 선행: G2(C1 보정) 통과 · **P2와 병렬 진행 가능**
소비 파일: configs/analogue_seed.yaml · 산출 스키마: analogue-record/1 (본 문서 정의)

## 목표
과거 26개 분기점 각각에 대해 (발화 시점 지표 벡터, 이후 가격·섹터 경로 통계, 한국어 요약)을
계산·저장하고, P3 judgment가 쓸 검색 모듈을 제공한다. LLM이 진폭을 지어내지 못하게 만드는 근거 저장소다.

## 산출 스키마: analogue-record/1 (contracts/analogue.py 신설 — 허가된 추가)
```python
class AnalogueRecord(BaseModel):
    schema_id: Literal["analogue-record/1"]
    event_id: str
    anchor_date: date
    type: EventType
    name_kr: str
    trigger_vector: dict[
        str, float
    ]  # 지표 id -> anchor 시점 값(z/level, 보정판 레지스트리)
    trigger_severities: dict[str, int]
    paths: dict[
        str, dict[str, float]
    ]  # {"KOSPI": {"d5": -6.2, "d20": ...}, "USDKRW": ...}
    sector_paths_d20: dict[str, float]
    outcome_summary_kr: str  # 3문장 이내, Batch API 생성
    data_quality: dict[str, float]  # 지표별 결측률
```

## 서브태스크 (AL-01 → 02 → 03 → 04, 03과 04는 병렬 가능)

### AL-01 백필
seed 26건 window 전체 + anchor 이전 252영업일(z-score 기준선)을 lake에 백필.
C1 백필과 중복 창은 재수집 금지(lake 존재 확인 후 스킵). 함정: K-03, K-05.
완료: 사건×지표 결측률 매트릭스 리포트, 필수 지표(KOSPI, USDKRW, VIX, HY OAS) 결측률 0%.
2007~2012 구간에서 조회 불가한 지표(예: ^VIX3M, MOVE)는 결측 허용하되 data_quality에 기록.

### AL-02 경로·벡터 계산
`scripts/build_analogues.py`: outcome_spec대로 5/20/60일 수익률, 섹터 20일 경로 계산.
KRX 업종지수 코드는 pykrx 메타로 실측 확정 후 스크립트 상수가 아닌 `configs/analogue_seed.yaml`에
주석으로 기록 (K-13). 산출: `lake/analogues/analogue_records.parquet` + Postgres 적재.
완료: 26건 전건 레코드 생성, 골든 검증 — 2024-08_carry_unwind의 KOSPI d5는 음수이며
2020-02_covid의 KOSPI d20보다 낙폭이 작아야 함(순서 관계 테스트).

### AL-03 요약 생성 (Batch API)
26건의 outcome_summary_kr를 Anthropic **Batch API + Haiku**로 일괄 생성.
프롬프트: 사건명·경로 수치만 입력, "수치에 없는 서술 금지" 규칙, 3문장 이내.
완료: 전건 생성, 수치 일치 자동 검증(요약 내 숫자가 레코드 수치 집합의 부분집합).

### AL-04 검색 모듈
`judgment/analogue.py` — `find(trigger_vector, event_type, k=3) -> list[AnalogueRef]`.
1차 필터 type(동일 또는 mixed 포함), 2차 랭킹 trigger_vector 표준화 코사인 유사도.
pgvector 사용은 선택(26건 규모면 인메모리 허용, 인터페이스만 고정).
완료: 픽스처 테스트 — 2024-08형 벡터 질의 시 top-3에 2018-02 또는 2020-02가 포함,
2022-10형(국내 신용) 질의 시 top-1이 domestic_kr 유형.

### AL-05 (조건부) 시드 확장
2025H2~2026 후보 사건을 뉴스 검색으로 수집하되, **추가는 데이터 검증 통과 건만**,
seed version 0.2.0으로 올리고 게이트 리포트에 추가 목록 명시. 검증 불충분 건은 보류 목록으로.

## 게이트 (G3의 AL 파트)
AL-01~04 완료 기준 + `docs/gates/GATE_G3_AL.md`. P2 파트와 합쳐 G3 승인 후 P3 착수.
