import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    id("com.vanniktech.maven.publish")
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
    compileOnly(kotlin("gradle-plugin"))

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val generatedVersionDir = layout.buildDirectory.dir("generated/sources/version/kotlin")

val generateLatticeVersion = tasks.register("generateLatticeVersion") {
    val pkgVersion = project.version.toString()
    val outDir = generatedVersionDir
    inputs.property("version", pkgVersion)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/lattice/gradle/LatticeVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            package com.lattice.gradle

            object LatticeVersion {
                const val VERSION = "$pkgVersion"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateLatticeVersion)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("lattice") {
            id = "io.github.jsflax.lattice"
            implementationClass = "com.lattice.gradle.LatticeGradlePlugin"
            displayName = "Lattice ORM Plugin"
            description = "Kotlin K2 FIR/IR compiler and runtime plugin for Lattice ORM"
        }
        create("latticeLegacy") {
            id = "com.lattice"
            implementationClass = "com.lattice.gradle.LatticeGradlePlugin"
            displayName = "Lattice ORM Plugin (Legacy ID)"
            description = "Legacy plugin alias for io.github.jsflax.lattice"
        }
    }
}

val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.keyId").isPresent

mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (hasSigningKey) {
        signAllPublications()
    }
    coordinates("io.github.jsflax", "lattice-gradle-plugin", version.toString())
    pom {
        name.set("Lattice Gradle Plugin")
        description.set("Gradle plugin for Lattice Kotlin Multiplatform & Android ORM.")
        url.set("https://github.com/jsflax/LatticeKotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("jsflax")
                name.set("Jason Flax")
                url.set("https://github.com/jsflax")
            }
        }
        scm {
            url.set("https://github.com/jsflax/LatticeKotlin")
            connection.set("scm:git:git://github.com/jsflax/LatticeKotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/jsflax/LatticeKotlin.git")
        }
    }
}
