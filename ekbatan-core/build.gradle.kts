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

    // Apache Commons Lang3
    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")

    // Jackson for JSON serialization
    api("tools.jackson.core:jackson-databind:${project.property("jacksonDatabindVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
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
