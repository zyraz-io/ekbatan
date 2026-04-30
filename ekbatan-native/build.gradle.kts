plugins {
    `java-library`
    `maven-publish`
}

group = "io.ekbatan.native"
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
    // GraalVM hosted API (Feature, RuntimeReflection) — provided by native-image at build
    // time, never on the JVM runtime classpath.
    compileOnly("org.graalvm.nativeimage:svm:25.0.2")

    // ClassGraph powers the build-time classpath scan inside Jackson3RecordsFeature.
    // It must reach the native-image build classpath, so it ships transitively.
    api("io.github.classgraph:classgraph:4.8.181")

    // Flyway is needed for the NativeImageFlywayResourceProvider / FlywayHelper to
    // compile against. Users who do not use Flyway never wire those classes; the
    // analyser drops them. Exposed as `api` so consumers depending on this module
    // see Flyway transitively when they call FlywayHelper.
    api("org.flywaydb:flyway-core:${project.property("flywayVersion")}")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
