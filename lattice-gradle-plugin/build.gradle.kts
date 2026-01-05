plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish")
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
    compileOnly(kotlin("gradle-plugin"))

    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("lattice") {
            id = "com.lattice"
            implementationClass = "com.lattice.gradle.LatticeGradlePlugin"
            displayName = "Lattice ORM Plugin"
            description = "Kotlin compiler plugin for Lattice ORM"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "lattice-gradle-plugin"
        }
    }
}
