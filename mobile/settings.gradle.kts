@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // AD-A9 (docs/plans/M1_PLAN_A.md §2.2): 저장소 잠금 — module build files may not declare
    // their own repositories, and mavenLocal() is never used (non-reproducible builds).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // gradle/libs.versions.toml is auto-wired by Gradle's default convention (name "libs" +
    // conventional path) — no explicit versionCatalogs{} block needed.
}

rootProject.name = "branch-console-mobile"

include(":engine")
include(":krx")
include(":app")
