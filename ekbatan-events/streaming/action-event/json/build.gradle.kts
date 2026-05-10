plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-action-event-json")
    description.set("JSON wire-format ActionEvent (Kafka consumer-side deserialization for Ekbatan outbox).")
}

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
