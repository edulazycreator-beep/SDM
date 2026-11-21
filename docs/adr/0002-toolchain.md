# ADR-0002: Toolchain and SDK levels

Status: accepted (2026-11-18)

- AGP 9.5.1 with built-in Kotlin, Gradle 9.6.0, Kotlin 2.4.21, KSP 2.3.14, Hilt 2.60.1, OkHttp 5.5.1.
- `compileSdk 37` (required by OkHttp 5.5's Android artifact), `targetSdk 36` (Google Play requirement for new apps since 31 Aug 2026), `minSdk 26`.
- AndroidX libraries are pinned to versions verified to build together (see `gradle/libs.versions.toml`); upgrading them is a Phase 6 maintenance task.
- detekt was not added: its current release cannot run alongside Kotlin 2.4. Android Lint runs on every CI build instead.
