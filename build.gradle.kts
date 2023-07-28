import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(platform("com.squareup.okhttp3:okhttp-bom:4.9.3"))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.4")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.4")
    implementation("com.jayway.jsonpath:json-path:2.7.0")
    implementation("org.slf4j:slf4j-simple:2.0.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
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
