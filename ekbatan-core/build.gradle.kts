plugins {
    `java-library`
    `maven-publish`
}

group = "io.ekbatan.core"
version = "0.0.1-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_25

repositories {
    mavenCentral()
}

dependencies {

    // JOOQ dependencies
    api("org.jooq:jooq:3.18.0")
    api("org.jooq:jooq-meta:3.18.0")
    api("org.jooq:jooq-codegen:3.18.0")

    // PostgreSQL JDBC driver
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:7.0.2")

    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Apache Commons Lang3
    implementation("org.apache.commons:commons-lang3:3.18.0")

    // Jackson for JSON serialization
    api("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.2")

    // Apache Commons Lang3 for validation and other utilities
    api("org.apache.commons:commons-lang3:3.19.0")
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
