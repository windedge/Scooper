package scooper.viewmodels

import com.scoopai.core.ToolActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Activity pump plus repair-round bookkeeping of one Create session.
 *
 * Engine callbacks arrive on arbitrary threads while the state reduce runs on the
 * Orbit container thread, so activities are queued here and drained by a single
 * collector. Activities of a superseded run are dropped twice: [offer] rejects a
 * non-current epoch, and [collect] skips anything still buffered for a non-current
 * epoch (late-event race defense of a cancelled run). The view model pushes the
 * live epoch through [setCurrentEpoch] exactly where it assigns one, so both
 * gates share the same source of truth and a buffered stale event can never slip
 * through the collector window before a new run's first offer.
 */
internal class ExecutionTimeline(
    private val formatToolActivity: (ToolActivity) -> String,
) {
    /** Unbounded so engine callbacks never block on a busy collector. */
    private val channel = Channel<Pair<ToolActivity, Long>>(Channel.UNLIMITED)

    /** Session-scoped repair round count; reset per generate/assist session. */
    private var repairRounds = 0

    /**
     * Epoch of the live engine run; 0 before the first run and never a valid
     * run epoch (the VM starts numbering at 1). Both gates pass everything
     * while it is 0 so pre-session tests stay unfiltered.
     */
    @Volatile
    private var currentEpoch = 0L

    /** Publish the epoch of the run starting now; buffered older events drop. */
    fun setCurrentEpoch(epoch: Long) {
        currentEpoch = epoch
    }

    /** Queue an activity of [epoch]; false when the epoch is already superseded. */
    fun offer(epoch: Long, activity: ToolActivity): Boolean {
        if (currentEpoch != 0L && epoch != currentEpoch) return false
        return channel.trySend(activity to epoch).isSuccess
    }

    /**
     * Deliver queued activities with their formatted line. Runs until cancelled;
     * the caller owns the scope.
     */
    suspend fun collect(onActivity: suspend (ToolActivity, String) -> Unit) {
        channel.receiveAsFlow().collect { (activity, epoch) ->
            if (currentEpoch != 0L && epoch != currentEpoch) return@collect
            onActivity(activity, formatSafely(activity))
        }
    }

    /** Repair attempt number of [phase], incremented on repair phases only. */
    fun repairAttempt(phase: String): Int {
        if (phase.equals("repair", ignoreCase = true)) {
            repairRounds++
        }
        return repairRounds
    }

    /** Restart repair numbering so the first round of a new session reads 1. */
    fun resetRounds() {
        repairRounds = 0
    }

    /**
     * Drop everything still buffered. Call before a run's transient execution is
     * collapsed into a finalized message, so late events cannot mutate it.
     */
    fun drain() = drainActivityEvents(channel)

    private fun formatSafely(activity: ToolActivity): String = try {
        formatToolActivity(activity)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Degrade to the bare tool name: a formatter bug must not stall the run.
        activity.toolName
    }
}

/**
 * Drain residual buffered activity events (late-event race defense, proposal 5.1):
 * everything still buffered when the run finishes must not leak into the next run
 * or mutate a finalized state. After this loop the channel is empty.
 */
internal fun drainActivityEvents(channel: Channel<Pair<ToolActivity, Long>>) {
    while (true) {
        val result = channel.tryReceive()
        if (result.isFailure) break
    }
}
