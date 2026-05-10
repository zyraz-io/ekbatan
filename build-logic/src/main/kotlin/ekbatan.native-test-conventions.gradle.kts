import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.graalvm.buildtools.gradle.dsl.GraalVMReachabilityMetadataRepositoryExtension

plugins {
    id("org.graalvm.buildtools.native")
}

if (project.path != ":ekbatan-native") {
    dependencies {
        "testImplementation"(project(":ekbatan-native"))
    }
}

val javaToolchains = extensions.getByType<JavaToolchainService>()

configure<GraalVMExtension> {
    toolchainDetection.set(true)
    binaries.all {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            },
        )
        resources.includedPatterns.add("db/migration/.*\\.sql")
        resources.includedPatterns.add(".*_init\\.sql")
    }
    binaries.named("test") {
        quickBuild.set(true)
    }
    (this as ExtensionAware).extensions.configure<GraalVMReachabilityMetadataRepositoryExtension>("metadataRepository") {
        enabled.set(true)
        version.set("1.0.0")
    }
}
