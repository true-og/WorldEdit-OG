import japicmp.accept.AcceptingSetupRule
import japicmp.accept.BinaryCompatRule
import me.champeau.gradle.japicmp.JapicmpTask
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.*

plugins {
    base
    id("me.champeau.gradle.japicmp")
}

repositories {
    maven {
        name = "EngineHub Repository (Releases Only)"
        url = URI.create("https://maven.enginehub.org/artifactory/libs-release-local/")
        mavenContent {
            releasesOnly()
        }
    }
    maven {
        name = "EngineHub Repository (External Releases Only)"
        url = URI.create("https://maven.enginehub.org/artifactory/ext-release-local/")
        mavenContent {
            releasesOnly()
            includeGroupAndSubgroups("com.sk89q.lib")
        }
    }
    maven {
        name = "EngineHub Repository (Snapshots Only)"
        url = URI.create("https://maven.enginehub.org/artifactory/libs-snapshot-local/")
        mavenContent {
            snapshotsOnly()
            includeGroupAndSubgroups("org.enginehub.lin-bus")
        }
    }
    maven {
        name = "EngineHub Repository (PaperMC Proxy)"
        url = URI.create("https://maven.enginehub.org/artifactory/papermc-proxy-cache/")
        mavenContent {
            includeGroupAndSubgroups("io.papermc")
            includeGroupAndSubgroups("net.md-5")
        }
    }
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
        content {
            includeGroupAndSubgroups("net.fabricmc")
        }
    }
    maven {
        name = "EngineHub Repository (Snapshots Only)"
        url = uri("https://maven.enginehub.org/artifactory/libs-snapshot-local/")
        content {
            excludeGroup("com.sk89q.worldedit")
        }
    }
    mavenCentral()
}

val resetAcceptedApiChangesFiles by tasks.registering {
    group = "API Compatibility"
    description = "Resets ALL the accepted API changes files"
}

val checkApiCompatibility by tasks.registering {
    group = "API Compatibility"
    description = "Checks ALL API compatibility"
}

tasks.check {
    dependsOn(checkApiCompatibility)
}

// Generic setup for all tasks
// Pull the version before our current version.
val baseVersion = "(,${rootProject.version.toString().substringBefore("-SNAPSHOT")}["
for (projectFragment in listOf("bukkit", "cli", "core", "fabric", "forge", "sponge")) {
    val capitalizedFragment =
        projectFragment.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val proj = project(":worldedit-$projectFragment")
    evaluationDependsOn(proj.path)

    val changeFile = project.file("src/changes/accepted-$projectFragment-public-api-changes.json").toPath()

    val resetChangeFileTask = tasks.register("reset${capitalizedFragment}AcceptedApiChangesFile") {
        group = "API Compatibility"
        description = "Reset the accepted API changes file for $projectFragment"

        doFirst {
            Files.newBufferedWriter(changeFile, StandardCharsets.UTF_8).use {
                it.write("{\n}")
            }
        }
    }
    resetAcceptedApiChangesFiles {
        dependsOn(resetChangeFileTask)
    }

    val baseConf = configurations.dependencyScope("${projectFragment}OldJar") {
    }
    val apiConf = configurations.resolvable("${projectFragment}OldJarApi") {
        extendsFrom(baseConf.get())
        attributes {
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM)
            )
            attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_API)
            )
        }
    }
    val runtimeConf = configurations.resolvable("${projectFragment}OldJarRuntime") {
        extendsFrom(baseConf.get())
        attributes {
            attribute(
                TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                objects.named(TargetJvmEnvironment.STANDARD_JVM)
            )
            attribute(
                Usage.USAGE_ATTRIBUTE,
                objects.named(Usage.JAVA_RUNTIME)
            )
        }
    }
    val projPublication = proj.the<PublishingExtension>().publications.getByName<MavenPublication>("maven")
    baseConf.configure {
        dependencies.add(
            project.dependencies.create("${projPublication.groupId}:${projPublication.artifactId}:$baseVersion")
        )
        // Temporarily necessary until Mojang updates their Guava
        dependencyConstraints.add(
            project.dependencies.constraints.create("com.google.guava:guava:${Versions.GUAVA}!!").apply {
                because("Mojang provides Guava")
            }
        )
    }
    val checkApi = tasks.register<JapicmpTask>("check${capitalizedFragment}ApiCompatibility") {
        group = "API Compatibility"
        description = "Check API compatibility for $capitalizedFragment API"
        richReport {
            addSetupRule(
                AcceptingSetupRule::class.java, AcceptingSetupRule.createParams(
                    changeFile,
                )
            )
            addRule(BinaryCompatRule::class.java)
            reportName.set("api-compatibility-$projectFragment.html")
        }

        oldClasspath.from(apiConf, runtimeConf)
        newClasspath.from(
            proj.configurations.named("compileClasspath").get().incoming.artifactView {
                attributes {
                    attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
                }
            }.files,
            proj.tasks.named(
                when (projectFragment) {
                    "fabric" -> "remapJar"
                    "forge" -> "reobfJar"
                    else -> "jar"
                }
            )
        )
        onlyModified.set(false)
        failOnModification.set(false) // report does the failing (so we can accept)

        // Internals are not API
        packageExcludes.add("com.sk89q.worldedit*.internal*")
        // Mixins are not API
        packageExcludes.add("com.sk89q.worldedit*.mixin*")
        // Experimental is not API
        packageExcludes.add("com.sk89q.worldedit*.experimental*")
    }

    checkApiCompatibility {
        dependsOn(checkApi)
    }
}

// Specific project overrides
tasks.named<JapicmpTask>("checkCoreApiCompatibility") {
    // Commands are not API
    packageExcludes.add("com.sk89q.worldedit.command*")
}

dependencies {
    "bukkitOldJar"("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
}
tasks.named<JapicmpTask>("checkBukkitApiCompatibility") {
    // Internal Adapters are not API
    packageExcludes.add("com.sk89q.worldedit.bukkit.adapter*")
}

tasks.named<JapicmpTask>("checkSpongeApiCompatibility") {
    // POM is broken
    ignoreMissingClasses.set(true)
}
