plugins {
    `java-library`
}

group = "io.ekbatan.streaming.actionevent.json"
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
    // Jackson for ObjectNode — the only dependency
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")
}
