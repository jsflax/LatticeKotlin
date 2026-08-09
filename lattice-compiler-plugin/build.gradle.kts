import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    kotlin("kapt")
    id("com.vanniktech.maven.publish")
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable")

    kapt("com.google.auto.service:auto-service:1.1.1")
    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    testImplementation("com.github.tschuchortdev:kotlin-compile-testing:1.5.0")
}

tasks.test {
    useJUnitPlatform()
}

// The Kotlin daemon that compiles the Android source sets runs on JDK 17 and
// has to be able to load this jar (the runtime's test compilations apply it
// via -Xplugin). Target 17 rather than whatever JDK Gradle happens to run on,
// which otherwise produces class files the daemon rejects.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("io.github.jsflax", "lattice-compiler-plugin", version.toString())
    pom {
        name.set("Lattice Compiler Plugin")
        description.set("K2 compiler plugin (FIR/IR) for Lattice @Model transforms; apply via kotlinCompilerPluginClasspath.")
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
