plugins {
    `java-library`
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

group = "io.ekbatan.streaming.actionevent.avro"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.apache.avro:avro:${project.property("avroVersion")}")
}

// Expose the .avsc file as a named output so consumers (e.g. the SMT integration test) can
// resolve its absolute path to mount into containers, instead of hardcoding a relative path.
val actionEventSchema: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(actionEventSchema.name, layout.projectDirectory.file("src/main/avro/ActionEvent.avsc"))
}
