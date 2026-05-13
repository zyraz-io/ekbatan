plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-spring-boot-autoconfigure")
    description.set("Spring Boot auto-configuration for Ekbatan.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di-annotations"))

    // Bundled by default — matches Quarkus and Micronaut so all three DI flavors give the same
    // out-of-box experience. The @ConditionalOnClass / @ConditionalOnBean gates in the auto-
    // config classes remain as defensive guards for users who manually exclude these modules.
    api(project(":ekbatan-events-local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    api("org.springframework.boot:spring-boot-autoconfigure:${project.property("springBootVersion")}")
    api("org.springframework.boot:spring-boot:${project.property("springBootVersion")}")
    implementation("org.springframework.boot:spring-boot-jackson:${project.property("springBootVersion")}")

    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")

    // Generates META-INF/spring-configuration-metadata.json so IDEs offer autocomplete +
    // hover docs + typo validation for ekbatan.* keys.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${project.property("springBootVersion")}")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:${project.property("springBootVersion")}")

    testImplementation("org.springframework.boot:spring-boot-test:${project.property("springBootVersion")}")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("org.springframework.boot:spring-boot-jackson:${project.property("springBootVersion")}")

    // EkbatanDistributedJobsConfigurationTest builds a real Hikari pool with a bogus postgresql
    // URL to drive the @ConditionalOnBean wiring path; Hikari calls DriverManager.getDriver(...)
    // during pool init and fails fast if the driver class isn't on the test classpath.
    testImplementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
}
