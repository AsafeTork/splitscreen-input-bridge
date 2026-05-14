# Shizuku Troubleshooting Guide

This guide provides solutions for common Shizuku issues, particularly when permission appears to be granted but the system still shows errors.

## Common Issue: "Permission Granted but Not Working"

### Symptoms
- Shizuku permission granted in the app
- App UI shows everything in red at the top
- "Permissão concedida" status but service not connecting
- Device is on local network

### Root Causes and Solutions

#### 1. Permission Synchronization Issues
**Problem**: Permission is granted in Shizuku but not detected by your app.

**Solutions**:
1. Force stop both apps and restart:
   - Settings → Apps → SplitScreen Input Bridge → Force Stop
   - Settings → Apps → Shizuku → Force Stop
   - Restart both apps

2. Revoke and re-grant permission multiple times:
   - In Shizuku app, find your app and revoke permission
   - Restart your app and grant permission again
   - Repeat this process 2-3 times

3. Check if app appears in Shizuku's permission management:
   - Open Shizuku app
   - Look for "SplitScreen Input Bridge" in the permissions list
   - Ensure it shows as granted

#### 2. Binder Connectivity Problems
**Problem**: Shizuku service binder is not properly connected.

**Solutions**:
1. Restart Shizuku service:
   - Force stop Shizuku app
   - Start Shizuku app again
   - Wait for "Service started" notification

2. Check Shizuku service status:
   - In Shizuku app, look for service status indicators
   - Ensure it shows "Running" or similar positive status

3. Verify Shizuku setup method:
   - Root mode: Ensure device is properly rooted
   - ADB mode: Ensure wireless debugging is enabled and working

#### 3. Device-Specific Issues
**Problem**: Some devices have specific requirements or restrictions.

**Solutions**:
1. Check device security settings:
   - Some devices block background services
   - Battery optimization settings may interfere
   - MIUI, EMUI, and other custom ROMs may need special permissions

2. Add app to battery optimization whitelist:
   - Settings → Battery → Battery optimization
   - Find your app and set to "No restriction"

3. Enable background activity:
   - Settings → Apps → Your app → Battery
   - Allow background activity

#### 4. Network/Local Environment Issues
**Problem**: Device on local network may have connectivity restrictions.

**Solutions**:
1. Use ADB for direct testing:
   - Connect via USB initially
   - Use network ADB after confirming functionality

2. Check network restrictions:
   - Some networks block certain ports or services
   - Corporate networks may have additional restrictions

## Diagnostic Steps

### 1. Run Built-in Diagnostics
In the app, tap the "Diagnosticar Shizuku" button to run comprehensive diagnostics.

### 2. Use ADB Testing
If available, use ADB commands to test Shizuku functionality:

```bash
# Test connectivity
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "connectivity"

# Run comprehensive diagnostic
adb shell am broadcast -n com.splitscreen.inputbridge/.util.AdbTestReceiver --es action "diagnose"
```

### 3. Check Logs
Use ADB to check detailed logs:

```bash
adb logcat | grep -i shizuku
adb logcat | grep -i splitscreen
```

## Advanced Troubleshooting

### For Root Mode Devices
1. Verify root access:
   ```bash
   adb shell su -c "echo test"
   ```

2. Check Shizuku root configuration:
   - In Shizuku app, verify it's using root mode
   - Ensure root app (Magisk, SuperSU) grants Shizuku root access

### For ADB Mode Devices
1. Verify ADB connection:
   ```bash
   adb devices
   ```

2. Check wireless debugging:
   - Ensure wireless debugging is properly enabled
   - Confirm device IP address is correct

3. Restart ADB server:
   ```bash
   adb kill-server
   adb start-server
   ```

## Prevention Tips

1. **Regular Maintenance**:
   - Periodically restart Shizuku service
   - Update Shizuku app when new versions are available

2. **Battery Settings**:
   - Keep app in battery optimization whitelist
   - Allow background activity

3. **System Updates**:
   - Some Android updates may reset Shizuku permissions
   - Re-grant permissions after system updates

4. **Network Changes**:
   - Network changes may affect ADB connectivity
   - Re-establish ADB connection when network changes

## When to Contact Support

If none of the above solutions work:

1. Provide detailed logs:
   ```bash
   adb logcat -v time | grep -iE "(shizuku|splitscreen)" > logs.txt
   ```

2. Include device information:
   - Android version
   - Device model
   - Shizuku version
   - Root/ADB mode

3. Describe exact steps to reproduce the issue