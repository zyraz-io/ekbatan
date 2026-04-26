import com.google.protobuf.gradle.id

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

group = "io.ekbatan.events.streaming.actionevent.protobuf"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.protobuf:protobuf-java:${project.property("protobufVersion")}")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${project.property("protobufVersion")}"
    }
    generateProtoTasks {
        all().configureEach {
            // Also emit a FileDescriptorSet (.desc) we can expose for runtime use.
            generateDescriptorSet = true
            descriptorSetOptions.includeImports = true
        }
    }
}

// Expose the generated descriptor file so consumers (SMT, integration tests) can mount it.
val actionEventDescriptor: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

val descriptorFile =
    layout.buildDirectory.file("generated/source/proto/main/descriptor_set.desc")

artifacts {
    add(actionEventDescriptor.name, descriptorFile) {
        builtBy(tasks.named("generateProto"))
    }
}
