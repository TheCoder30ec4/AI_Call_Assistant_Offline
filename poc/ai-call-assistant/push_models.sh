#!/bin/bash
# Copies the AI models to the phone. Usage: push_models.sh <folder from get_models.sh>
#
# They're left in /data/local/tmp (readable only by adb's shell user). When the app next connects to
# Shizuku it imports them into its private storage and deletes this copy. Needs ~6 GB free while
# both copies exist.
set -eu
ADB="${ADB:-adb}"
PACKAGE=app.offlinevoice.callprobe
DEST=/data/local/tmp/ai-call-assistant/models

"$ADB" shell pm path "$PACKAGE" >/dev/null || { echo "Install the app first."; exit 1; }
"$ADB" shell mkdir -p "$DEST"
"$ADB" push "$1/." "$DEST/"
"$ADB" shell du -sh "$DEST"
echo "Now open AI Call Assistant (with Shizuku running). It imports the models in a minute or two."
