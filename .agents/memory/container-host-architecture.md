---
name: ContainerHostActivity architecture
description: Correct container architecture for rooted device — Android user profiles via root
---

## Rule
Use Android multi-user isolation via root (`pm create-user` + `am start --user <id>`), NOT in-process DexClassLoader app invocation. DexClassLoader is only for dex inspection/method enumeration — never call invokeApplication() / Application.onCreate() via reflection.

**Why:** Running a guest app's Application.onCreate() in-process via reflection always crashes for real apps (Binder, ContentProviders, native libs can't bootstrap inside a foreign process). MultiApp's HackApi does this at the native level — we can't replicate that in pure Kotlin. On a rooted device, Android user profiles give true data isolation without any native framework.

**How it works:**
1. `UserSpaceManager.ensureContainerUser()` — creates one persistent Android user profile via `pm create-user`, stores userId in SharedPrefs
2. `UserSpaceManager.installIntoContainer()` — `pm install-existing --user <id> <pkg>` makes the host-installed app available in the container user
3. `UserSpaceManager.launchInContainer()` — `am start --user <id> -a MAIN -c LAUNCHER <pkg>` runs it isolated
4. `UserSpaceManager.installFakeSuForContainer()` — writes fake su scripts to `/data/user/<id>/<pkg>/files/vsbin/` using real root
5. DexClassLoader still loads the APK read-only for class/method enumeration in the overlay

**ContainerHostActivity flow:**
- Make APK read-only → load dex (inspection only) → isRooted() check → launchInContainer() → find PID → start InspectorOverlayService → finish()
- If not rooted: warn user, dex inspection only, no isolation

## Read-only APK rules
- Always `setWritable(true)` BEFORE copy (re-installs), then `setWritable(false)` AFTER copy
- `deleteRecursively()` needs `walkBottomUp { setWritable(true) }` first
- Check and fix in ContainerHostActivity just before DexClassLoader too (handles pre-existing state)

## MultiApp reference
The `opensdk/` in MultiApp zip is an empty git submodule — closed-source precompiled binary (HackApi). There is no Java/Kotlin logic to extract from it. The open-source part is purely UI calling HackApi.installPackageFromHost() and HackApi.startActivity(intent, userId).
