plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.1.0"
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
    
    // Add annotation processor configuration for non-annotation-processor modules
    if (project.name != "ekbatan-annotation-processor") {
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
    
    // Configure Java compilation to ensure compatibility
    tasks.withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
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
