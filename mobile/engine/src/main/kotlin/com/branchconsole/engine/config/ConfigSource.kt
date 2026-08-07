package com.branchconsole.engine.config

import java.io.InputStream

/**
 * `:engine`은 Android API에 의존하지 않는다(docs/plans/M1_PLAN_A.md AD-A1, §2.1) — YAML 설정을
 * 여는 방법을 이 인터페이스 하나로 추상화한다. 앱은 `AssetManager`로, 테스트는 저장소
 * `configs` 디렉터리의 YAML 파일(예: indicators.yaml)로 구현한다("추상화는 이 하나만
 * 만든다" — 실사용처가 둘이므로 YAGNI 위반 아님).
 */
fun interface ConfigSource {
    /** @param name 예: "indicators.yaml", "statemachine.yaml". 없으면 예외로 실패한다(조용한
     * 기본값 금지 — PRINCIPLES "Fail Fast"). */
    fun open(name: String): InputStream
}
