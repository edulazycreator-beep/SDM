# ADR-0004: Ten states plus hold reasons

Status: accepted (2026-11-18)

Spec section 6.3 defines exactly ten persisted states. Reasons a queued download is not starting
(schedule, metered network, rule, storage, start time) are stored as `holdReason` on QUEUED rows
instead of adding states. Every transition goes through the pure `StateMachine` and is persisted
with compare-and-set (`UPDATE … WHERE id = ? AND state = ?`), so concurrent writers cannot both win.
Downloads found in an active state at start-up were interrupted by process death and are moved to
QUEUED (or PAUSED when auto-resume is off).
