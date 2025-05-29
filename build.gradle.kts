import org.ajoberstar.grgit.Grgit
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.*
import org.gradle.testing.jacoco.tasks.JacocoReport

// needed for fabric to know where FF executor is....
buildscript {
    repositories {
        mavenCentral()
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
    }
    dependencies {
        classpath("net.fabricmc:fabric-loom:${versions.loom}")
    }
}
plugins {
    id("org.enginehub.codecov")
    jacoco
}

// Work around https://github.com/gradle/gradle/issues/4823
subprojects {
    // `lowercase()` replaces deprecated `toLowerCase()`
    if (buildscript.sourceFile?.extension?.lowercase() == "kts" && parent != rootProject) {
        generateSequence(parent) { it.parent.takeIf { p -> p != rootProject } }
            .forEach { evaluationDependsOn(it.path) }
    }
}

logger.lifecycle(
    """
    *******************************************
     You are building WorldEdit!

     If you encounter trouble:
       1) Read COMPILING.md if you haven't yet
       2) Try running 'build' in a separate Gradle run
       3) Use ./gradlew (not the system gradle)
       4) Need help? Discord → https://discord.gg/enginehub

     Output jars land in  [sub-project]/build/libs
    *******************************************
    """.trimIndent()
)

applyCommonConfiguration()
applyRootArtifactoryConfig()

val totalReport = tasks.register<JacocoReport>("jacocoTotalReport") {
    subprojects.forEach { proj ->
        proj.apply(plugin = "jacoco")
        proj.plugins.withId("java") {
            val buildDirFile = proj.layout.buildDirectory.asFile.get()
            // collect *.exec files under “…/jacoco/”
            executionData(
                proj.fileTree(buildDirFile).include("**/jacoco/*.exec")
            )

            // replace deprecated JavaPluginConvention with JavaPluginExtension
            val mainSourceSet = proj.extensions
                .getByType<JavaPluginExtension>()
                .sourceSets
                .getByName("main")

            sourceSets(mainSourceSet)
            // new report API (required / outputLocation)
            reports {
                xml.required.set(true)
                xml.outputLocation.set(
                    rootProject.layout.buildDirectory.file("reports/jacoco/report.xml")
                )
                html.required.set(true) // everything goes in build/reports/jacoco by default
            }
        }
    }
}
afterEvaluate {
    totalReport.configure {
        classDirectories.setFrom(
            classDirectories.files.map { fileTree(it) {
                exclude("**/*AutoValue_*", "**/*Registration.*")
            }}
        )
    }
}

codecov {
    reportTask.set(totalReport)
}
