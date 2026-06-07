plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-flyway")
    description.set("Flyway migration utilities for single-shard and sharded Ekbatan applications.")
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
    api(project(":ekbatan-core"))
    api("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
}
