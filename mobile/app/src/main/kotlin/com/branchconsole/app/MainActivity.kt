package com.branchconsole.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.branchconsole.app.home.HomeScreen
import com.branchconsole.app.home.RunHistoryScreen
import com.branchconsole.app.onboarding.SettingsScreen

/**
 * MT1-08b — 기능판 홈 3화면(홈/이력/설정, M1_PLAN_C.md §4.3 "단일 스크롤 화면 1개 + 이력 화면
 * 1개 + 설정 화면 1개"). 별도 Navigation-Compose 의존성을 추가하지 않는다 — 화면 3개짜리
 * 단일 Activity에는 `remember { mutableStateOf(...) }` 전환으로 충분하다(M2에서 화면이 늘면
 * 재검토).
 */
private enum class AppScreen(val label: String) {
    HOME("홈"),
    HISTORY("이력"),
    SETTINGS("설정"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var screen by remember { mutableStateOf(AppScreen.HOME) }
                    // MT1-08b 결함 수정 — compileSdk/targetSdk 36은 edge-to-edge를 강제해 탭 행이
                    // 상태바 뒤에 그려지고 y<=100 터치가 앱에 도달하지 않았다(실기기 SM-F966N
                    // 실측). safeDrawingPadding으로 상태바·내비바·컷아웃·IME를 한 번에 해소한다
                    // (safeDrawing = systemBars ∪ ime ∪ displayCutout — 키보드 회피도 이 지점이
                    // 담당한다: 자손 화면에서 별도로 imePadding을 붙여도 이미 소비된 0dp라 무효).
                    // 단일 Activity라 이 한 지점이 홈/이력/설정 3화면 전부를 덮는다.
                    // 검증 범위: M1 인셋 실증은 SM-F966N(Android 16) 실기기 한정. minSdk 29~34
                    // (edge-to-edge 비강제) 구간은 인셋이 0으로 디스패치되어 무해할 것으로
                    // 보이나 미실측이며(aaa C-1), 그 구간의 실기기 실증은 M3 소크 소관이다.
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        Row {
                            AppScreen.entries.forEach { candidate ->
                                TextButton(
                                    onClick = { screen = candidate },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(if (screen == candidate) "> ${candidate.label}" else candidate.label)
                                }
                            }
                        }
                        when (screen) {
                            AppScreen.HOME -> HomeScreen()
                            AppScreen.HISTORY -> RunHistoryScreen()
                            AppScreen.SETTINGS -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }
}
