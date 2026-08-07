package com.branchconsole.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MT1-01a 계측 소스셋 배선 확인용 스모크. 실기기·에뮬레이터가 있어야 실행되므로
 * 이 세션(Windows 개발 머신, 브리프 §3.2)에서는 실행하지 않는다 — 배선(컴파일 가능성)만 증명한다.
 * `./gradlew connectedDebugAndroidTest`로 실기기에서 실행.
 */
@RunWith(AndroidJUnit4::class)
class AppPackageInstrumentedTest {
    @Test
    fun applicationIdMatchesTargetContext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.branchconsole.app", context.packageName)
    }
}
