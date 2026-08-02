# CHANGES_V3 — v2 저장소에 적용할 변경 명세 (MT0-01의 실행 지시서)

- 적용 주체: Claude Code(M0 세션). 실제 파일을 열어 아래 명세대로 수정한다. **여기 없는 SSOT 수정 금지.**
- 검증: 각 절 끝의 검증 커맨드가 green이어야 다음 절 진행.

## §0. 신규 파일 배치 (본 번들 → 저장소)

그대로 복사: `docs/MASTER_PLAN.md`(대체), `docs/ARCHITECTURE_SPLIT.md`, `docs/BACKTEST_PLAN.md`,
`docs/AAA_QUALITY_STANDARD.md`, `docs/MCP_SETUP.md`, `docs/CHANGES_V3.md`(본 문서), `docs/reviews/PLAN_REVIEW_V3.md`, `PROGRESS.md`(대체),
`TASK_mobile_m0~m3.md`, `.claude/agents/{plan-architect,aaa-critic,kotlin-implementer,ui-craftsman,backtest-analyst}.md`,
`.claude/skills/{design-system,backtest-run,gate-report}/SKILL.md`.
기존 `.claude/agents/{python-implementer,qa-verifier,data-verifier}.md`·서버 TASK 문서·configs·contracts·prompts는 유지.
검증: `ls docs/ .claude/agents/ .claude/skills/*/ | sort` 로 존재 확인.

## §1. docs/P0_DESIGN_DECISIONS.md — 결정 append

파일 끝에 구분선과 함께 "아키텍처 v3 결정 (2026-08-02)" 절을 추가하고 D-15~D-21 **요약**(각 3줄 이내)과
"전문: docs/ARCHITECTURE_SPLIT.md" 포인터를 기록한다. D-11 항목 바로 아래에 한 줄 추가:
`> [2026-08-02] 본 결정의 모델 ID는 유효함이 재확인됨. 운용 규칙은 D-20이 승계한다(런타임 티어링 + 개발 시 배정 + 분기 재검증).`
검증: `grep -c "D-2[01]" docs/P0_DESIGN_DECISIONS.md` ≥ 2.

## §2. configs 패치 (스키마 버전 0.3.0-rc로 상향은 MT0-05에서)

### 2.1 statemachine.yaml
1. `profiles:` 블록 신설(ARCHITECTURE_SPLIT D-16의 YAML 그대로). 기존 최상위 전이 파라미터(승격 sustain 2틱·강등 6틱·최소 체류 4틱·냉각)는 값 변경 없이 `profiles.server_intraday`로 **이동**한다(아직 소비 코드 없음 — 이동 안전). 주석으로 "엔진은 프로파일 키를 주입받는다(D-16)" 명기.
2. `llm_tiering`을 D-20 §20.1 확정값으로 고정한다:
```yaml
llm_tiering:            # D-20 확정(2026-08-02). 무날짜 ID는 고정 스냅샷 — 별칭 아님.
  scenario_report:      # ORANGE/RED 심층 리포트
    model: claude-opus-5
  amber_summary:        # AMBER 요약
    model: claude-sonnet-5
  daily_digest:         # 일일 다이제스트(고정 호출 — 비용 누적 경로)
    model: claude-haiku-4-5-20251001
  # 변경 절차: 분기 C-주기에 모델 목록 실조회 → 폐기 일정 확인 → 여기와 .claude/agents/* 동시 갱신(D-20 §20.3)
```
기존 키 구조(온도·max_tokens·재시도 등)는 유지하고 `model` 값만 교체한다.
3. 검증: `python3 -c "import yaml;d=yaml.safe_load(open('configs/statemachine.yaml'));assert 'mobile_daily' in d['profiles']"`.

### 2.2 indicators.yaml
`engine.stale_data_max_age`를 `engine.stale_profiles`로 개편: `server_intraday`에 기존 값 그대로,
`mobile_daily: { daily_kr: 30h, daily_us: 48h, fred_daily: 96h }` 추가(주말·T+1 반영 가설, BT-03 대상 주석).
검증: yaml 파싱 + 두 프로파일 키 존재 assert.

### 2.3 sources.yaml
각 provider에 `consumers:` 주석 표기(server / mobile / both). 신규 provider 2개 추가:
`kis`(kind: rest, enabled: false 기본, consumers: mobile, usage: preview_realtime, notes: "옵션 — TinyOscillator 검증 자산"),
`stooq`(kind: rest, consumers: mobile, usage: yahoo_fallback, notes: "K-18 폴백. MT1-04a에서 실측").
검증: yaml 파싱.

## §3. CLAUDE.md 편집 (앵커 기반 — 실제 파일에서 확인 후 적용)

1. 서두: "…게이트는 `docs/MASTER_PLAN.md`를 따른다." 줄 뒤에 추가 →
   `시스템 분리·프로파일은 docs/ARCHITECTURE_SPLIT.md, 품질·비평 절차는 docs/AAA_QUALITY_STANDARD.md, 백테스트는 docs/BACKTEST_PLAN.md.`
2. 서브에이전트 규칙 줄(구현=python-implementer…)을 다음으로 교체:
   ```
   - 위임 시 .claude/agents/의 전용 서브에이전트를 사용한다: 계획=plan-architect, 품질 판정=aaa-critic(수정 권한 없음),
     Python 구현=python-implementer, Kotlin/Android 구현=kotlin-implementer, UI·그래픽=ui-craftsman,
     백테스트 실행·분석=backtest-analyst, 산출물 기계 검증=qa-verifier, 외부 API 실측=data-verifier.
   - 모든 서브태스크는 qa-verifier → aaa-critic 2단 판정을 PASS해야 완료다(AAA_QUALITY_STANDARD §1).
   - 모델 배정은 D-20 §20.2 고정: Advisor=claude-opus-5, 계획·비평=claude-opus-5, 구현·검증=claude-sonnet-5.
     에이전트 model 필드는 전체 ID로 쓰고, CLAUDE_CODE_SUBAGENT_MODEL은 설정하지 않는다(프론트매터를 덮어씀).
   ```
3. 함정 목록: K-13 항목 바로 뒤, "## 4. 상태 추적" 앞에 삽입:
   ```
   - **K-14 WorkManager**: 일일 작업은 정시 보장 없음 — 지연 허용 + 앱 실행 시 캐치업(멱등)이 설계다. 정확 알람으로 우회하지 마라.
   - **K-15 OEM 절전**: 제조사 절전 관리자가 작업을 죽일 수 있다. 온보딩에서 예외 등록 안내, 틱 누락은 실행 이력에 노출.
   - **K-16 assets 드리프트**: configs/prompts는 syncConfigs Gradle task로만 복사, SHA-256 계측 테스트로 일치 강제. 수동 복사 금지.
   - **K-17 모바일 키 보안**: API 키는 Keystore/EncryptedSharedPreferences. 코드·assets·로그·백업 포함 금지.
   - **K-18 야후계 비공식**: 엔드포인트 변경·차단 상시 가정. Stooq 폴백 경로와 지표별 결측 처리를 함께 구현.
   ```
4. 문서 말미에 절 추가:
   ```
   ## 6. 워크플로 v3 (요약)
   세션 시작 프롬프트에 ultracode 활성 지시(세션 한정). 설계·비평·회귀분석 턴에 ultrathink.
   phase 착수 전 plan council(plan-architect 병렬 → aaa-critic 라운드 → Advisor 병합 → 사용자 승인).
   골든 무회귀 × 2프로파일 + (M1 이후) Kotlin 패리티가 전 phase 공통 회귀 게이트다.

   ## 7. SSOT 보호 훅 (선택 강화)
   워크플로 서브에이전트는 편집이 자동 승인될 수 있으므로, .claude/settings.json의 PreToolUse 훅으로
   configs/·contracts/·prompts/ 경로의 Write|Edit를 차단하는 가드를 둘 수 있다(현재 phase TASK가 허가한 경우
   Advisor가 일시 해제). 훅 스키마는 사용 중인 Claude Code 버전 문서로 확인 후 작성한다.
   ```
검증: `grep -c "K-1[4-8]" CLAUDE.md` = 5, `grep -c "워크플로 v3" CLAUDE.md` = 1, `grep -c "claude-opus-5" CLAUDE.md` ≥ 1.

## §3.5 개발 시 모델 배정 적용 (D-20 §20.2)

1. `.claude/agents/` 8종의 `model` 필드를 전체 ID로 확정한다. 신규 5종은 본 번들 파일에 이미 반영돼 있고,
   **기존 3종은 `model: sonnet` → `model: claude-sonnet-5`로 수정**한다(python-implementer, qa-verifier, data-verifier).
2. 세션 기동: `claude --model claude-opus-5` (Advisor = Opus 5). `/model`로 바꾼 경우 서브에이전트 프론트매터는 영향받지 않는다.
3. `CLAUDE_CODE_SUBAGENT_MODEL`이 설정돼 있으면 **프론트매터 배정이 전부 무시된다**. 해제 확인:
   ```bash
   echo "${CLAUDE_CODE_SUBAGENT_MODEL:-<unset>}"   # <unset> 이어야 한다
   grep -n "CLAUDE_CODE_SUBAGENT_MODEL" .claude/settings.json ~/.claude/settings.json 2>/dev/null
   ```
4. (선택) 조직 정책으로 모델을 제한하는 경우, 허용 목록에 `claude-opus-5`·`claude-sonnet-5`가 없으면
   Claude Code가 지정을 건너뛰고 메인 세션 모델로 실행한다 — 첫 위임 후 실제 사용 모델을 1회 확인한다.
5. 검증: `grep -h "^model:" .claude/agents/*.md | sort | uniq -c` → `claude-opus-5` 2건, `claude-sonnet-5` 6건.

## §4. 서버 TASK 델타 (본문 원칙 유지, 머리에 [v3 델타] 블록만 추가)

- `TASK_branch_console_p1.md`: ST-05·ST-06은 자체 구현 대신 **engine_ref import**로 변경(중복 로직 금지), 상태기계는 `server_intraday` 프로파일 명시 주입. 명칭 매핑 "P1=S1, G1=GS1" 표기.
- `TASK_calibration_c1.md`: 초기값을 레지스트리 0.3.0에서 상속. GM0에서 상신된 D-14·F-04·F-06 결정의 **실측 확정**이 완료 조건에 추가됨을 명기. "G2=GS2".
- `TASK_branch_console_p2.md`/`TASK_analogue_library.md`: "G3=GS3"만 표기.
- `TASK_branch_console_p3.md`: [v3 델타] ST-P3-07 신설 예고 — 스냅샷 발행 모듈(INT 대비, 상세는 INT TASK에서). "G4=GS4".
- `TASK_branch_console_p4.md`: 모듈 C를 "INT로 흡수·폐지" 표기, A·B만 존치.
검증: 각 파일 `grep -c "v3 델타"` = 1.

## §5. 적용 순서와 최종 검증

§0 → §1 → §2 → §3 → §4 순서. 최종:
```bash
python3 - <<'EOF'
import yaml
for f in ["configs/indicators.yaml","configs/statemachine.yaml","configs/sources.yaml"]:
    yaml.safe_load(open(f)); print(f,"OK")
EOF
uv run ruff check . && uv run pytest -q   # 기존 테스트 무회귀 (contracts 스냅샷 포함)
```
완료 후 커밋 메시지: `chore(v3): dual-track architecture docs + SSOT patches (D-15..D-21)`.
