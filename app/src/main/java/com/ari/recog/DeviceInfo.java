package com.ari.recog;

import android.os.Build;

/**
 * DeviceInfo — 设备计算信息（CPU/GPU）。
 * 本应用训练/识别用纯 Java 闭式运算，在 CPU 上运行（无 GPU 依赖）。
 */
public final class DeviceInfo {

    public static String describe() {
        String cpu = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "unknown";
        String model = Build.MODEL;
        String gpu = "无（纯 Java 闭式，CPU 计算）";
        String arch = Build.HARDWARE;
        return "CPU/ABI=" + cpu + ", 设备=" + model
                + ", 硬件=" + arch + "\nGPU=" + gpu;
    }
}
