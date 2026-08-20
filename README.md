# ARI Vision · ARI 识图

Android 屏幕实时识别。同标签多图合模、热力图、横竖屏自适应。

**当前版本：v2.9**（versionCode 11）

## 下载 APK

到 Releases 页下载安装包：

**https://github.com/sayed1145/ARI-Vision/releases/latest**

或仓库内：[`apk/ARIRecog-v2.9-debug.apk`](apk/ARIRecog-v2.9-debug.apk)

## 功能

- 屏幕实时框选；全图注入（不强制裁剪）
- 同名标签 = 同一物体，多图合模去噪
- 热力图可编辑；模板显式公式
- 一次添加 / 删除多张图；改名；删除标签会清图片、热力图、适应层并从界面消失
- 横竖屏自动重建捕获，框不飞出屏幕
- 在线适应层不写基座 `arimodel.bin`

## 源码

Android 工程在本仓库根目录。`app/src/main/java/com/ari/recog/` 为 Java 源码。

## 构建

需要 Android SDK 与 JDK 17+。

```bash
# 写入 local.properties: sdk.dir=/path/to/android-sdk
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
