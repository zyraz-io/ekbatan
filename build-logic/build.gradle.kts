plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.1.0")
    implementation("org.graalvm.buildtools:native-gradle-plugin:1.1.0")
}
