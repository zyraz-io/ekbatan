plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-core")
    description.set("Core action/repository/persister framework for Ekbatan.")
}

java.sourceCompatibility = JavaVersion.VERSION_25
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {

    // JOOQ dependencies
    api("org.jooq:jooq:${project.property("jooqVersion")}")
    api("org.jooq:jooq-meta:${project.property("jooqVersion")}")
    api("org.jooq:jooq-codegen:${project.property("jooqVersion")}")

    // PostgreSQL JDBC driver — testImplementation only. ekbatan-core itself doesn't reference
    // Postgres APIs; downstream apps bring their own JDBC driver matching their dialect. Pinning
    // it as `implementation` here would force every MariaDB/MySQL consumer to also inherit
    // Postgres on their runtime classpath, which clutters the artifact graph and (more sharply)
    // trips GraalVM native-image with NoClassDefFoundError on Postgres's SSPI/JNA stack when the
    // reachability-metadata repo registers those classes against a classpath that doesn't have
    // JNA. Test fixtures still need the driver for the integration-test runners.
    testImplementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    implementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    implementation("org.apache.commons:commons-collections4:${project.property("commonsCollections4Version")}")
    implementation("com.google.guava:guava:${project.property("guavaVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("net.javacrumbs.json-unit:json-unit-assertj:${project.property("jsonUnitVersion")}")
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")
    testImplementation(project(":ekbatan-test-support"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Apache Commons Lang3
    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")

    // Jackson for JSON serialization
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")

    // SLF4J API (no-op when no backend is present)
    api("org.slf4j:slf4j-api:${project.property("slf4jVersion")}")

    // OpenTelemetry API (no-op when no SDK is present)
    api("io.opentelemetry:opentelemetry-api:${project.property("opentelemetryVersion")}")

    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:${project.property("opentelemetryVersion")}")

    // YAML support for ShardingConfig deserialization sanity-check tests
    testImplementation("tools.jackson.dataformat:jackson-dataformat-yaml:${project.property("jacksonDatabindVersion")}")

    // GraalVM SVM API for the @TargetClass / @Substitute fix to jOOQ's Internal.arrayType.
    // compileOnly because the API is provided by native-image at AOT time and is never on
    // the JVM runtime classpath; the substitution class is invisible to JVM execution.
    compileOnly("org.graalvm.nativeimage:svm:25.0.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Tracing tests require a separate JVM fork because the OTel SDK must be registered
// before any instrumented class loads its static Tracer field via GlobalOpenTelemetry.
tasks.register<Test>("tracingTest") {
    useJUnitPlatform {
        includeTags("tracing")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("tracing")
    }
    finalizedBy("tracingTest")
}
