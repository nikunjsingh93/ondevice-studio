<p align="center">
  <img src="docs/assets/icon_no_bg.png" alt="OnDevice Studio logo" width="72" />
</p>

<h1 align="center">OnDevice Studio</h1>

<p align="center">
  On-device Android app for generating and previewing web apps (HTML/CSS/JS) with local AI workflows.
</p>

## Features

- Jetpack Compose Android UI
- Built-in WebView live preview
- Local project workspace in app-private storage
- File import/export (including ZIP export)
- On-device model integration via LiteRT-LM (`.litertlm`)
- Demo mode for testing without loading a model
- Safe in-app file writing for generated project files

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- Android WebView
- LiteRT-LM Android runtime
- ML Kit (OCR / labeling helpers)

## Requirements

- Android Studio (recent stable)
- JDK 17+
- Android device (recommended) or emulator
- Android SDK matching project config

## Getting Started

1. Clone the repository.
2. Open the project in Android Studio.
3. Let Gradle sync complete.
4. Run the `app` configuration on a device.

## Model Setup (Gemma / LiteRT-LM)

This repository does **not** include model files.

1. Download a compatible `.litertlm` model on your device.
2. Launch the app.
3. Open menu and import the model.
4. Load the model and start prompting.

Reference model family:

- `litert-community/gemma-4-E2B-it-litert-lm`

Model source:

- [Hugging Face - litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main)

## Project Structure

- `app/src/main/java/com/nikunj/gemmabuilder/` - main app source
- `app/src/main/res/` - Android resources
- `app/src/main/AndroidManifest.xml` - app manifest
- `app/build.gradle.kts` - app module build config

## Security and Privacy Notes

- Generated project files stay inside app-private storage by default.
- External web navigation is restricted in preview flow.
- Imported files/models are handled locally on-device.

## Build Notes

Current app version:

- `versionName`: `1.0.5`
- `versionCode`: `1`

LiteRT-LM dependency currently pinned in app Gradle config. If your environment needs it, you can switch to `latest.release`.

## Roadmap Ideas

- Multi-project workspace management
- Better template/gallery flows
- Improved model prompt tooling
- Release packaging improvements

## Contributing

Contributions are welcome.

1. Fork the repo
2. Create a feature branch
3. Make your changes
4. Open a pull request

## License

Add your preferred license here (for example, MIT, Apache-2.0, or GPL-3.0).
