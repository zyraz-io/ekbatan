plugins {
    `java-library`
}

group = "io.ekbatan.keyedlock.redis"
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
    api(project(":ekbatan-core"))
    api("org.redisson:redisson:${project.property("redissonVersion")}")

    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")
}
