import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    application
}

group = "com.dimdarkevil"
version = "1.0-SNAPSHOT"
description = "Command-line REST API client"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.9.3"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.2")
    implementation("com.jayway.jsonpath:json-path:2.7.0")
    implementation("org.slf4j:slf4j-simple:1.7.36")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<ProcessResources> {
    eachFile {
        if (name == "version.properties") {
            filter { line ->
                line.replace("%project.version%", "${project.version}")
            }
        }
    }
}

application {
    mainClass.set("com.dimdarkevil.glasnik.Glasnik")
    applicationName = "glasnik"
}
