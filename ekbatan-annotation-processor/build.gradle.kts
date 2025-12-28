plugins {
    `java-library`
}

dependencies {
    implementation("com.squareup:javapoet:${project.property("javaPoetVersion")}")

    // Annotation processor dependencies
    annotationProcessor("com.google.auto.service:auto-service:${project.property("autoServiceVersion")}")
    compileOnly("com.google.auto.service:auto-service-annotations:${project.property("autoServiceVersion")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.compileJava {
    options.compilerArgs.add("-parameters")
}
