# ARI Vision

Android real-time screen recognition powered by **Algebraic Residual Iteration (ARI)**.

**Current version: v2.9** (versionCode 11)

## Download APK

Get the installer from the Releases page:

**https://github.com/sayed1145/ARI-Vision/releases/latest**

## Technical white paper and this implementation

The original paper is the **naive / reference ARI** (closed-form least squares + algebraic residual iteration):

> Zhou, Juncai. *The Mixed-Group Framework and Algebraic Residual Iteration (ARI): From Structural Unification to Efficient Learning.* Zenodo, 2026.  
> **https://doi.org/10.5281/zenodo.21968497**

**This repository is not that naive implementation.** It is an **engineering ARI variant**: the same algebraic core, extended for real-time screen recognition (multi-shot consensus, heatmap attention, online adapt, rotation-safe tracking). Stronger in the product sense; the paper remains the theoretical source.

## Developers

| Role | Credit |
| --- | --- |
| Algorithm invention (white-paper author) | **Zhou Juncai** |
| Software engineering | **deepseek** |
| Symbolic reasoning | **NLM-AGI** |

## Features

- Live screen boxes; inject a full screenshot (no forced crop)
- Same label name = same object; multi-shot consensus drops background noise
- Editable heatmap; explicit template formula
- Batch add / delete; rename a label; deleting a label removes images, heatmap, adapt data, and the label from the UI
- Portrait / landscape capture rebuild so boxes stay on screen
- Online adapt layer does **not** write the base `arimodel.bin`

## Source

Android project at the repository root. Java sources: `app/src/main/java/com/ari/recog/`.

## Build

Android SDK and JDK 17+.

```bash
# local.properties: sdk.dir=/path/to/android-sdk
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
