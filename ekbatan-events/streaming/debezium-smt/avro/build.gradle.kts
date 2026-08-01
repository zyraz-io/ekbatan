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

// The real published ActionEvent schema. The tests encode and then decode against it rather than
// a hand-built stand-in, so a change to ActionEvent.avsc that outruns the column binding table
// fails here instead of in production.
val actionEventSchemaFile: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    actionEventSchemaFile(
        project(
            mapOf(
                "path" to ":ekbatan-events-streaming-action-event-avro",
                "configuration" to "actionEventSchema",
            ),
        ),
    )

    // Provided by the Kafka Connect worker at runtime
    compileOnly("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    compileOnly("org.apache.kafka:connect-transforms:${project.property("kafkaClientsVersion")}")

    // Shared Debezium value conversions, bundled into the fat JAR by Shadow.
    implementation(project(":ekbatan-events-streaming-debezium-smt-common"))

    implementation("org.apache.avro:avro:${project.property("avroVersion")}")

    testImplementation("org.apache.kafka:connect-api:${project.property("kafkaClientsVersion")}")
    testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")
}

tasks.withType<Test> {
    useJUnitPlatform()
    inputs.files(actionEventSchemaFile)
    doFirst {
        systemProperty("ekbatan.actionEventSchema", actionEventSchemaFile.singleFile.absolutePath)
    }
}

// Shadow produces the fat JAR (all runtime deps bundled) that Kafka Connect loads as a plugin.
tasks.shadowJar {
    archiveBaseName.set("ekbatan-debezium-smt-avro")
    archiveClassifier.set("") // primary artifact (no "-all" suffix)
    mergeServiceFiles() // concatenate META-INF/services/* from all deps so ServiceLoader still finds everything
    // Relocate bundled libs into a unique package so our plugin's classloader can never clash
    // with Kafka Connect's own copies of Jackson/Avro/etc.
    relocate("com.fasterxml.jackson", "io.ekbatan.shaded.jackson")
    relocate("org.apache.avro", "io.ekbatan.shaded.avro")
}

// The thin jar has no standalone use — Connect needs the fat jar.
tasks.jar {
    enabled = false
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

// Make consumers of this project (via `project(":ekbatan-events-streaming-debezium-smt-avro")`) receive the
// shadow jar rather than the disabled thin jar.
configurations {
    runtimeElements {
        outgoing.artifacts.clear()
        outgoing.artifact(tasks.shadowJar)
    }
}
