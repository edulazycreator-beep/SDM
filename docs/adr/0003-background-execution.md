# ADR-0003: Background execution

Status: accepted (2026-11-18)

Downloads run in an application-scoped coroutine inside the app process. An *execution lease*
keeps the process allowed to run:

1. Android 14+: a user-initiated data transfer (UIDT) job (`JobInfo.setUserInitiated`). Built for
   user-started downloads; not subject to the 6-hour `dataSync` foreground-service budget of
   Android 15+ or the Android 16 job quota for jobs running alongside a foreground service.
2. Android 8-13, or when the job cannot be scheduled: a `dataSync` foreground service.
3. When Android forbids both (the app is in the background, e.g. a scheduled start or after a
   reboot): WorkManager windows. The worker keeps the process alive for up to ~8 minutes and asks to
   be re-run while downloads remain; progress is checkpointed so nothing is lost between windows.
   Exact alarms (if the user allows them) start scheduled downloads on time; alarms are an allowed
   way to start a foreground service.

When the lease is revoked (time limit, user stop in Task Manager) downloads checkpoint and return to
QUEUED; they continue when SDM next runs. After a reboot, Android 15+ forbids starting a `dataSync`
service from `BOOT_COMPLETED`, so recovery goes through WorkManager.
