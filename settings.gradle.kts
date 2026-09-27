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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's repository is asked only for Google's own groups, and every
        // other group comes from Maven Central alone. Google's groups can
        // still fall through to Maven Central, which hosts some of them too
        // (zxing, for one). What pins each artifact, whichever repository
        // serves it, is its checksum in gradle/verification-metadata.xml.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "BTCPayApp"
include(":app")
