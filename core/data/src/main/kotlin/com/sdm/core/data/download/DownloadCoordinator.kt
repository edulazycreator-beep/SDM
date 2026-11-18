package com.sdm.core.data.download

import com.sdm.core.common.Redactor
import com.sdm.core.common.Urls
import com.sdm.core.data.analytics.AnalyticsRecorder
import com.sdm.core.data.device.BatteryMonitor
import com.sdm.core.data.device.NetworkMonitor
import com.sdm.core.data.device.NetworkState
import com.sdm.core.data.device.StorageInfo
import com.sdm.core.data.di.AppScope
import com.sdm.core.data.execution.ExecutionLeaseManager
import com.sdm.core.data.execution.WakeupScheduler
import com.sdm.core.data.notifications.Notifier
import com.sdm.core.data.rules.QueueRepository
import com.sdm.core.data.rules.RulesRepository
import com.sdm.core.data.security.SecretStore
import com.sdm.core.data.settings.AppSettings
import com.sdm.core.data.settings.SettingsRepository
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.DownloadEntity
import com.sdm.core.database.EventEntity
import com.sdm.core.database.FileIndexEntity
import com.sdm.core.database.SdmDatabase
import com.sdm.core.database.SegmentEntity
import com.sdm.domain.DownloadEvent
import com.sdm.domain.DownloadState
import com.sdm.domain.ErrorCode
import com.sdm.domain.EventType
import com.sdm.domain.HoldReason
import com.sdm.domain.LiveProgress
import com.sdm.domain.NetworkPolicy
import com.sdm.domain.RangeSupport
import com.sdm.domain.SegmentProgressView
import com.sdm.domain.StateMachine
import com.sdm.domain.intelligence.QueueIntelligence
import com.sdm.domain.intelligence.QueueItem
import com.sdm.domain.intelligence.SpeedIntelligence
import com.sdm.domain.rules.EnvironmentCommand
import com.sdm.domain.rules.EnvironmentFacts
import com.sdm.domain.rules.RuleEngine
import com.sdm.domain.scheduler.Conditions
import com.sdm.domain.scheduler.SchedulePolicy
import com.sdm.domain.scheduler.ScheduleVerdict
import com.sdm.engine.Checkpoint
import com.sdm.engine.EngineEventType
import com.sdm.engine.ErrorKind
import com.sdm.engine.ExpectedChecksum
import com.sdm.engine.ProbeInfo
import com.sdm.engine.SegmentState
import com.sdm.engine.TransferError
import com.sdm.engine.TransferListener
import com.sdm.engine.TransferRequest
import com.sdm.engine.TransferResult
import com.sdm.engine.Validators
import com.sdm.engine.connection.RateLimiter
import com.sdm.engine.manager.DownloadEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Why a running transfer was stopped; decides the state it lands in. */
enum class StopReason { USER_PAUSE, USER_CANCEL, NETWORK_LOST, POLICY, RULE, SCHEDULE, LEASE_REVOKED, RESTART, STORAGE }

/**
 * Owns every running download: queue dispatch, state transitions (through [StateMachine] with
 * compare-and-set persistence), engine runs, retries, network and rule reactions, notifications,
 * analytics and the execution lease that keeps the process alive.
 */
@Singleton
class DownloadCoordinator @Inject constructor(
    private val db: SdmDatabase,
    private val engine: DownloadEngine,
    private val outputs: OutputStore,
    private val secrets: SecretStore,
    private val settingsRepo: SettingsRepository,
    private val network: NetworkMonitor,
    private val battery: BatteryMonitor,
    private val storage: StorageInfo,
    private val notifier: Notifier,
    private val lease: ExecutionLeaseManager,
    private val wakeups: WakeupScheduler,
    private val analytics: AnalyticsRecorder,
    private val rules: RulesRepository,
    private val queues: QueueRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private class Running(val job: Job, val limiter: RateLimiter) { @Volatile var stopReason: StopReason? = null }

    private val dao = db.downloads()
    private val running = ConcurrentHashMap<Long, Running>()
    private val _live = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())
    val live: StateFlow<Map<Long, LiveProgress>> = _live.asStateFlow()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private val dispatchMutex = Mutex()
    @Volatile private var settings = AppSettings()
    @Volatile private var pausedByRule = false
    private val notifiedRuleMessages = ConcurrentHashMap.newKeySet<String>()

    val activeCount: Int get() = running.size
    val totalSpeedBps: Long get() = _live.value.values.sumOf { it.speedBps }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            settings = settingsRepo.current()
            rules.ensureDefaults()
            recover()
            for (ignored in signal) runCatching { dispatch() }
        }
        scope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                engine.globalLimiter.bytesPerSecond = s.globalLimitBps
                signal()
            }
        }
        scope.launch { network.state.collect { onNetwork(it) } }
        scope.launch {
            combine(network.state, battery.state, rules.rules) { n, b, r -> Triple(n, b, r) }.collect { (n, b, r) ->
                val facts = EnvironmentFacts(n.connected, n.connected && !n.unmetered, b.percent, b.charging, storage.primary().freeBytes)
                val decision = RuleEngine.onEnvironment(r, facts)
                when (decision.command) {
                    EnvironmentCommand.PAUSE_ALL -> if (!pausedByRule) { pausedByRule = true; stopAll(StopReason.RULE); event(null, EventType.RULE_APPLIED, decision.appliedRules.joinToString()) }
                    EnvironmentCommand.RESUME_ALL -> if (pausedByRule) { pausedByRule = false; signal() }
                    null -> if (pausedByRule && decision.appliedRules.isEmpty()) { pausedByRule = false; signal() }
                }
                decision.notifications.forEach { msg -> if (notifiedRuleMessages.add(msg)) notifier.info(msg.hashCode() and 0xffff, "SDM rule", msg) }
                if (decision.notifications.isEmpty()) notifiedRuleMessages.clear()
            }
        }
        scope.launch {
            while (true) {
                delay(15_000)
                signal()
                analytics.prune()
            }
        }
    }

    fun signal() { signal.trySend(Unit) }

    // ------------------------------------------------------------------ recovery & dispatch

    /** Downloads left in an active state by a killed process are interrupted, never lost (spec 6.4). */
    private suspend fun recover() {
        val stale = dao.inStates(StateMachine.INTERRUPTIBLE.toList())
        for (d in stale) {
            if (running.containsKey(d.id)) continue
            val ok = transition(d.id, DownloadEvent.INTERRUPTED)
            if (ok != null) {
                event(d.id, EventType.LEASE_REVOKED, "Recovered after the app was stopped while ${d.state.name.lowercase()}")
                if (!settings.autoResumeOnStart) transition(d.id, DownloadEvent.PAUSE)
            }
        }
    }

    private suspend fun dispatch() = dispatchMutex.withLock {
        val now = System.currentTimeMillis()
        val net = network.state.value
        val queueConfigs = queues.all().associateBy { it.id }
        val conditions = Conditions(net.connected, net.connected && net.unmetered, battery.state.value.charging, battery.state.value.percent)

        // Retry timers that are due.
        dao.inStates(listOf(DownloadState.RETRYING)).filter { (it.nextRetryAt ?: 0) <= now && !running.containsKey(it.id) }.forEach { d ->
            if (net.connected && running.size < settings.maxConcurrent) launchRun(d.id, DownloadEvent.RETRY_NOW)
        }
        // Network came back.
        if (net.connected) dao.inStates(listOf(DownloadState.WAITING_FOR_NETWORK)).forEach { transition(it.id, DownloadEvent.NETWORK_RESTORED) }

        val queued = dao.inStates(listOf(DownloadState.QUEUED)).filter { !running.containsKey(it.id) }
        val ordered = queued.groupBy { it.queueId }.flatMap { (qid, items) ->
            val ordering = queueConfigs[qid]?.ordering ?: com.sdm.domain.intelligence.QueueOrdering.MANUAL
            QueueIntelligence.order(items.map { QueueItem(it.id, it.priority.weight, it.queuePosition, it.totalBytes?.minus(it.downloadedBytes), it.createdAt, it.important) }, ordering)
                .mapNotNull { qi -> items.firstOrNull { it.id == qi.id } }
        }.sortedByDescending { it.important }

        var nextWake: Long? = null
        for (d in ordered) {
            val q = queueConfigs[d.queueId]
            val verdict = q?.schedule?.let { SchedulePolicy.evaluate(it, now, conditions) } ?: ScheduleVerdict.Allowed
            val startAt = d.scheduledAt
            val remaining = d.totalBytes?.let { it - d.downloadedBytes }
            val hold = when {
                !net.connected -> { transition(d.id, DownloadEvent.NETWORK_LOST); continue }
                pausedByRule -> HoldReason.RULE
                q?.paused == true -> HoldReason.SCHEDULE
                startAt != null && startAt > now -> { nextWake = minOf(nextWake ?: Long.MAX_VALUE, startAt); HoldReason.START_TIME }
                !policyAllows(d.networkPolicy, net) || !policyAllows(settings.networkPolicy, net) -> HoldReason.METERED_NETWORK
                verdict is ScheduleVerdict.Blocked -> { verdict.nextWindowStart?.let { nextWake = minOf(nextWake ?: Long.MAX_VALUE, it) }; HoldReason.SCHEDULE }
                storage.primary().let { s -> remaining != null && s.totalBytes > 0 && remaining > s.freeBytes } -> HoldReason.STORAGE
                else -> HoldReason.NONE
            }
            if (d.holdReason != hold) dao.setHold(d.id, hold)
            if (hold != HoldReason.NONE) continue
            if (running.size >= settings.maxConcurrent) break
            launchRun(d.id, DownloadEvent.START)
        }
        dao.inStates(listOf(DownloadState.RETRYING)).mapNotNull { it.nextRetryAt }.minOrNull()?.let { nextWake = minOf(nextWake ?: Long.MAX_VALUE, it) }
        wakeups.scheduleAt(nextWake, settings.exactAlarms)
        updateLease()
    }

    private fun policyAllows(policy: NetworkPolicy, net: NetworkState) = when (policy) {
        NetworkPolicy.ANY -> true
        NetworkPolicy.UNMETERED_ONLY -> net.unmetered
        NetworkPolicy.WIFI_ONLY -> net.wifi && net.unmetered
    }

    private suspend fun launchRun(id: Long, event: DownloadEvent) {
        if (running.containsKey(id)) return
        if (transition(id, event) != DownloadState.PREPARING) return
        val d = dao.get(id) ?: return
        val limiter = RateLimiter(d.speedLimitBps)
        val job = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { runDownload(id) }
        running[id] = Running(job, limiter)
        job.invokeOnCompletion {
            running.remove(id)
            _live.update { it - id }
            signal()
            scope.launch { updateLease() }
        }
        job.start()
        updateLease()
    }

    private suspend fun updateLease() {
        lease.update(running.size, totalSpeedBps)
    }

    // ------------------------------------------------------------------ the run

    private suspend fun runDownload(id: Long) {
        var entity = dao.get(id) ?: return
        val run = running[id] ?: return
        val sink = try {
            entity = ensureOutput(entity)
            outputs.open(entity.destinationUri!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            // The partial file vanished (deleted by the user or expired): start over in a new file.
            db.segments().clear(id)
            entity = entity.copy(destinationUri = null, downloadedBytes = 0, rangeSupport = RangeSupport.UNKNOWN, etag = null, lastModified = null)
            dao.update(entity.copy(updatedAt = now()))
            event(id, EventType.RESTARTED, "Partial file missing; restarting")
            try { entity = ensureOutput(entity); outputs.open(entity.destinationUri!!) } catch (e2: Exception) { fail(entity, ErrorCode.STORAGE_ERROR, e2.message, null); return }
        } catch (e: Exception) {
            fail(entity, ErrorCode.STORAGE_ERROR, e.message, null); return
        }

        val headers = if (entity.hasSecrets) secrets.get(id) else emptyMap()
        val checkpoint = loadCheckpoint(entity)
        val connections = chooseConnections(entity)
        val profile = settings.speedProfile
        val algorithm = entity.checksumAlgorithm
        val expected = entity.checksumExpected
        val request = TransferRequest(
            url = entity.originalUrl,
            headers = headers,
            maxConnections = connections,
            minSegmentBytes = profile.minSegmentBytes,
            checkpoint = checkpoint,
            expectedChecksum = if (algorithm != null && !expected.isNullOrBlank()) ExpectedChecksum(algorithm, expected) else null,
            segmentRetries = profile.maxRetries.coerceAtMost(settings.retryMaxAttempts.coerceAtLeast(1)),
        )
        val listener = RunListener(id, run)
        val result = try {
            engine.run(request, sink, run.limiter, listener)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { onStopped(id, run.stopReason ?: StopReason.LEASE_REVOKED) }
            throw e
        } finally {
            runCatching { sink.close() }
        }
        withContext(NonCancellable) {
            when (result) {
                is TransferResult.Completed -> onCompleted(id, result)
                is TransferResult.Failed -> onFailed(id, result)
            }
        }
    }

    private suspend fun ensureOutput(e: DownloadEntity): DownloadEntity {
        if (e.destinationUri != null) return e
        val handle = outputs.create(e.fileName, e.mimeType, e.category, e.destinationType, e.destinationTreeUri)
        val updated = e.copy(destinationUri = handle.uri, fileName = handle.displayName, destinationLabel = handle.label, updatedAt = now())
        dao.update(updated)
        return updated
    }

    private suspend fun loadCheckpoint(e: DownloadEntity): Checkpoint? {
        if (e.rangeSupport != RangeSupport.SUPPORTED || e.totalBytes == null) return null
        val segs = db.segments().forDownload(e.id)
        if (segs.isEmpty()) return null
        return Checkpoint(
            totalBytes = e.totalBytes,
            rangeSupported = true,
            validators = Validators(e.etag, e.lastModified),
            resolvedUrl = e.resolvedUrl ?: e.originalUrl,
            segments = segs.map { SegmentState(it.segmentIndex, it.startByte, it.endByte, it.checkpointByte) },
        )
    }

    private suspend fun chooseConnections(e: DownloadEntity): Int {
        if (!settings.autoConnections) return e.maxConnections.coerceIn(1, 16)
        val rec = SpeedIntelligence.recommend(Urls.host(e.originalUrl), analytics.observations(), userMax = e.maxConnections, fallback = settings.speedProfile)
        if (rec.connections != e.maxConnections) event(e.id, EventType.RULE_APPLIED, "Speed Intelligence: ${rec.connections} connection(s). ${rec.basis}")
        return rec.connections
    }

    private inner class RunListener(private val id: Long, private val run: Running) : TransferListener {
        private var lastSampleAt = System.currentTimeMillis()
        private var lastSampleBytes = -1L

        override fun onProbed(info: ProbeInfo) {
            scope.launch {
                val e = dao.get(id) ?: return@launch
                val free = storage.primary()
                val needed = (info.totalBytes ?: 0) - e.downloadedBytes
                if (info.totalBytes != null && free.totalBytes > 0 && needed > free.freeBytes) {
                    run.stopReason = StopReason.STORAGE
                    run.job.cancel()
                    return@launch
                }
                dao.update(e.copy(
                    resolvedUrl = info.finalUrl, totalBytes = info.totalBytes ?: e.totalBytes,
                    mimeType = e.mimeType ?: info.contentType?.substringBefore(';'),
                    rangeSupport = if (info.rangeSupported) RangeSupport.SUPPORTED else RangeSupport.UNSUPPORTED,
                    etag = info.validators.etag, lastModified = info.validators.lastModified,
                    startedAt = e.startedAt ?: now(), updatedAt = now(),
                ))
                if (transition(id, DownloadEvent.PROBED) == DownloadState.DOWNLOADING) event(id, EventType.STARTED, "Connected; ${if (info.rangeSupported) "range requests supported" else "single connection only"}")
            }
        }

        override fun onProgress(downloadedBytes: Long, totalBytes: Long?, speedBps: Long, activeConnections: Int, segments: List<SegmentState>) {
            val view = segments.map { SegmentProgressView(it.start, it.end, it.position, !it.done) }
            val lp = LiveProgress(downloadedBytes, totalBytes, speedBps, activeConnections, view)
            _live.update { it + (id to lp) }
            scope.launch {
                val e = dao.get(id) ?: return@launch
                if (settings.perDownloadNotifications) notifier.progress(e.toDomain(), lp)
                val now = System.currentTimeMillis()
                if (lastSampleBytes < 0) { lastSampleBytes = downloadedBytes; lastSampleAt = now }
                else if (now - lastSampleAt >= 5_000) {
                    analytics.sample(id, e.resolvedUrl ?: e.originalUrl, downloadedBytes - lastSampleBytes, now - lastSampleAt, activeConnections)
                    lastSampleBytes = downloadedBytes; lastSampleAt = now
                }
            }
        }

        override suspend fun onCheckpoint(checkpoint: Checkpoint) {
            db.segments().replace(id, checkpoint.segments.map { SegmentEntity(id, it.index, it.start, it.end, it.position) })
            dao.updateProgress(id, checkpoint.downloadedBytes, checkpoint.totalBytes, _live.value[id]?.activeConnections ?: 0, now())
        }

        override fun onEvent(type: EngineEventType, detail: String) {
            val t = when (type) {
                EngineEventType.FALLBACK_SINGLE_CONNECTION -> EventType.FALLBACK_SINGLE_CONNECTION
                EngineEventType.RESOURCE_CHANGED -> EventType.RESOURCE_CHANGED
                EngineEventType.SEGMENT_FAILED -> EventType.SEGMENT_FAILED
                EngineEventType.CONNECTIONS_REDUCED -> EventType.CONNECTIONS_REDUCED
                EngineEventType.RESTARTED_FROM_ZERO -> EventType.RESTARTED
                EngineEventType.VERIFIED -> EventType.VERIFIED
            }
            scope.launch { event(id, t, detail) }
        }

        override fun onVerifying() {
            scope.launch { transition(id, DownloadEvent.VERIFY) }
        }
    }

    // ------------------------------------------------------------------ outcomes

    private suspend fun onCompleted(id: Long, r: TransferResult.Completed) {
        var e = dao.get(id) ?: return
        val state = dao.get(id)?.state
        if (state == DownloadState.DOWNLOADING || state == DownloadState.PREPARING) {
            if (state == DownloadState.PREPARING) transition(id, DownloadEvent.PROBED)
            transition(id, DownloadEvent.VERIFY)
        }
        val finalUri = try {
            outputs.finalize(e.destinationUri!!, e.destinationType, e.destinationTreeUri, e.fileName, e.mimeType)
        } catch (ex: Exception) {
            fail(e, ErrorCode.STORAGE_ERROR, ex.message, null); return
        }
        e = e.copy(
            destinationUri = finalUri, totalBytes = r.totalBytes, downloadedBytes = r.totalBytes, checksumActual = r.checksumHex ?: e.checksumActual,
            completedAt = now(), updatedAt = now(), activeMillis = e.activeMillis + r.stats.activeMillis, errorCode = null, errorMessage = null,
            httpStatus = null, activeConnections = 0, connectionsUsed = r.stats.connectionsUsed, segmentFailures = e.segmentFailures + r.stats.segmentFailures,
            throttled = r.stats.throttled, resolvedUrl = r.finalUrl,
        )
        dao.update(e)
        if (transition(id, DownloadEvent.VERIFIED) == DownloadState.COMPLETED) {
            db.segments().clear(id)
            event(id, EventType.COMPLETED, "Completed ${com.sdm.core.common.Formatters.bytes(r.totalBytes)} with ${r.stats.connectionsUsed} connection(s)")
            analytics.session(e, r.stats, succeeded = true)
            db.files().upsert(listOf(FileIndexEntity(finalUri, e.fileName, r.totalBytes, e.mimeType, e.category, now(), e.destinationLabel, e.id, if (e.checksumAlgorithm.equals("SHA-256", true) || e.checksumAlgorithm.equals("SHA256", true)) r.checksumHex else null, null, e.destinationTreeUri)))
            notifier.completed(e.toDomain(), finalUri)
        }
    }

    private suspend fun onFailed(id: Long, r: TransferResult.Failed) {
        val e = dao.get(id) ?: return
        val err = r.error
        val code = mapError(err)
        dao.update(e.copy(activeMillis = e.activeMillis + r.stats.activeMillis, activeConnections = 0, segmentFailures = e.segmentFailures + r.stats.segmentFailures, throttled = e.throttled || r.stats.throttled, updatedAt = now()))
        analytics.session(e, r.stats, succeeded = false)
        val net = network.state.value
        if (!net.connected && (err.kind == ErrorKind.NETWORK || err.kind == ErrorKind.TIMEOUT)) {
            transition(id, DownloadEvent.NETWORK_LOST)
            event(id, EventType.NETWORK_LOST, err.message)
            return
        }
        val (retryRule, _) = RuleEngine.onFailure(rules.current())
        val maxRetries = settings.retryMaxAttempts
        if (err.retryable && retryRule && e.retryCount < maxRetries) {
            val base = 5_000L * (1L shl e.retryCount.coerceAtMost(10))
            val delayMs = (err.retryAfterMillis ?: (Random.nextDouble(0.5, 1.0) * base).toLong()).coerceAtMost(settings.retryBackoffCapSeconds * 1000L)
            dao.update(dao.get(id)!!.copy(retryCount = e.retryCount + 1, nextRetryAt = now() + delayMs, errorCode = code.name, errorMessage = Redactor.text(err.message), httpStatus = err.httpStatus, updatedAt = now()))
            transition(id, DownloadEvent.RETRY_LATER)
            event(id, EventType.RETRY_SCHEDULED, "${code.title}; retry ${e.retryCount + 1}/$maxRetries in ${delayMs / 1000}s", err.httpStatus, code)
            notifier.paused(e.toDomain(), "Retrying: ${code.title}")
            return
        }
        fail(e, code, err.message, err.httpStatus)
    }

    private suspend fun fail(e: DownloadEntity, code: ErrorCode, message: String?, status: Int?) {
        val cur = dao.get(e.id) ?: return
        dao.update(cur.copy(errorCode = code.name, errorMessage = message?.let { Redactor.text(it) }, httpStatus = status, activeConnections = 0, updatedAt = now()))
        transition(e.id, DownloadEvent.FAIL)
        event(e.id, EventType.FAILED, message ?: code.title, status, code)
        notifier.failed(cur.toDomain(), code.title, code.suggestion)
        if (cur.downloadedBytes == 0L && cur.startedAt == null) analytics.failedWithoutTransfer(cur)
    }

    private fun mapError(err: TransferError): ErrorCode = when (err.kind) {
        ErrorKind.NETWORK -> if (network.state.value.connected) ErrorCode.CONNECTION_FAILED else ErrorCode.NETWORK_UNAVAILABLE
        ErrorKind.TIMEOUT -> ErrorCode.TIMEOUT
        ErrorKind.HTTP -> ErrorCode.forHttpStatus(err.httpStatus ?: 0, err.resumed)
        ErrorKind.RANGE_MISMATCH -> ErrorCode.RANGE_MISMATCH
        ErrorKind.RESOURCE_CHANGED -> ErrorCode.RESOURCE_CHANGED
        ErrorKind.STORAGE_FULL -> ErrorCode.INSUFFICIENT_STORAGE
        ErrorKind.FILE_TOO_LARGE -> ErrorCode.FILE_TOO_LARGE
        ErrorKind.STORAGE -> ErrorCode.STORAGE_ERROR
        ErrorKind.CHECKSUM_MISMATCH -> ErrorCode.CHECKSUM_MISMATCH
        ErrorKind.INVALID_RESPONSE -> ErrorCode.INVALID_RESPONSE
    }

    /** Lands a cancelled run in the state its stop reason calls for. */
    private suspend fun onStopped(id: Long, reason: StopReason) {
        val e = dao.get(id) ?: return
        dao.update(e.copy(activeConnections = 0, updatedAt = now()))
        notifier.cancel(id)
        when (reason) {
            StopReason.USER_PAUSE -> { transition(id, DownloadEvent.PAUSE); event(id, EventType.PAUSED, "Paused by user") }
            StopReason.USER_CANCEL -> Unit // handled by cancel()
            StopReason.RESTART -> Unit // handled by restart()
            StopReason.NETWORK_LOST -> { transition(id, DownloadEvent.NETWORK_LOST); event(id, EventType.NETWORK_LOST, "Network lost") }
            StopReason.STORAGE -> {
                fail(e, ErrorCode.INSUFFICIENT_STORAGE, "Needs ${com.sdm.core.common.Formatters.bytes(e.totalBytes)}; free space is ${com.sdm.core.common.Formatters.bytes(storage.primary().freeBytes)}", null)
            }
            StopReason.POLICY, StopReason.RULE, StopReason.SCHEDULE, StopReason.LEASE_REVOKED -> {
                transition(id, DownloadEvent.INTERRUPTED)
                val hold = when (reason) { StopReason.POLICY -> HoldReason.METERED_NETWORK; StopReason.RULE -> HoldReason.RULE; StopReason.SCHEDULE -> HoldReason.SCHEDULE; else -> HoldReason.NONE }
                dao.setHold(id, hold)
                event(id, if (reason == StopReason.LEASE_REVOKED) EventType.LEASE_REVOKED else EventType.PAUSED, "Stopped: ${reason.name.lowercase().replace('_', ' ')}")
            }
        }
    }

    // ------------------------------------------------------------------ reactions

    private suspend fun onNetwork(n: NetworkState) {
        if (!n.connected) {
            stopAll(StopReason.NETWORK_LOST)
        } else {
            running.forEach { (id, r) ->
                val d = dao.get(id) ?: return@forEach
                if (!policyAllows(d.networkPolicy, n) || !policyAllows(settings.networkPolicy, n)) { r.stopReason = StopReason.POLICY; r.job.cancel() }
            }
            dao.inStates(listOf(DownloadState.WAITING_FOR_NETWORK)).forEach {
                if (transition(it.id, DownloadEvent.NETWORK_RESTORED) != null) event(it.id, EventType.NETWORK_RESTORED, n.label)
            }
        }
        signal()
    }

    private fun stopAll(reason: StopReason) {
        running.values.forEach { it.stopReason = reason; it.job.cancel() }
    }

    /** Called when Android revokes the execution lease (UIDT stopped, FGS timeout). */
    fun onLeaseRevoked(detail: String) {
        scope.launch { event(null, EventType.LEASE_REVOKED, detail) }
        stopAll(StopReason.LEASE_REVOKED)
    }

    // ------------------------------------------------------------------ user actions

    suspend fun pause(id: Long) {
        running[id]?.let { it.stopReason = StopReason.USER_PAUSE; it.job.cancel(); return }
        if (transition(id, DownloadEvent.PAUSE) != null) {
            event(id, EventType.PAUSED, "Paused by user")
            dao.get(id)?.let { notifier.paused(it.toDomain(), "Paused") }
        }
    }

    suspend fun resume(id: Long) {
        val d = dao.get(id) ?: return
        if (d.state == DownloadState.FAILED) dao.update(d.copy(retryCount = 0, nextRetryAt = null, errorCode = null, errorMessage = null, httpStatus = null, updatedAt = now()))
        if (transition(id, DownloadEvent.RESUME) != null) {
            dao.setHold(id, HoldReason.NONE)
            event(id, EventType.RESUMED, "Resumed")
            notifier.cancel(id)
        }
        signal()
    }

    suspend fun cancel(id: Long, deletePartial: Boolean = true) {
        running[id]?.let { it.stopReason = StopReason.USER_CANCEL; it.job.cancel(); it.job.join() }
        val d = dao.get(id) ?: return
        if (transition(id, DownloadEvent.CANCEL) != null) {
            if (deletePartial && d.state != DownloadState.COMPLETED) {
                d.destinationUri?.let { outputs.delete(it) }
                db.segments().clear(id)
                dao.update(dao.get(id)!!.copy(destinationUri = null, downloadedBytes = 0, updatedAt = now()))
            }
            event(id, EventType.CANCELLED, "Cancelled by user")
        }
        notifier.cancel(id)
    }

    suspend fun restart(id: Long) {
        running[id]?.let { it.stopReason = StopReason.RESTART; it.job.cancel(); it.job.join() }
        val d = dao.get(id) ?: return
        if (d.state.isActive || d.state == DownloadState.QUEUED || d.state == DownloadState.WAITING_FOR_NETWORK) transition(id, DownloadEvent.PAUSE)
        if (d.state != DownloadState.COMPLETED) d.destinationUri?.let { outputs.delete(it) }
        db.segments().clear(id)
        val fresh = dao.get(id) ?: return
        dao.update(fresh.copy(
            destinationUri = if (d.state == DownloadState.COMPLETED) null else null, downloadedBytes = 0, rangeSupport = RangeSupport.UNKNOWN,
            etag = null, lastModified = null, retryCount = 0, nextRetryAt = null, errorCode = null, errorMessage = null, httpStatus = null,
            completedAt = null, checksumActual = null, activeMillis = 0, updatedAt = now(), holdReason = HoldReason.NONE,
        ))
        if (transition(id, DownloadEvent.RESTART) != null) event(id, EventType.RESTARTED, "Restarted from the beginning")
        signal()
    }

    suspend fun delete(ids: Collection<Long>, deleteFiles: Boolean) {
        for (id in ids) {
            running[id]?.let { it.stopReason = StopReason.USER_CANCEL; it.job.cancel(); it.job.join() }
            val d = dao.get(id) ?: continue
            if (deleteFiles || d.state != DownloadState.COMPLETED) d.destinationUri?.let { outputs.delete(it); db.files().delete(listOf(it)) }
            notifier.cancel(id)
        }
        dao.delete(ids.toList())
        signal()
    }

    suspend fun setSpeedLimit(id: Long, bytesPerSecond: Long) {
        dao.get(id)?.let { dao.update(it.copy(speedLimitBps = bytesPerSecond.coerceAtLeast(0), updatedAt = now())) }
        running[id]?.limiter?.bytesPerSecond = bytesPerSecond
    }

    // ------------------------------------------------------------------ helpers

    /** Applies an event through the state machine with compare-and-set; returns the new state or null. */
    suspend fun transition(id: Long, event: DownloadEvent): DownloadState? {
        repeat(3) {
            val cur = dao.get(id) ?: return null
            val next = StateMachine.next(cur.state, event) ?: return null
            val hold = if (next == DownloadState.QUEUED) cur.holdReason else HoldReason.NONE
            if (dao.casState(id, cur.state, next, hold, now()) == 1) return next
        }
        return null
    }

    private suspend fun event(id: Long?, type: EventType, detail: String?, status: Int? = null, code: ErrorCode? = null) {
        runCatching { db.events().insert(EventEntity(downloadId = id, timestamp = now(), type = type, httpStatus = status, errorCode = code?.name, detail = detail?.let { Redactor.text(it).take(500) })) }
    }

    private fun now() = System.currentTimeMillis()
}
