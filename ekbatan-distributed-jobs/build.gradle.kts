plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-distributed-jobs")
    description.set("Distributed job scheduler (db-scheduler-backed) for Ekbatan.")
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
    api("com.github.kagkarlsson:db-scheduler:${project.property("dbSchedulerVersion")}")

    // JobRegistryBuilderTest uses a real Hikari pool with a bogus postgresql URL as its
    // ConnectionProvider fixture. Hikari calls Class.forName on the driver during validation
    // before the test's expected IllegalArgumentException is thrown, so the driver class must
    // be on the test classpath.
    testImplementation("org.postgresql:postgresql:${project.property("postgresqlVersion")}")
    // ekbatan-core declares HikariCP as compileOnly so it does not leak transitively to this
    // module's test classpath; the JobRegistryBuilderTest fixture needs a real pool, so pull
    // Hikari in for tests explicitly.
    testRuntimeOnly("com.zaxxer:HikariCP:${project.property("hikariCpVersion")}")
}
