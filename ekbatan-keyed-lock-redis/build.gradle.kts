plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-keyed-lock-redis")
    description.set("Redisson-backed KeyedLockProvider implementation for Ekbatan.")
}

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
}
