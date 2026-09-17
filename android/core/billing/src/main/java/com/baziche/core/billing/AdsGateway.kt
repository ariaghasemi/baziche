package com.baziche.core.billing

/**
 * Rewarded/interstitial ads port — RESERVED interface, no implementation yet.
 * An implementation (Tapsell, AdMob, ...) arrives when an ad network is chosen;
 * until then [available] is false everywhere and CAP-0028 requests go to the
 * host's [com.baziche.runtime.MonetizationSink] (default: silent no-op).
 */
interface AdsGateway {
    val available: Boolean

    /**
     * @param host the host Activity (typed as Any for JVM-testability).
     * @return whether the user earned the reward.
     */
    suspend fun showRewarded(host: Any, placement: String): AdShowResult

    enum class AdShowResult { EARNED, SKIPPED, FAILED, UNAVAILABLE }
}
