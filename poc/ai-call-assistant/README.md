# AI Call Assistant: Android app

This folder is the Android app. Setup, usage, build instructions and licenses are in the
[main README](../../README.md).

Quick reference:

```sh
./get_models.sh ~/ai-call-assistant-models     # download the models (~2.8 GB)
./gradlew test assembleDebug                    # debug build
./gradlew test lintRelease assembleRelease      # release build (needs keystore.properties)
./push_models.sh ~/ai-call-assistant-models     # copy the models to the phone
```
