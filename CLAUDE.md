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
| `InputBridgeServiceEnhanced.kt` | Enhanced version with metrics/logging/config |
| `InputBridgeServiceNew.kt` | New service with DI and improved architecture |
| `ShizukuUserService.kt` | Shizuku bridge for privileged input injection |
| `InputBridgeAccessibilityService.kt` | Global input event interceptor |

### Test Policy

- No `./gradlew` or local compilation allowed per user instructions
- CI/CD validation only via GitHub Actions (`gh run watch`)
