package com.branchconsole.engine.config

private const val NUM_PATTERN = """[-+]?\d+(?:\.\d+)?"""

/**
 * `engine_ref.registry`의 transform 문자열 파서 1:1 이식(M1_PLAN_D.md §2.4.3 "_BUILDERS 이식
 * 규율" — 파라미터는 전부 여기서만 뽑는다, 코드 리터럴 금지 CLAUDE.md §1).
 */
object TransformParser {
    /**
     * `callName(...)` 자신의 여는 '('에 대응하는 닫는 ')' 사이 본문을 반환한다. 단어 경계(`\b`)
     * 로 `zscore`가 `neg_zscore(`의 접미사로 오매칭되지 않게 하고, 괄호 깊이를 세어 자신의 짝을
     * 찾는다(`gated(zscore(...), gate=...)`처럼 인자 목록이 다른 호출을 중첩할 수 있으므로).
     */
    fun extractCallBody(
        callName: String,
        transform: String,
    ): String {
        val opening =
            Regex("\\b${Regex.escape(callName)}\\s*\\(").find(transform)
                ?: error("'$callName' not found in transform: '$transform'")
        var i = opening.range.last + 1
        val start = i
        var depth = 1
        while (depth > 0) {
            check(i < transform.length) { "unbalanced parentheses for '$callName' in transform: '$transform'" }
            when (transform[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return transform.substring(start, i - 1)
    }

    /** 콤마로 인자 목록을 분리하되, 중첩 괄호 안의 콤마는 분리 지점으로 보지 않는다. */
    fun splitTopLevelArgs(body: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (ch in body) {
            when {
                ch == '(' -> {
                    depth++
                    current.append(ch)
                }
                ch == ')' -> {
                    depth--
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    args.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        args.add(current.toString())
        return args.map { it.trim() }
    }

    private val KWARG_RE = Regex("""^(\w+)\s*=\s*(true|false|"[^"]*"|$NUM_PATTERN)$""")

    private fun coerceKwargValue(v: String): Any =
        when {
            v == "true" -> true
            v == "false" -> false
            v.startsWith('"') -> v.trim('"')
            v.contains('.') -> v.toDouble()
            else -> v.toInt()
        }

    /**
     * `callName(...)`의 **최상위(직접) 인자**에서만 kwargs(key=value)를 추출한다. 중첩된 다른
     * 호출(예: `gated(zscore(..., window=60), gate=...)` 안 `zscore`의 `window`)이 바깥 호출의
     * kwargs로 누출되지 않는다 — 콤마로 분리한 최상위 인자 각각이 통째로 `key=value` 형태일
     * 때만 채택한다.
     */
    fun parseCallKwargs(
        callName: String,
        transform: String,
    ): Map<String, Any> {
        val body = extractCallBody(callName, transform)
        val out = LinkedHashMap<String, Any>()
        for (arg in splitTopLevelArgs(body)) {
            val m = KWARG_RE.matchEntire(arg) ?: continue
            out[m.groupValues[1]] = coerceKwargValue(m.groupValues[2])
        }
        return out
    }

    private val FALLBACK_WINDOW_RE = Regex("""_(\d+)d$""")

    /** 예: "realized_vol_kospi_20d" -> 20. K-02 폴백 식별자에 내장된 윈도우를 정규식으로
     * 추출한다(하드코딩 금지 — [com.branchconsole.engine.transforms.RollingTransforms.realizedVolKospi20d]
     * 의 window 인자는 여기서만 나온다). */
    fun parseFallbackWindow(fallbackId: String): Int {
        val m = FALLBACK_WINDOW_RE.find(fallbackId) ?: error("cannot extract window from fallback id: '$fallbackId'")
        return m.groupValues[1].toInt()
    }

    private val GATE_RE = Regex("""(\w+)\s*(<=|>=|==|<|>)\s*($NUM_PATTERN)""")

    /** `"daily_return < 0"` 형태의 gate 문자열을 (변수명, 연산자, 임계값)으로 분해. */
    fun parseGate(gateExpr: String): Triple<String, String, Double> {
        val m = GATE_RE.find(gateExpr) ?: error("unrecognized gate expression: '$gateExpr'")
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3].toDouble())
    }

    private val WINDOW_KWARG_RE = Regex("""(?:window|lookback)\s*=\s*(\d+)""")
    private val SUFFIX_DAYS_RE = Regex("""_(\d+)d\b""")

    /**
     * `requiredRows(지표)` 도출 — docs/plans/M1_PLAN_D.md §2.3.2. 코드 리터럴이 아니라
     * transform 문자열(+ `source.fallback`, 있으면)에서 파생한다:
     * `1 + Σ(window=/lookback= 정수) + Σ(식별자 접미사 _Nd의 N)`. 보수적 상한이다(과대평가는
     * 안전 — 값에 영향 없이 웜업 충족 기준만 높인다, CLAUDE.md §1 SSOT 규율). D 문서 §2.3.2
     * 표의 전 지표 값과 일치가 대조 테스트의 기준이다(예: `spx_drawdown_momentum` = 318,
     * `vkospi_z`(폴백 경로, `source.fallback` 반영) = 273).
     */
    fun requiredRows(spec: IndicatorSpec): Int {
        var total = 1
        total += WINDOW_KWARG_RE.findAll(spec.transform).sumOf { it.groupValues[1].toInt() }
        total += SUFFIX_DAYS_RE.findAll(spec.transform).sumOf { it.groupValues[1].toInt() }
        (spec.source["fallback"] as? String)?.let { fallback ->
            total += SUFFIX_DAYS_RE.findAll(fallback).sumOf { it.groupValues[1].toInt() }
        }
        return total
    }
}
