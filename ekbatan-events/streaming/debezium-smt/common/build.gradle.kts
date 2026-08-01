plugins {
    id("java")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// Deliberately not published. This module exists so the Avro and protobuf SMTs share one copy of
// the Debezium value conversions instead of two divergent ones; its classes are bundled into both
// fat JARs by Shadow. Consumers get the SMT plugin JARs, never this.
dependencies {
    // Provided by the Kafka Connect worker at runtime, so it is never bundled.
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")

    testImplementation("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
