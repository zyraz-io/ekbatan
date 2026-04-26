plugins {
    `java-library`
}

group = "io.ekbatan.events.localeventhandler"
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
    api(project(":ekbatan-distributed-jobs"))
    api("com.github.kagkarlsson:db-scheduler:${project.property("dbSchedulerVersion")}")

    // Hikari only needed at compile time to resolve ConnectionProvider#getDataSource()'s return type.
    compileOnly("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")
    implementation("io.opentelemetry:opentelemetry-api:${project.property("opentelemetryVersion")}")
}
