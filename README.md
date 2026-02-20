# Java2Smali

Java2Smali is an Android editor/workbench for writing Java and converting it to Smali, with workspace management, package-folder structure, dependency browsing, and release-oriented build/export flow.

## Features

- Java/Smali dual-view editing and conversion
- Workspace-based project organization (multiple workspaces)
- Package-folder tree support (create/move/copy/rename/delete)
- Search/replace with workspace scope and highlight
- Dependency import and preview (JAR/DEX)
- DEX export (project-own `classes.dex`)

## Build

Release build (recommended):

```bash
./gradlew testReleaseUnitTest --no-daemon
./gradlew assembleRelease --no-daemon
```

Generated APK path:

`app/build/outputs/apk/release/app-release-unsigned.apk`

## Project Structure

- `app/src/main/java/com/java2smali/` core app logic
- `app/src/main/res/` UI resources
- `app/src/test/` unit tests

## Notes

- Keep using release tasks for verification.
- `local.properties` is machine-local and not versioned.

## License

This project is open source. Add your preferred license file if needed.
