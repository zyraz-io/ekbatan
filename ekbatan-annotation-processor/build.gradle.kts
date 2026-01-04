plugins {
    `java-library`
}

dependencies {
    implementation("com.squareup:javapoet:${project.property("javaPoetVersion")}")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.compileJava {
    options.compilerArgs.add("-parameters")
}
