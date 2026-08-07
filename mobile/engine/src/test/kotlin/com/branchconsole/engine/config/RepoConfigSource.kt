package com.branchconsole.engine.config

import java.io.File
import java.io.InputStream

/**
 * 저장소 원본 `configs` 디렉터리의 YAML 파일을 여는 테스트 전용 [ConfigSource] — 앱은 `AssetManager`로
 * 같은 인터페이스를 구현한다(:app, MT1-01b). SnapshotContractsTest.kt와 동일한 방식으로
 * JVM 테스트 작업 디렉토리에서 위로 걸어 올라가며 `configs/` 디렉토리를 찾는다(Gradle의
 * 정확한 작업 디렉토리 규약에 의존하지 않기 위해).
 */
object RepoConfigSource : ConfigSource {
    private const val MAX_PARENT_HOPS = 8

    private fun findConfigsDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.let { File(it, "configs") }
            if (candidate != null && candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        error("configs/ not found by walking up from ${System.getProperty("user.dir")}")
    }

    private val configsDir: File by lazy { findConfigsDir() }

    override fun open(name: String): InputStream = File(configsDir, name).inputStream()
}
