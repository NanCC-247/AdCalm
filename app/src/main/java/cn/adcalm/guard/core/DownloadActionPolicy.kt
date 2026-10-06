package cn.adcalm.guard.core

/** A previous jump never authorizes actions in observation or paused mode. */
object DownloadActionPolicy {
    fun allows(enabled: Boolean, dryRun: Boolean, optedIn: Boolean, adJumpAt: Long, now: Long): Boolean =
        enabled && !dryRun && optedIn && adJumpAt > 0 && now >= adJumpAt && now - adJumpAt <= 120_000L
}
