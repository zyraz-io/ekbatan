plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-test-support")
    description.set("Test support utilities for Ekbatan applications.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    api(project(":ekbatan-core"))
    api("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
}
