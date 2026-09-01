pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // LightHouse SDK — private maven; auth via conduit.user / conduit.password
        // gradle properties (see gradle.properties).
        maven {
            url = uri("https://maven.kpeworld.com/releases")
            credentials {
                username = providers.gradleProperty("conduit.user").orElse("").get()
                password = providers.gradleProperty("conduit.password").orElse("").get()
            }
        }
    }
}

rootProject.name = "Numify CallerID Lookup"
include(":app")
 