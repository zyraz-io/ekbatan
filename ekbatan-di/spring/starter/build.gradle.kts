plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

/*
 * Thin transitive-dep collector. End users add ONLY this module; Gradle pulls in
 * autoconfigure + the di-annotations + Spring Boot's standard starter via this graph.
 *
 * No source — convention, not code.
 */
dependencies {
    api(project(":ekbatan-di:spring:autoconfigure"))
    api(project(":ekbatan-di:annotations"))
    api("org.springframework.boot:spring-boot-starter:${project.property("springBootVersion")}")
}
