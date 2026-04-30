plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    api(project(":ekbatan-di:quarkus:runtime"))

    api("io.quarkus:quarkus-core-deployment:${project.property("quarkusVersion")}")
    api("io.quarkus:quarkus-arc-deployment:${project.property("quarkusVersion")}")

    // Optional Ekbatan modules are deliberately NOT declared — the deployment processor only
    // references them via QuarkusClassLoader.isClassPresentAtRuntime, so adding them here would
    // load EventHandler/DistributedJob on the deployment classloader alongside the runtime one
    // and trip IncompatibleClassChangeError at @All List<...> injection.
}
