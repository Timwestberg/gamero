package org.formulagen.wrapper.bridge

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * Notifications the device schedules itself, with no server and no push. Mirrors `NOTIFICATION_METHODS` in
 * event-contracts. Only the iPhone has them; the Mac and Android keep their notices in the page.
 */
interface NotificationsCapability {
    suspend fun status(): NotificationStatus

    /** Shows the system prompt only while the answer is `undetermined`. After an answer it only reports it. */
    suspend fun request(): NotificationStatus

    /** Makes the pending set of [NotificationSync.scope] exactly its items. Never prompts. */
    suspend fun sync(args: NotificationSync): NotificationSynced
}

/** Who owns a set of pending notifications. A sync replaces one scope's whole set and leaves the others alone. */
@Serializable
enum class NotificationScope(val wireName: String) {
    @SerialName("lists.focus") LISTS_FOCUS("lists.focus"),
    @SerialName("lists.reminders") LISTS_REMINDERS("lists.reminders"),
}

@Serializable
enum class NotificationPermission {
    @SerialName("granted") GRANTED,
    @SerialName("denied") DENIED,
    @SerialName("undetermined") UNDETERMINED,
}

/** One notification shown at [fireAtMs] (Unix milliseconds). [body] is the contract's optional key: absent, never `null`. */
@Serializable data class LocalNotification(val id: String, val title: String, val fireAtMs: Long, val body: String = "")
@Serializable data class NotificationStatus(val permission: NotificationPermission)
@Serializable data class NotificationSync(val scope: NotificationScope, val items: List<LocalNotification>)
/** [scheduled] counts the scope's requests now pending: none without permission, and none whose time has passed. */
@Serializable data class NotificationSynced(val permission: NotificationPermission, val scheduled: Int)

/** `NOTIFICATION_LIMITS` in event-contracts. iOS keeps at most 64 pending requests per app: two scopes of 32. */
object NotificationLimits {
    const val BODY = 240
    const val ID = 96
    const val ITEMS = 32
    const val TITLE = 120
}

object NotificationMethods {
    private val ID = Regex("[A-Za-z0-9._:-]+")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    val STATUS = BridgeMethod("notifications.status", NoArgs.serializer(), NotificationStatus.serializer())
    /** The system prompt stays up until the person answers it, so this call may run for minutes. */
    val REQUEST = BridgeMethod("notifications.request", NoArgs.serializer(), NotificationStatus.serializer(), timeout = 5.minutes)
    val SYNC = BridgeMethod("notifications.sync", NotificationSync.serializer(), NotificationSynced.serializer()) { sync ->
        sync.items.size <= NotificationLimits.ITEMS && sync.items.all { item ->
            item.id.length <= NotificationLimits.ID && ID.matches(item.id) &&
                item.title.length in 1..NotificationLimits.TITLE && item.body.length <= NotificationLimits.BODY &&
                item.fireAtMs in 1..MAX_SAFE_INTEGER
        }
    }

    fun pack(input: Capability<NotificationsCapability>): CapabilityPack = binder.pack(input)
    internal val binder = CapabilityBinder("notifications", listOf<CapabilityHandler<NotificationsCapability>>(
        REQUEST.handle { input, _ -> input.request() },
        STATUS.handle { input, _ -> input.status() },
        SYNC.handle { input, args -> input.sync(args) },
    ))
}

/** How a host names its requests and decides what to schedule. No platform types, so every host and test agrees. */
object NotificationPlan {
    /**
     * The request identifier of [id] in [scope]: `lists.reminders/reminder:1`. Neither a scope nor an id has a
     * slash, so one scope's prefix never matches another scope's requests.
     */
    fun identifier(scope: NotificationScope, id: String): String = "${scope.wireName}/$id"

    /** True when [identifier] is one of [scope]'s requests. */
    fun owns(scope: NotificationScope, identifier: String): Boolean = identifier.startsWith("${scope.wireName}/")

    /** The items worth scheduling at [nowMs]: still in the future, one for each id (the last one wins), soonest first. */
    fun due(items: List<LocalNotification>, nowMs: Long): List<LocalNotification> =
        items.filter { it.fireAtMs > nowMs }.associateBy { it.id }.values.sortedBy { it.fireAtMs }

    /** The pending requests of [scope] that [due] no longer names. A request that [due] names is replaced in place. */
    fun stale(scope: NotificationScope, pending: Collection<String>, due: List<LocalNotification>): List<String> {
        val kept = due.mapTo(mutableSetOf()) { identifier(scope, it.id) }
        return pending.filter { owns(scope, it) && it !in kept }
    }

    /** Seconds from [nowMs] until [fireAtMs], never below [MIN_DELAY_SECONDS]: a time trigger must lie in the future. */
    fun delaySeconds(fireAtMs: Long, nowMs: Long): Double = maxOf((fireAtMs - nowMs) / 1000.0, MIN_DELAY_SECONDS)

    const val MIN_DELAY_SECONDS = 1.0
}

/** One request as a platform's notification centre takes it. [identifier] and [thread] come from [NotificationPlan]. */
internal data class ScheduledNotification(val identifier: String, val thread: String, val title: String, val body: String, val fireAtMs: Long)

/** What a platform's notification centre does for [LocalNotifications]. Each call may suspend on the system. */
internal interface NotificationCenterPort {
    suspend fun permission(): NotificationPermission
    /** Shows the system prompt and returns once it is answered. Called only while the answer is undetermined. */
    suspend fun prompt()
    /** Identifiers of every request still waiting for its time. */
    suspend fun pending(): List<String>
    /** Identifiers of every notification already shown and still listed by the system. */
    suspend fun delivered(): List<String>
    suspend fun removePending(identifiers: List<String>)
    suspend fun removeDelivered(identifiers: List<String>)
    /** Adds [request], replacing a pending request with the same identifier. False when the system refused it. */
    suspend fun schedule(request: ScheduledNotification): Boolean
}

/**
 * The notification rules over a platform's centre. A sync removes the scope's requests the page no longer names and
 * what the system already showed for it, then adds or replaces the rest, so a sync that is cut short never leaves
 * the scope emptier than the page asked for. Calls run one at a time, and a sync finishes even when its page goes
 * away, because the next page may not open before the reminder is due.
 */
internal class LocalNotifications(private val center: NotificationCenterPort, private val nowMs: () -> Long) : NotificationsCapability {
    private val mutex = Mutex()

    override suspend fun status(): NotificationStatus = NotificationStatus(center.permission())

    /** Not serialized with [sync]: the prompt may stay on screen for minutes and schedules nothing. */
    override suspend fun request(): NotificationStatus {
        if (center.permission() == NotificationPermission.UNDETERMINED) center.prompt()
        return NotificationStatus(center.permission())
    }

    override suspend fun sync(args: NotificationSync): NotificationSynced = withContext(NonCancellable) {
        mutex.withLock {
            val scope = args.scope
            val permission = center.permission()
            val due = if (permission == NotificationPermission.GRANTED) NotificationPlan.due(args.items, nowMs()) else emptyList()
            center.removePending(NotificationPlan.stale(scope, center.pending(), due))
            center.removeDelivered(center.delivered().filter { NotificationPlan.owns(scope, it) })
            for (item in due) {
                center.schedule(ScheduledNotification(NotificationPlan.identifier(scope, item.id), scope.wireName, item.title, item.body, item.fireAtMs))
            }
            NotificationSynced(permission, center.pending().count { NotificationPlan.owns(scope, it) })
        }
    }

    /**
     * Removes every scheduled and shown notification, whatever its scope. The account locked or changed, and a
     * signed-out phone must not show the last account's reminders.
     */
    suspend fun clear() = withContext(NonCancellable) {
        mutex.withLock {
            center.removePending(center.pending())
            center.removeDelivered(center.delivered())
        }
    }
}

/**
 * This Lists store with [forget] run when the account locks, and when another account unlocks it, so nothing the
 * last account scheduled outlives it. [forget] runs even when the lock itself fails.
 */
internal fun ListsLocalCapability.forgettingOnLock(forget: suspend () -> Unit): ListsLocalCapability {
    val store = this
    return object : ListsLocalCapability by store {
        override suspend fun lock() {
            try { store.lock() } finally { forget() }
        }

        override suspend fun authorize(proof: String): ListsLocalOwner {
            val before = runCatching { store.account().ownerId.value }.getOrNull()
            val owner = store.authorize(proof)
            if (before != owner.ownerId) forget()
            return owner
        }
    }
}
