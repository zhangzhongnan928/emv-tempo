pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            setAllowInsecureProtocol(true)
            url = uri("https://www.jitpack.io")
        }
    }
}

rootProject.name = "emv-tempo-terminal"
include(":app")
