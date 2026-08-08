pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.opencollab.dev/maven-releases")
        maven("https://repo.opencollab.dev/maven-snapshots")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "endlink"

// The Bedrock codecs, vendored under protocol/ (which in turn includes network/ beside it). They are
// a fork of Kas-tle/Protocol carrying the hand-written v944, v975, v1001 and v2168 codecs this proxy
// depends on, so they live here rather than being fetched: without them there is no protocol support
// to build. Apache 2.0, see protocol/LICENSE and NOTICE.
includeBuild("protocol")

// Addons live in sibling directories and compile against the proxy's plugin API. They are included
// only when actually present: the proxy does not depend on any of them, and Gradle fails outright
// rather than skipping a project whose directory does not exist — which is what made a clone of this
// repository alone unbuildable.
for (addon in listOf("viaendlink")) {
    if (file("../$addon").isDirectory) {
        include(addon)
        project(":$addon").projectDir = file("../$addon")
    }
}
