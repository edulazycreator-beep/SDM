# Architecture

```
app (Compose UI, navigation, feature.* packages)
 └─ core:data (repositories, DownloadCoordinator, storage, leases, notifications)
     ├─ core:database (Room)          ├─ download-engine (pure JVM, OkHttp)
     ├─ core:designsystem (Compose)   └─ domain (pure JVM) ── core:common (pure JVM)
```

## Download flow
1. A `NewDownload` is created from the add dialog, browser, share sheet or import. Rules and
   duplicate checks run first (`RuleEngine.onEnqueue`, `DuplicateDetector`).
2. The row is persisted as `QUEUED`; cookies/headers go to `SecretStore` (Keystore AES-GCM).
3. `DownloadCoordinator.dispatch()` orders the queue (`QueueIntelligence`), applies hold reasons
   (network policy, schedule, start time, storage, rules) and starts up to the concurrency limit.
4. The run creates or reopens the output (`OutputStore`), loads the checkpoint, and calls
   `DownloadEngine.run()`. Live progress is in memory (`StateFlow`); checkpoints (flush, then
   persist segment offsets) every 2 s.
5. The result is mapped to `COMPLETED` (file published, indexed, analytics recorded), `RETRYING`
   (retryable error, backoff or Retry-After), `WAITING_FOR_NETWORK` or `FAILED` with an `ErrorCode`.
6. `ExecutionLeaseManager` keeps the process alive while anything runs (ADR-0003).

## Engine
- Probe: `GET Range: bytes=0-`. A valid 206 means ranges work and the response becomes segment 0.
- Segments: equal plan (≥ minimum size, ≤ connections), work stealing splits the largest
  remaining range when a worker is free. Every ranged response is validated (status, Content-Range
  start/end/total, encoding); any mismatch switches to a single connection from byte 0.
- Resume: size and validators must match; without validators the last 64 KiB are re-downloaded and
  compared. Changed resources restart.
- Errors: 429/503 on extra connections drop that connection instead of failing; permanent statuses
  fail without retry; storage errors are never confused with network errors.

## State machine
Ten states (spec 6.3) and a pure transition table (`StateMachine`), persisted with compare-and-set.
Metadata updates use a transaction that preserves the current state.
