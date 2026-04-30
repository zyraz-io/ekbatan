plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di:bootstrap"))
    api(project(":ekbatan-di:annotations"))

    // Auto-config classes are @ConditionalOnClass-gated on these modules.
    compileOnly(project(":ekbatan-events:local-event-handler"))
    compileOnly(project(":ekbatan-distributed-jobs"))

    api("org.springframework.boot:spring-boot-autoconfigure:${project.property("springBootVersion")}")
    api("org.springframework.boot:spring-boot:${project.property("springBootVersion")}")
    implementation("org.springframework.boot:spring-boot-jackson:${project.property("springBootVersion")}")

    implementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")

    // Generates META-INF/spring-configuration-metadata.json so IDEs offer autocomplete +
    // hover docs + typo validation for ekbatan.* keys.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${project.property("springBootVersion")}")
    compileOnly("org.springframework.boot:spring-boot-configuration-processor:${project.property("springBootVersion")}")

    testImplementation(project(":ekbatan-events:local-event-handler"))
    testImplementation(project(":ekbatan-distributed-jobs"))
    testImplementation("org.springframework.boot:spring-boot-test:${project.property("springBootVersion")}")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("org.springframework.boot:spring-boot-jackson:${project.property("springBootVersion")}")
}
