#!/bin/bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
cd /opt/ARIRecog
/opt/gradle-8.7/bin/gradle assembleDebug --no-daemon 2>&1 | tail -12
echo '=== done ==='
ls -la /opt/ARIRecog/app/build/outputs/apk/debug/ 2>/dev/null
