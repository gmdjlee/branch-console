package com.branchconsole.app.onboarding

import com.branchconsole.app.collectors.CollectorResult
import com.branchconsole.app.collectors.RetryPolicy
import com.branchconsole.app.collectors.fred.FredObservationsCollector
import com.krxkt.api.KrxClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val VERIFY_SERIES = "VIXCLS" // small, always-available FRED series -- cheap probe.
private val SINGLE_ATTEMPT = RetryPolicy(attempts = 1, backoffMs = emptyList())

/**
 * MT1-08c — [검증] 버튼의 최소 실호출(브리프 §4 "검증 최소"). 목적은 `KEY_INVALID`를
 * "내일 알게 되는 실패"에서 "지금 아는 실패"로 바꾸는 것(M1_PLAN_C.md §4.4)뿐이다.
 *
 * 자동 테스트는 이 파일의 순수 포맷 함수([VerifyResult.label])만 겨눈다 — 실호출 자체는
 * K-01/K-03류와 마찬가지로 네트워크가 필요해 JVM 테스트 대상이 아니다(CLAUDE.md §2 "테스트는
 * 네트워크 금지"). 실효 확인은 스모크(사용자 수행)가 담당한다(M1_PLAN_C.md §4.4).
 */
internal object CredentialVerification {
    suspend fun verifyFred(apiKey: String): VerifyResult {
        if (apiKey.isBlank()) return VerifyResult.Failure("키가 비어 있습니다")
        return withContext(Dispatchers.IO) {
            val collector = FredObservationsCollector(credentials = { apiKey }, retryPolicy = SINGLE_ATTEMPT)
            when (val result = collector.fetchObservations(VERIFY_SERIES, limit = 1)) {
                is CollectorResult.Success -> VerifyResult.Success
                is CollectorResult.Failed -> VerifyResult.Failure(result.message)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // ad hoc UI action -- a crash here must become an inline error, not a crash.
    suspend fun verifyKrx(
        id: String,
        password: String,
    ): VerifyResult {
        if (id.isBlank() || password.isBlank()) return VerifyResult.Failure("ID/비밀번호가 비어 있습니다")
        return withContext(Dispatchers.IO) {
            try {
                if (KrxClient().login(id, password)) VerifyResult.Success else VerifyResult.Failure("로그인 실패")
            } catch (e: Exception) {
                VerifyResult.Failure(e.message ?: (e::class.simpleName ?: "알 수 없는 오류"))
            }
        }
    }
}

internal sealed interface VerifyResult {
    data object Success : VerifyResult

    data class Failure(val reason: String) : VerifyResult
}

internal fun VerifyResult.label(): String =
    when (this) {
        is VerifyResult.Success -> "확인됨"
        is VerifyResult.Failure -> "실패: $reason"
    }
