---
name: ContainerHostActivity architecture
description: Why invokeApplication() was removed and what the correct launch flow is
---

## Rule
Never call `AppLoader.invokeApplication()` (or any reflection-based Application.onCreate()) inside ContainerHostActivity. It always crashes for real apps.

**Why:** Running a guest app's Application class in-process via reflection fails universally because: the Application hits Binder calls that require proper UID/process identity, tries to install ContentProviders, accesses native libs not bootstrapped in the host process. Without a full virtual-app native framework (VirtualApp/HackApi style), this path is a dead end.

**How to apply:**
- `ContainerHostActivity.loadContainer()` should:
  1. Make APK read-only (Android 8+ security check on DexClassLoader)
  2. Load dex via `AppLoader.loadFromPath()` for static inspection only
  3. Launch the real installed app via `getLaunchIntentForPackage()` + `startActivity()`
  4. Wait ~1.5s, find PID via `ContainerEngine.findPid()`
  5. Start `InspectorOverlayService` with that PID
  6. `finish()` the loading screen

## Read-only APK rules
- `ContainerManager.install()`: make dst writable BEFORE copy, then read-only AFTER
- `ContainerManager.uninstall()`: walk tree and make writable BEFORE deleteRecursively
- `ContainerHostActivity`: check and set read-only just before DexClassLoader load (handles pre-existing writable files)

## What MultiApp (reference) actually uses
The `opensdk/` in MultiApp is a closed-source precompiled native library (HackApi) — a git submodule not included in the zip. There is no logic to "rip out". The open-source part is just a thin UI wrapper calling HackApi.installPackageFromHost() and HackApi.startActivity().
