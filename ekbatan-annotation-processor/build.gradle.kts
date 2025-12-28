plugins {
    `java-library`
}

dependencies {
    implementation("com.squareup:javapoet:1.13.0")

    // Annotation processor dependencies
    annotationProcessor("com.google.auto.service:auto-service:1.0.1")
    compileOnly("com.google.auto.service:auto-service-annotations:1.0.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.compileJava {
    options.compilerArgs.add("-parameters")
}
