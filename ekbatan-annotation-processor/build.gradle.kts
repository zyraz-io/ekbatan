plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-annotation-processor")
    description.set("AutoBuilder annotation processor — generates Builder classes for @AutoBuilder-annotated Ekbatan Models.")
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
