# Contributing

Conventions come from `docs/handoff.md` §3; the build plan and the order of work are in
`docs/implementation-plan.md`.

## Build

Requirements: JDK 21 (the code targets Java 17 bytecode) and, for the Android modules, an Android SDK with
platform 37 (`ANDROID_HOME` or `sdk.dir` in `local.properties`). Without an SDK the Android modules are skipped
and everything else still builds.

```sh
./gradlew build                       # compile, unit tests, ktlint, architecture tests
./gradlew ktlintFormat                # fix formatting
./gradlew :core:crypto:jvmTest        # one module's tests
./gradlew :ui:desktop:run             # run the desktop app
./gradlew :ui:android:assembleDebug   # build the Android APK
```

## Layout

`core/*` is Kotlin Multiplatform with a JVM target and no Android or desktop-UI imports; `platform/*`
implements the `core` interfaces; `ui/*` is Compose. Dependencies point `ui → platform → core`, and
`:tools:arch-test` fails the build if they do not. Module READMEs say what each one owns.

## Rules

- Branches: `main` is protected; work on `feat/<feature-id>-short-name` or `fix/…`. Squash merge.
- Feature IDs from `docs/features.md` (for example `F-E2`) go in PR titles and test names.
- Commit messages in the imperative mood. PR descriptions list the acceptance criteria they touch.
- Every protocol message has a serializer test with golden CBOR bytes checked in.
- Coroutines and `StateFlow` for engine state; the UI never talks to radios directly.
- A PR touching `core/transfer`, `core/ladder` or a radio includes a bench CSV row from the two-phone rig.
- No secrets in the repository; signing keys live in the CI secret store.
- Spec changes go into `docs/architecture.md` in the same PR, with a "Changed:" note at the top of the section.
