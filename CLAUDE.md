# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and Deploy

```bash
# Debug APK (local development)
./gradlew assembleDebug

# APK path: app/build/outputs/apk/debug/app-debug.apk
```

**Prerequisites:** Android Studio Hedgehog+, JDK 17, Android SDK 33+, Shizuku v13+ on device.

**Remote CI:** GitHub Actions workflow at `.github/workflows/main.yml` builds and uploads APK artifacts on push to main.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    SplitScreen Input Bridge                   │
├─────────────────────────────────────────────────────────────┤
│  InputBridgeAccessibilityService (global input interceptor) │
│              ↓                                                │
│  InputBridgeService (foreground service, routing logic)     │
│              ↓                                                │
│  ┌──────────────────┬──────────────────┬─────────────────┐  │
│  │ Player 1 (P1)    │  Transformation  │ Player 2 (P2)   │  │
│  │ Pass-through     │  Gamepad→Touch   │ Shizuku Inject  │  │
│  └──────────────────┴──────────────────┴─────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Key Patterns

1. **Input Routing Logic** (`InputBridgeService.onGamepadMotionEvent()`):
   - Player 1 controllers pass through natively (controls focused Minecraft)
   - Player 2 controllers intercepted, transformed to touch coordinates mapped to lower half of screen, injected via Shizuku

2. **Device Differentiation** (`computeDeviceFingerprint()`):
   - Uses molecular fingerprint combining: descriptor, EVIOCGUNIQ (unique ID), EVIOCGPHYS (physical path), and EVIOCGID (bus/vendor/product/version)
   - Allows differentiating identical controllers by their hardware identifiers

3. **System Hacks** (`applySystemHacks()`):
   - Enables `multi_window_focus_enabled=1` for simultaneous input to split-screen windows
   - Applied via Shizuku shell commands with `INJECT_EVENTS` privilege

4. **Architecture Layers** (defined in `ARCHITECTURE_IMPROVEMENTS.md`):
   - **State Machine**: `BridgeState.kt`, `BridgeStateManager.kt`
   - **Repository Pattern**: `ControllerRegistry.kt`, `ShizukuServiceRepository.kt`
   - **Worker Pattern**: `WatchdogWorker.kt`, `WatchdogManager.kt`
   - **Metrics**: `PerformanceMetrics.kt`, `SystemMetricsCollector.kt`
   - **Logging**: `StructuredLogger.kt`
   - **Dynamic Config**: `DynamicConfigManager.kt`, `AdvancedConfigManager.kt`
   - **Persistence**: `ProfilePersistenceManager.kt`

### Service Files

| File | Purpose |
|------|---------|
| `InputBridgeService.kt` | Main foreground service (routing, watchdog, system hacks) |
| `InputBridgeService.kt` | Enhanced version with metrics/logging/config |
| `InputBridgeService.kt` | *(Deprecated - consolidated into InputBridgeService.kt)* |
| `ShizukuUserService.kt` | Shizuku bridge for privileged input injection |
| `InputBridgeAccessibilityService.kt` | Global input event interceptor |

## Automated Testing and Monitoring

### GitHub Actions Workflow
- **Location**: `.github/workflows/main.yml`
- **Schedule**: Runs every 10 minutes
- **Actions**: Build APK, validate structure, upload artifacts

### Local Monitoring Scripts
- **Windows**: `monitor_apk.ps1` - PowerShell monitoring script
- **Linux/macOS**: `monitor_apk.sh` - Bash monitoring script
- **Frequency**: Every 5 minutes
- **Features**: Build verification, APK validation, notifications, auto-fix

### Auto-Fix System
- **Windows**: `auto_fix_apk.ps1` - PowerShell auto-fix script
- **Linux/macOS**: `auto_fix_apk.sh` - Bash auto-fix script
- **Capabilities**: Dependency fixes, compilation fixes, memory fixes, installation fixes

### Demo and Verification
- **Windows**: `show_app_working.ps1`, `demo_app.ps1`
- **Linux/macOS**: `show_app_working.sh`, `demo_app.sh`
- **Features**: Build, install, start app, simulate interactions, show logs

### Usage
```bash
# Start monitoring with auto-fix (Windows)
.\monitor_apk.ps1

# Start monitoring with auto-fix (Linux/macOS)  
chmod +x monitor_apk.sh
./monitor_apk.sh

# Show app working completely (Windows)
.\show_app_working.ps1

# Show app working completely (Linux/macOS)
chmod +x show_app_working.sh
./show_app_working.sh
```

## Test Policy

- No `./gradlew` or local compilation allowed per user instructions
- CI/CD validation only via GitHub Actions (`gh run watch`)
- Auto-fix system handles common build/installation issues automatically
- Demo scripts verify app is actually working on connected devices
