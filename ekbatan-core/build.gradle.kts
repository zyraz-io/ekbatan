plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

group = "io.ekbatan.core"
version = "0.0.1-SNAPSHOT"
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

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    implementation("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
    implementation("org.apache.commons:commons-collections4:${project.property("commonsCollections4Version")}")
    implementation("com.google.guava:guava:${project.property("guavaVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("net.javacrumbs.json-unit:json-unit-assertj:${project.property("jsonUnitVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // testFixtures consumers (the integration-test modules) need access to the
    // GraalVM-native helpers — FlywayHelper, NativeImageFlywayResourceProvider, and the
    // auto-registered Jackson3RecordsFeature — which now live in :ekbatan-native (the
    // publishable module any downstream user adds when they want their app to build as
    // a native image). `testFixturesApi` so the dependency reaches the integration tests
    // transitively via `testFixtures(project(":ekbatan-core"))`.
    testFixturesApi(project(":ekbatan-native"))

    // ClasspathTransferable in testFixtures wraps a classpath resource into a
    // Testcontainers Transferable so test setups can use `withCopyToContainer(...)`
    // (works under native image) instead of `MountableFile.forClasspathResource(...)`
    // (broken under native image because the resource has no filesystem path).
    testFixturesImplementation("org.testcontainers:testcontainers:${project.property("testcontainersVersion")}")

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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
        }
    }
}
