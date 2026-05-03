# Gemma Android Builder

This is a minimal Android Studio project for an on-device HTML/CSS/JS builder app.

It includes:

- Kotlin + Jetpack Compose UI
- WebView live preview
- Local project workspace in app private storage
- XML `<action name="write_file">` parser
- Safe file writing for generated web files
- Demo builder engine so you can test immediately without a model
- LiteRT-LM Gemma engine integration for `.litertlm` models
- Model import using Android's file picker

## Open and run

1. Unzip this folder.
2. Open the folder in Android Studio.
3. Let Gradle sync.
4. Connect a physical Android phone.
5. Run the `app` configuration.
6. Try demo mode first: prompt it with `Build a calculator`.

## Using Gemma 4 E2B

The app does not bundle the model because the `.litertlm` file is very large.

Model download page:
- https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main

1. Download a Gemma 4 E2B `.litertlm` model to your phone.
2. Open the app.
3. Tap **Import .litertlm**.
4. Pick the model file.
5. Tap **Load Gemma**.
6. Ask it to build or edit an app.

Recommended model family:

- `litert-community/gemma-4-E2B-it-litert-lm`

## Notes

- The MVP is intentionally single-file-first: it asks the model to write `index.html` with inline CSS and JS.
- This is much more reliable for small on-device models.
- The code blocks external navigation in WebView.
- Generated files are only written inside the app sandbox under `files/projects/default`.
- Use Android Studio Device Explorer to inspect the generated files.

## If Gradle complains about LiteRT-LM

This project pins:

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:0.10.2")
```

If needed, change it to:

```kotlin
implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")
```

Also make sure Android Studio is using a recent JDK. JDK 17+ is recommended; newer LiteRT-LM versions may need newer toolchains.
