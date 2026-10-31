# Packaging an app for its own Studio profile

Ulanzi Studio links a profile to an executable's full path. Every Java app run with plain Java is `java.exe` or `javaw.exe` to it, so Studio can't give them separate profiles. Packaging an app with `jpackage` (JDK 14 and later) gives it its own executable, which Studio can link to.

For apps run with plain Java, such as during development, use focus routing instead: see "Several Java apps on one Studio profile" in [xml-pages.md](xml-pages.md).

## Tested result

Tested on Windows with `FocusDemo`, built as a `jpackage` app-image with JDK 25 and linked to its own Studio profile:

- Windows reports the app's window as owned by `FocusDemo.exe`, not `javaw.exe`.
- Studio treated it as a separate app, and the deck's slots worked with it.
- The launcher starts a second copy of itself. That child owns the window and the plugin connection, and both have the same path, so this made no difference to Studio.
- The app folder was about 140 MB with the bundled runtime.

Mac and Linux are untested. Ulanzi's manifest docs list only Windows and Mac as platforms, so there's probably nothing on Linux to link to. On Mac, Studio identifies apps by bundle ID (`--mac-package-identifier`).

## Building on Windows

```powershell
# 1. Collect the app jar and its runtime dependencies in one folder
mvn package dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=target/input"
Copy-Item target\myapp-1.0.jar target\input\

# 2. Build the app folder with its launcher
jpackage --type app-image `
  --name MyApp `
  --input target\input `
  --main-jar myapp-1.0.jar `
  --main-class com.example.Launcher `
  --icon app.ico `
  --dest target\dist
```

The result is `target\dist\MyApp\MyApp.exe`, with its own `runtime\` and `app\` folders and no console window. For an installer, use `--type msi` or `--type exe`, which also need the WiX Toolset.

- **Main class:** use a plain class that calls `Application.launch(...)`, not one that extends `Application`. Otherwise, with JavaFX on the classpath, Java typically fails at startup with "JavaFX runtime components are missing".
- **Dependencies:** `ulanzi-client-fx` has `common-java-utils-jfx` in `provided` scope, so the app must declare its own version for it to land in the input folder.
- **Native jars:** Maven picks JavaFX's native jars for the OS it runs on, and `jpackage` can't cross-build, so build on each target OS.
- **Unsigned builds:** an unsigned exe can trigger SmartScreen or antivirus warnings.

## Linking it in Studio

1. Install or copy the app to its permanent location first. The profile stores the full path, so moving the app or rebuilding it elsewhere breaks the link.
2. Link a Studio profile to the exe.
3. Add App slots and set their **App ID** to the app's ID, which is its folder name under `RemoteControl/UlanziDeck` in the XML.
4. Leave out the `group` attribute for that app, since with its own profile it doesn't need focus routing.
