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

// The plugin jar is loaded BY a Kotlin compile daemon, so its bytecode level must
// be <= the daemon's JDK. lattice-runtime's Android compilations run on JDK 17
// (jvmTarget = 17), and without this the daemon rejects the jar with
// UnsupportedClassVersionError — which surfaces only on the JVM/Android path,
// never on native.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
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
