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
        // sherpa-onnx publishes its Android AAR only through JitPack, not Maven
        // Central. Restricted to that one group so nothing else resolves here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.k2-fsa") }
        }
    }
}

rootProject.name = "sivrad"

include(":app")
include(":core:audio")
include(":core:stt")
include(":core:llm")
include(":core:tools")
