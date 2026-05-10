plugins {
    `java-library`
    id("ekbatan.publishing")
}

ekbatanPublishing {
    artifactId.set("ekbatan-di-annotations")
    description.set(
        "DI stereotype annotations for Ekbatan (@EkbatanAction, @EkbatanRepository, @EkbatanEventHandler, @EkbatanDistributedJob).",
    )
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
