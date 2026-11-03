pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        // 플레오스 SDK(차 갈래만 쓴다). 로그인 없이 받는다.
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "onsupporter-ptt"
include(":app")
