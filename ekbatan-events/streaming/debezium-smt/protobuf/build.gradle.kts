plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "io.ekbatan.events.streaming.debeziumsmt"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    // Provided by the Kafka Connect worker at runtime
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    compileOnly("org.apache.kafka:connect-transforms:${project.property("kafkaClientsVersion")}")

    implementation("com.google.protobuf:protobuf-java:${project.property("protobufVersion")}")
    implementation("com.google.protobuf:protobuf-java-util:${project.property("protobufVersion")}")

    testImplementation("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.google.protobuf", "io.ekbatan.shaded.protobuf")
    relocate("com.google.gson", "io.ekbatan.shaded.gson")
}

tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

configurations {
    runtimeElements {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}
