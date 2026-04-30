plugins {
    `java-library`
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
    api(project(":ekbatan-di:bootstrap"))
    api(project(":ekbatan-di:annotations"))
    api(project(":ekbatan-events:local-event-handler"))
    api(project(":ekbatan-distributed-jobs"))

    compileOnly("io.micronaut:micronaut-inject:${project.property("micronautVersion")}")
    compileOnly("io.micronaut:micronaut-context:${project.property("micronautVersion")}")
    compileOnly("io.micronaut:micronaut-runtime:${project.property("micronautVersion")}")
    // TypeElementVisitor / VisitorContext live in core-processor (the compile-time AP SPI).
    compileOnly("io.micronaut:micronaut-core-processor:${project.property("micronautVersion")}")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("jakarta.annotation:jakarta.annotation-api:3.0.0")

    // Pre-generates BeanDefinition classes for our @Factory beans at this module's compile time.
    annotationProcessor("io.micronaut:micronaut-inject-java:${project.property("micronautVersion")}")
}
