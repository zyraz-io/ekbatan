plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.1.0"
    id("org.graalvm.buildtools.native") version "1.1.0" apply false
    checkstyle
}

// Configure Spotless for the root project
spotless {
    
    // Configure Java formatting
    java {
        target("**/*.java")
        
        // Apply Palantir Java Format
        palantirJavaFormat("2.81.0")
    }
    
    // Configure format for miscellaneous files
    format("misc") {
        target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
        
        // Define the formatting rules
        trimTrailingWhitespace()
        leadingTabsToSpaces(4) // or tabs(1)
        endWithNewline()
    }
    
}

allprojects {
    group = "io.ekbatan"
    version = "0.0.1-SNAPSHOT"
    
    apply(plugin = "checkstyle")
    
    configure<CheckstyleExtension> {
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        toolVersion = "10.12.5"  // Updated to support modern Java features
        isIgnoreFailures = false
        isShowViolations = true
        // Enable HTML reports for better diagnostics
        reportsDir = layout.buildDirectory.dir("reports/checkstyle").get().asFile
    }

    repositories {
        mavenCentral()
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    
    // Add annotation processor for non-annotation-processor modules.
    // SMT modules opt out — they're standalone Kafka Connect plugins and don't use AutoBuilder.
    val skipAnnotationProcessor = project.name == "ekbatan-annotation-processor"
            || project.path.startsWith(":ekbatan-events:streaming:debezium-smt")
    if (!skipAnnotationProcessor) {
        dependencies {
            annotationProcessor(project(":ekbatan-annotation-processor"))
            implementation(project(":ekbatan-annotation-processor"))
        }
    }
    
    // Configure Spotless for each subproject
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // Configure Kotlin Gradle scripts
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
            trimTrailingWhitespace()
            leadingTabsToSpaces(4)
            endWithNewline()
        }
        
        // Configure format for miscellaneous files
        format("misc") {
            target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
            trimTrailingWhitespace()
            leadingTabsToSpaces(4)
            endWithNewline()
        }
    }

    dependencies {

        testImplementation(platform("org.junit:junit-bom:${project.property("junitBomVersion")}"))
        testImplementation("org.junit.jupiter:junit-jupiter:${project.property("junitJupiterVersion")}")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher:${project.property("junitPlatformLauncherVersion")}")

        testImplementation("io.mockk:mockk:${project.property("mockkVersion")}")
        testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    }
    
    // Configure Java compilation. SMT modules target Java 21 (Kafka Connect runtime).
    val javaTarget = if (project.path.startsWith(":ekbatan-events:streaming:debezium-smt")) "21" else "25"
    tasks.withType<JavaCompile> {
        sourceCompatibility = javaTarget
        targetCompatibility = javaTarget
        if (javaTarget == "21") {
            options.release.set(21)
        }
        // Enable incremental compilation for better build performance
        options.isIncremental = true
        // Enable all warnings
        options.isWarnings = true
        // Enable deprecation warnings
        options.isDeprecation = true
        // Enable unchecked warnings
        options.compilerArgs.add("-Xlint:unchecked")
        // Enable deprecation warnings
        options.compilerArgs.add("-Xlint:deprecation")
    }

    
    // GraalVM Native Build Tools — gives every Java module a `nativeTest` task that compiles
    // JUnit 5 tests as a native image and executes them. JVM `clean build` is unchanged.
    //
    // Skip modules where native testing doesn't apply:
    //   - ekbatan-annotation-processor: build-time only, no runtime artifact
    //   - ekbatan-events:streaming:debezium-smt*: Java 21 (Kafka Connect runtime)
    //   - ekbatan-di:quarkus:{runtime,deployment}: Quarkus has its own native machinery
    //   - ekbatan-integration-tests:di:quarkus: validated via @QuarkusIntegrationTest /
    //     quarkusIntTest task, not the GraalVM Build Tools nativeTest path
    val skipNative = project.name == "ekbatan-annotation-processor"
            || project.path.startsWith(":ekbatan-events:streaming:debezium-smt")
            || project.path == ":ekbatan-di:quarkus:runtime"
            || project.path == ":ekbatan-di:quarkus:deployment"
            || project.path == ":ekbatan-integration-tests:di:quarkus"
    if (!skipNative) {
        apply(plugin = "org.graalvm.buildtools.native")
        // ekbatan-native is the publishable module that hosts the GraalVM-side helpers
        // every native build of an Ekbatan-using project needs (Jackson3RecordsFeature,
        // KafkaClientsFeature, TestcontainersDockerJavaFeature, AvroSpecificRecordFeature,
        // FlywayHelper, NativeImageFlywayResourceProvider). Wire it onto every module's
        // test classpath so the Features auto-load for any module's `nativeTest` — without
        // each module having to remember the dependency. The conditional guards against
        // ekbatan-native trying to depend on itself.
        if (project.path != ":ekbatan-native") {
            dependencies.add("testImplementation", project(":ekbatan-native"))
        }
        val javaToolchains = extensions.getByType<JavaToolchainService>()
        configure<org.graalvm.buildtools.gradle.dsl.GraalVMExtension> {
            // GraalVMExtension.toolchainDetection defaults to FALSE in the plugin, which
            // routes the runtime-args provider for `nativeTest` through the env-var
            // fallback (GRAALVM_HOME / JAVA_HOME) instead of the configured toolchain —
            // even though the compile task uses the toolchain (it has its own
            // BuildNativeImageTask.disableToolchainDetection convention(false)).
            // Forcing this to true makes both paths consistently use the toolchain.
            toolchainDetection.set(true)
            // Route native-image tasks to a GraalVM JDK 25 install; normal compileJava/test
            // continue using whatever JDK runs Gradle. JvmVendorSpec.GRAAL_VM matches the
            // Community Edition installer ID (GraalVM CE writes IMPLEMENTOR="GraalVM
            // Community"); for Oracle GraalVM, switch to JvmVendorSpec.ORACLE.
            binaries.all {
                javaLauncher.set(javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                    vendor.set(JvmVendorSpec.GRAAL_VM)
                })
                // Bundle classpath resources that integration-test wiring needs at runtime
                // but native-image otherwise wouldn't include:
                //   - Flyway migrations (`db/migration/V*.sql`) — Flyway scans the classpath
                //     by glob; without these bundled the migrations silently apply zero
                //     scripts and tests fail with "relation does not exist".
                //   - Testcontainers init scripts (`*_init.sql`) — referenced by
                //     `withInitScript()` calls in the MariaDB/MySQL test setups.
                // The Flyway RMR (org.flywaydb:flyway-core) only bundles its own
                // version.txt, not user migrations — that responsibility is per-app.
                resources.includedPatterns.add("db/migration/.*\\.sql")
                resources.includedPatterns.add(".*_init\\.sql")
            }
            // Test binaries don't need runtime perf — they need to pass and exit. -Ob
            // (quick build) trades runtime optimisation for ~30–50% faster native-image
            // compilation, the dominant cost of the nativeTest sweep. Production
            // binaries (`main`) keep full optimisation.
            binaries.named("test") {
                quickBuild.set(true)
            }
            // metadataRepository is a sub-extension on GraalVMExtension; reach it via
            // the ExtensionAware cast since the typed DSL helper isn't available here.
            (this as org.gradle.api.plugins.ExtensionAware)
                .extensions
                .configure<org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension>(
                    "metadataRepository"
                ) {
                    enabled.set(true)
                    version.set("1.0.0")
                }
        }
    }

    // Make build depend on spotlessApply to ensure code is formatted before building
    tasks.named("build") {
        // Ensure formatting is applied before compilation
        dependsOn("spotlessApply")
        // Optional: Fail the build if there are formatting violations
        // dependsOn("spotlessCheck")
    }
    
    // Optional: Add a task to check formatting without applying it
    tasks.register("checkFormat") {
        group = "verification"
        description = "Check code formatting without applying changes"
        dependsOn("spotlessCheck")
    }
}
