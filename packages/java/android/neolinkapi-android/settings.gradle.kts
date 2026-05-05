pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        if (providers.gradleProperty("useMavenLocal").map(String::toBoolean).orElse(false).get()) {
            mavenLocal()
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (providers.gradleProperty("useMavenLocal").map(String::toBoolean).orElse(false).get()) {
            mavenLocal()
        }
    }
}

rootProject.name = "neolinkapi-android"
