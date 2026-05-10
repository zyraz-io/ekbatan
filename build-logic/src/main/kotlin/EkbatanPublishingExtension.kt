import org.gradle.api.provider.Property

abstract class EkbatanPublishingExtension {
    abstract val artifactId: Property<String>
    abstract val description: Property<String>
}
