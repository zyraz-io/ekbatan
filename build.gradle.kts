plugins {
    `java-library`
    id("com.diffplug.spotless") version "6.22.0"
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
        indentWithSpaces(4) // or tabs(1)
        endWithNewline()
    }
    
}

allprojects {
    group = "com.example.springdd"
    version = "0.0.1-SNAPSHOT"

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
    
    // Configure Spotless for each subproject
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        // Configure Kotlin Gradle scripts
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
            trimTrailingWhitespace()
            indentWithSpaces(4)
            endWithNewline()
        }
        
        // Configure format for miscellaneous files
        format("misc") {
            target("*.md", ".gitignore", ".gitattributes", "*.yaml", "*.yml", "*.json")
            trimTrailingWhitespace()
            indentWithSpaces(4)
            endWithNewline()
        }
    }

    dependencies {

        testImplementation(platform("org.junit:junit-bom:6.0.1"))
        testImplementation("org.junit.jupiter:junit-jupiter")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")


        testImplementation("io.mockk:mockk:1.13.8")
        testImplementation("org.assertj:assertj-core:3.24.2")
    }
    
    // Configure Java compilation to ensure compatibility
    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
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
