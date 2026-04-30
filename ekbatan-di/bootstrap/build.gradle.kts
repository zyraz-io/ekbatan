plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    api(project(":ekbatan-core"))

    // local-event-handler and distributed-jobs are used by RegistryAssembler helpers
    // but aren't required for every consumer (a project might use only Actions+Repositories).
    // compileOnly keeps them out of the transitive closure unless the consumer pulls them in.
    compileOnly(project(":ekbatan-events:local-event-handler"))
    compileOnly(project(":ekbatan-distributed-jobs"))

    // Jackson is already api'd by ekbatan-core; mention it for clarity.
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")

    testImplementation(project(":ekbatan-events:local-event-handler"))
    testImplementation(project(":ekbatan-distributed-jobs"))
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
}
