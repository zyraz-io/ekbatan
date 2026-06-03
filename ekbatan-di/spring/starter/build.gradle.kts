plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-spring-boot-starter")
    description.set("Spring Boot starter for Ekbatan — single-dep entry point for Spring Boot apps.")
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
    api(project(":ekbatan-di-spring-autoconfigure"))
    api(project(":ekbatan-di-annotations"))
    api("org.springframework.boot:spring-boot-starter:${project.property("springBootVersion")}")

    // ekbatan-core declares HikariCP as compileOnly so its public API stays Hikari-free; the
    // starter declares Hikari as implementation so Spring Boot consumers using this starter
    // get a working framework out of the box without an extra dep line - including the
    // documented EkbatanShardFlywayDataSource pattern that imports HikariDataSource directly
    // (consumer compile classpath, not just runtime).
    implementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
}
