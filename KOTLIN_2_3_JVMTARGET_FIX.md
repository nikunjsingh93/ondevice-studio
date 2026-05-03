# Kotlin 2.3 jvmTarget fix

This version keeps Kotlin 2.3.21 for LiteRT-LM 0.10.2, but replaces the old Gradle syntax:

```kotlin
kotlinOptions {
    jvmTarget = "17"
}
```

with the Kotlin 2.3 compatible syntax:

```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
```

If Android Studio still uses old cached scripts, delete `.gradle`, `build`, and `app/build`, then sync again.
