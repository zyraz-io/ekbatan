plugins {
    `java-library`
    `maven-publish`
}

val ekbatanPublishing = extensions.create<EkbatanPublishingExtension>("ekbatanPublishing")

java {
    withJavadocJar()
    withSourcesJar()
}

afterEvaluate {
    val artifactId = ekbatanPublishing.artifactId.orNull
        ?: error("Module ${project.path} applies 'ekbatan.publishing' but doesn't set ekbatanPublishing.artifactId")
    val description = ekbatanPublishing.description.orNull
        ?: error("Module ${project.path} applies 'ekbatan.publishing' but doesn't set ekbatanPublishing.description")

    base.archivesName.set(artifactId)

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                groupId = "io.github.zyraz-io"
                this.artifactId = artifactId
                // Gradle's "capability" concept (used by java-test-fixtures) has no POM equivalent.
                // We don't publish test fixtures to Maven coordinates, so silence the warning.
                suppressPomMetadataWarningsFor("testFixturesApiElements")
                suppressPomMetadataWarningsFor("testFixturesRuntimeElements")
                pom {
                    name.set(artifactId)
                    this.description.set(description)
                    url.set("https://github.com/zyraz-io/ekbatan")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("unikzforce")
                            name.set("Hossein Bakhtiari Ziabari")
                            email.set("h.bakhtiary.z@gmail.com")
                            url.set("https://github.com/unikzforce")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/zyraz-io/ekbatan.git")
                        developerConnection.set("scm:git:ssh://git@github.com/zyraz-io/ekbatan.git")
                        url.set("https://github.com/zyraz-io/ekbatan")
                        // For released versions, point at the matching tag (v<version>); for
                        // snapshots, use Maven's "HEAD" convention since snapshots aren't tagged.
                        val v = project.version.toString()
                        tag.set(if (v.endsWith("-SNAPSHOT")) "HEAD" else "v$v")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}
