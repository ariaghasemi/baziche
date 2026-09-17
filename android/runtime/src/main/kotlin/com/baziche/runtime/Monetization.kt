package com.baziche.runtime

/**
 * Engine -> host monetization requests. The engine never touches billing SDKs;
 * the host (app / game-shell) implements this with a [BillingGateway]-style
 * backend and reports results back via [GameEngine.onRewardedAdResult] /
 * [GameEngine.onPurchaseResult]. Purchases are always verified server-side
 * (POST /billing/verify) — the APK's word alone grants nothing.
 */
interface MonetizationSink {
    /** CAP-0029: user (game logic) requests a purchase. */
    fun onPurchaseRequested(sku: String, developerPayload: String)

    /** CAP-0028: game logic requests a rewarded ad. */
    fun onRewardedAdRequested(placement: String)
}

/** Default sink: monetization requests are silently ignored (offline-safe). */
object NoOpMonetization : MonetizationSink {
    override fun onPurchaseRequested(sku: String, developerPayload: String) = Unit
    override fun onRewardedAdRequested(placement: String) = Unit
}
