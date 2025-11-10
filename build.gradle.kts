// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
    // SpotBugs Gradle plugin (enable in modules via: id("com.github.spotbugs"))
    id("com.github.spotbugs") version "6.0.23" apply false
}
