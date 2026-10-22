// Versions are pinned here so a fresh clone builds the same. If Android Studio offers to upgrade the
// Android Gradle Plugin or Kotlin, accepting is fine (keep the Compose compiler plugin on the Kotlin version).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
