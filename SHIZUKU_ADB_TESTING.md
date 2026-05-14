# Shizuku ADB Testing Guide

This guide explains how to test Shizuku functionality using ADB commands, which is particularly useful for troubleshooting when the device is on a local network.

## Prerequisites

1. ADB installed on your computer
2. USB debugging enabled on your Android device
3. Device connected to the same local network as your computer (for network ADB)

## ADB Testing Commands

### 1. Basic Connectivity Test
```bash
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "connectivity"
```

### 2. Shell Command Execution Test
```bash
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "shell" --es command "echo test"
```

### 3. Input Injection Test
```bash
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "input"
```

### 4. Comprehensive Diagnostic
```bash
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "diagnose"
```

## Additional Useful ADB Commands

### Check if app can be started:
```bash
adb shell am start -n com.splitscreen.inputbridge/.MainActivity
```

### Check Shizuku service status:
```bash
adb shell dumpsys activity services | grep -i shizuku
```

### Check app permissions:
```bash
adb shell dumpsys package com.splitscreen.inputbridge | grep permission
```

### Force stop and restart app:
```bash
adb shell am force-stop com.splitscreen.inputbridge
adb shell am start -n com.splitscreen.inputbridge/.MainActivity
```

### Check Shizuku logs:
```bash
adb logcat | grep -i shizuku
```

### Test shell execution:
```bash
adb shell sh -c "echo test"
```

## Network ADB Setup

If your device is on the same local network:

1. Connect device via USB initially
2. Enable network ADB on device:
   ```bash
   adb tcpip 5555
   ```
3. Disconnect USB and connect over network:
   ```bash
   adb connect DEVICE_IP_ADDRESS:5555
   ```

## Common Issues and Solutions

### Permission Not Detected After Granting
1. Force stop both apps:
   ```bash
   adb shell am force-stop com.splitscreen.inputbridge
   adb shell am force-stop com.splitscreen.inputbridge.debug
   adb shell am force-stop moe.shizuku.privileged.api
   ```

2. Restart Shizuku service:
   ```bash
   adb shell am force-stop moe.shizuku.privileged.api
   adb shell am start -n moe.shizuku.privileged.api/.MainActivity
   ```

3. Re-grant permission in Shizuku app

### Binder Not Alive
1. Check if Shizuku service is running:
   ```bash
   adb shell ps | grep shizuku
   ```

2. Restart Shizuku service if not running

### Shell Execution Failed
1. Check if device has proper SELinux context:
   ```bash
   adb shell getenforce
   ```

2. Verify Shizuku has proper root/ADB access in Shizuku app settings