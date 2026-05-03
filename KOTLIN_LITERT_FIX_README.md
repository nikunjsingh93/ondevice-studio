# Kotlin / LiteRT-LM compile fix

This version fixes the `compileDebugKotlin` failure caused by `litertlm-android-0.10.2` being compiled with Kotlin metadata 2.3.0 while the previous project used Kotlin 2.0.21.

Changes:

- Android Gradle Plugin: 8.12.1
- Gradle wrapper: 8.13
- Kotlin Android plugin: 2.3.21
- Kotlin Compose compiler plugin: 2.3.21
- Coroutines Android: 1.10.2
- LiteRT-LM Android: 0.10.2

If Android Studio shows stale errors, close the project, delete `.gradle`, `build`, and `app/build`, then reopen and sync.

```bash
rm -rf .gradle build app/build
```
