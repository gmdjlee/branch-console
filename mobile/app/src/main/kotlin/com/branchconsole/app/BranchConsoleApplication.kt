package com.branchconsole.app

import android.app.Application
import android.util.Log
import com.branchconsole.app.tick.ConfirmTickWorker

private const val TAG = "BranchConsoleApp"

/**
 * MT1-06 프로덕션 진입점 (aaa F-1 해소) — 이전 판은 `ConfirmTickWorker.schedulePeriodic`/
 * `triggerCatchupNow`를 호출하는 곳이 앱 어디에도 없어 확정 틱 파이프라인이 실기기에서
 * 영원히 실행되지 않았다.
 *
 * `runCatching`으로 감싸는 이유: `confirm_time_kst`가 SSOT에 아직 없으면(AD-3b 실측 대기,
 * MT1-00g) [ConfirmTickWorker.schedulePeriodic]이 **명시적으로** 예외를 던진다(조용한 17:00
 * 기본값 금지, CLAUDE.md §1). 그 실패로 **앱 전체가 기동 실패**하면 확정 틱 기능 하나 때문에
 * 다른 모든 화면이 죽는다 — 로그로 드러내고 다음 실행(그 사이 값이 채워지면)에 다시 시도하는
 * 쪽이 올바른 절충이다. 로더 자체의 명시 실패 계약은 그대로다(여기서 삼키는 것은 "앱을 끄느냐"
 * 라는 상위 판단일 뿐, 실패 자체를 숨기지 않는다 — `Log.e`로 남는다).
 */
class BranchConsoleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            ConfirmTickWorker.schedulePeriodic(this)
            ConfirmTickWorker.triggerCatchupNow(this)
        }.onFailure { e ->
            Log.e(TAG, "confirm tick scheduling failed (will retry on next app launch)", e)
        }
    }
}
