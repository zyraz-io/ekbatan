plugins {
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

// The real published ActionEvent descriptor. The tests encode and then decode against it rather
// than a hand-built stand-in, so a change to ActionEvent.proto that outruns the column binding
// table fails here instead of in production.
val actionEventDescriptorFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    actionEventDescriptorFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-protobuf",
                "configuration" to "actionEventDescriptor",
            ),
        ),
    )

    // Provided by the Kafka Connect worker at runtime
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    compileOnly("org.apache.kafka:connect-transforms:${project.property("kafkaClientsVersion")}")

    // Shared Debezium value conversions, bundled into the fat JAR by Shadow.
    implementation(project(":ekbatan-events-streaming-debezium-smt-common"))

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
    inputs.files(actionEventDescriptorFile)
    doFirst {
        systemProperty("ekbatan.actionEventDescriptor", actionEventDescriptorFile.singleFile.absolutePath)
    }
}

tasks.shadowJar {
    archiveBaseName.set("ekbatan-debezium-smt-protobuf")
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
