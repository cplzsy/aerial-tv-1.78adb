pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        // Kadb 2.1.1 pulls com.github.Flyfish233:spake2-java (its SPAKE2 pairing handshake), which
        // is published only on JitPack. skyadb declares the same repository.
        maven("https://jitpack.io")
    }
}
rootProject.name = "Aerial"
include(":app")
include(":lite")
