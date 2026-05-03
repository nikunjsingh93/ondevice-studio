# Android Studio Sync Fix

This build is pinned to Android Gradle Plugin **8.12.1** because some Android Studio versions show:

> Latest supported version is AGP 8.12.1

AGP 8.12.x uses Gradle **8.13** according to the Android Gradle Plugin 8.12 release notes, so the wrapper properties are set to Gradle 8.13.

If Android Studio still syncs using an old cached configuration, delete these folders and sync again:

```bash
rm -rf .gradle build app/build
```

Then open Android Studio settings:

Build, Execution, Deployment → Build Tools → Gradle → Use Gradle wrapper.

The important version pin is in the root `build.gradle.kts`:

```kotlin
id("com.android.application") version "8.12.1" apply false
```
