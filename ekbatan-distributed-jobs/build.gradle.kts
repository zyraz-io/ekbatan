plugins {
    `java-library`
}

group = "io.ekbatan.distributedjobs"
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
    api("com.github.kagkarlsson:db-scheduler:${project.property("dbSchedulerVersion")}")

    // Hikari is needed only at compile time, to resolve the HikariDataSource return type
    // of ConnectionProvider#getDataSource(). It's already on the runtime classpath via
    // ekbatan-core's transitive runtime deps.
    compileOnly("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")

    implementation("org.apache.commons:commons-lang3:${project.property("commonsLang3Version")}")
}
