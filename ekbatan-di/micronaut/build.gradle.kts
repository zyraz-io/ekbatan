plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-micronaut")
    description.set("Micronaut Factory + annotation-processor visitor for Ekbatan @EkbatanAction discovery.")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

// We don't apply `io.micronaut.library` — that plugin auto-applies the Micronaut AP to *this*
// module, but we want the AP to run in the *user's* module (where their @EkbatanAction classes
// live) using this jar as a service-loader source for the TypeElementVisitor.
dependencies {
    api(project(":ekbatan-core"))
    api(project(":ekbatan-di-annotations"))

    // Bundled by default — matches Spring and Quarkus so all three DI flavors give the same
    // out-of-box experience. The @Requires(classes = ...) gates in
    // EkbatanLocalEventHandlerConfiguration / EkbatanDistributedJobsConfiguration remain as
    // defensive guards for users who manually exclude these modules.
    api(project(":ekbatan-events-local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    // Used to bind ekbatan.sharding.* from Micronaut's flat (dotted-key + [idx]) PropertyResolver
    // output directly into ShardingConfig — JavaPropsMapper's default schema (dot separator,
    // [idx] array notation) is an exact match for what Micronaut emits.
    implementation("tools.jackson.dataformat:jackson-dataformat-properties:${project.property("jacksonDatabindVersion")}")

    compileOnly("io.micronaut:micronaut-inject:${project.property("micronautVersion")}")
    compileOnly("io.micronaut:micronaut-context:${project.property("micronautVersion")}")
    compileOnly("io.micronaut:micronaut-runtime:${project.property("micronautVersion")}")
    // TypeElementVisitor / VisitorContext live in core-processor (the compile-time AP SPI).
    compileOnly("io.micronaut:micronaut-core-processor:${project.property("micronautVersion")}")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")

    // Pre-generates BeanDefinition classes for our @Factory beans at this module's compile time.
    annotationProcessor("io.micronaut:micronaut-inject-java:${project.property("micronautVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    // Bootstraps a real Micronaut Environment so the binding tests exercise the same path
    // production code takes — getProperties(prefix, CAMEL_CASE) → JavaPropsMapper → ShardingConfig.
    testImplementation("io.micronaut:micronaut-context:${project.property("micronautVersion")}")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
