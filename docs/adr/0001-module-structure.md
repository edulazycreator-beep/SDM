# ADR-0001: Module structure

Status: accepted (2026-11-18)

## Decision
Eight Gradle modules instead of the ~25 sketched in spec section 30:

| Module | Type | Contents |
| --- | --- | --- |
| `:core:common` | JVM | Redactor, file names, MIME types, categories, URL validation, formatting |
| `:domain` | JVM | State machine, models, error catalogue, rule engine, schedule policy, speed/queue intelligence, import/export codecs, duplicate detection |
| `:download-engine` | JVM | Segmented HTTP engine (packages `manager`, `connection`, `segment`, `validator`, `storage`) |
| `:testing:testserver` | JVM | Programmable HTTP server for engine tests |
| `:core:database` | Android | Room entities, DAOs, exported schema |
| `:core:designsystem` | Android | Theme, tokens, components, Canvas charts |
| `:core:data` | Android | Repositories, coordinator, storage sinks, execution leases, notifications |
| `:app` | Android | Activity, navigation, feature screens (`feature.*` packages) |

## Why
The project is built only in CI (no local Android SDK in the authoring environment). Fewer
modules means fewer build scripts to get right and faster configuration. The boundaries that
matter most are still enforced by the build: the domain and engine are pure JVM modules (no
Android API can be imported), and `checkModuleDependencies` fails the build on forbidden edges.

## Consequences
Feature screens live in packages of `:app` rather than separate modules, so feature-to-feature
isolation is by convention. Splitting them out later is mechanical.
