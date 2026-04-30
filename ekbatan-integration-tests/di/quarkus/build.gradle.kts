plugins {
    id("java")
    id("io.quarkus") version "3.34.6"
}

group = "io.ekbatan.test"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

// The runtime jar's META-INF/quarkus-extension.properties points at the Maven coordinate
// io.ekbatan:ekbatan-di-quarkus-deployment:0.0.1-SNAPSHOT. The actual artifact lives at the
// in-build subproject :ekbatan-di:quarkus:deployment whose implicit publication name is
// "deployment" — those don't match, so Gradle's automatic project substitution can't bridge
// them. Map them explicitly. With this rule, the Quarkus Gradle plugin's specialized deployment
// classpath resolves the GAV to the local project jar and never reaches a Maven repo, keeping
// the build hermetic (no mavenLocal publish step, no ~/.m2 pollution).
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.ekbatan:ekbatan-di-quarkus-runtime"))
            .using(project(":ekbatan-di:quarkus:runtime"))
        substitute(module("io.ekbatan:ekbatan-di-quarkus-deployment"))
            .using(project(":ekbatan-di:quarkus:deployment"))
    }
}

dependencies {
    // Quarkus BOM aligns transitive Quarkus + SmallRye Config + Arc versions across the test app.
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:${project.property("quarkusVersion")}"))

    // Widget domain + jOOQ-generated tables + Flyway migrations.
    //
    // We exclude every Ekbatan + transitively-shared dep from `:di:shared`'s graph so the test
    // app's runtime classpath gets each of those jars from EXACTLY ONE path: the Quarkus
    // extension's `api()` graph below. Without these excludes, Gradle resolves the same jar via
    // two paths (shared AND extension), Quarkus places one copy on QuarkusClassLoader's local
    // set + one on the parent classloader, and you get `ClassCastException` at `@All` injection
    // points — with a long `parentFirstArtifacts` list as the only escape hatch.
    //
    // Spring Boot and Micronaut consumers don't have a split classloader, so they inherit
    // `:di:shared`'s full transitive graph as-is — these excludes are scoped to the Quarkus
    // consumer only.
    implementation(project(":ekbatan-integration-tests:di:shared")) {
        exclude(group = "io.ekbatan.core", module = "ekbatan-core")
        exclude(group = "io.ekbatan", module = "bootstrap")
        exclude(group = "io.ekbatan", module = "annotations")
        exclude(group = "io.ekbatan.events.localeventhandler", module = "local-event-handler")
        exclude(group = "io.ekbatan.distributedjobs", module = "ekbatan-distributed-jobs")
    }
    // All Ekbatan internal modules + jOOQ + Jackson + JDBC etc. come transitively via this
    // jar's `api()` graph (the extension is the single source of truth for those deps; the
    // `:di:shared` excludes above prevent the user-side path from re-introducing them).
    implementation(project(":ekbatan-di:quarkus:runtime"))

    // Test
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation(testFixtures(project(":ekbatan-core")))
    testImplementation("io.quarkus:quarkus-test-common")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")
    testImplementation("org.testcontainers:testcontainers-postgresql:${project.property("testcontainersVersion")}")
    testImplementation("org.awaitility:awaitility:${project.property("awaitilityVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
