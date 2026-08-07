package com.branchconsole.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/**
 * MT1-01a 스캐폴드 placeholder. 실제 기능판 홈(상태 7종)은 MT1-08에서 구현된다
 * (docs/plans/M1_PLAN_FINAL.md §3 W5). 여기서는 Compose 컴파일러·Material3·activity-compose
 * 의존성 배선이 실제로 컴파일·패키징되는지만 증명한다.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text(text = AppInfo.MODULE_NAME)
                }
            }
        }
    }
}
