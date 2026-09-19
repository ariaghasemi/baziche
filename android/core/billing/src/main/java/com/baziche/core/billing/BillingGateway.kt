package com.baziche.core.billing

import android.content.Context

/**
 * Store billing port. Implementations talk to a market app (Myket, Cafe Bazaar, Google Play);
 * the [PurchaseFlow] orchestrator adds the mandatory server verification step.
 */
interface BillingGateway {
    /** False until the market SDK is present AND the device market supports billing. */
    val available: Boolean

    suspend fun queryProducts(skus: List<String>): List<ProductInfo>

    /**
     * @param host MUST be an android.app.Activity (typed as Any so pure-JVM
     * tests can implement this interface without Robolectric).
     */
    suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult

    /** Consume a one-shot purchase AFTER server verification. */
    suspend fun consume(purchaseToken: String): Boolean
}

data class ProductInfo(val sku: String, val title: String, val priceText: String)

sealed interface PurchaseResult {
    data class Success(val sku: String, val token: String, val developerPayload: String, val signature: String, val signedData: String) : PurchaseResult
    data object Cancelled : PurchaseResult
    data class Failed(val code: String, val message: String) : PurchaseResult
}

/** Honest offline fallback: every call reports unavailability. */
class NoOpBillingGateway : BillingGateway {
    override val available: Boolean = false
    override suspend fun queryProducts(skus: List<String>): List<ProductInfo> = emptyList()
    override suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult =
        PurchaseResult.Failed("BILLING_UNAVAILABLE", "Billing is not available on this build")
    override suspend fun consume(purchaseToken: String): Boolean = false
}

/**
 * Myket In-App Billing adapter.
 * Uses official Myket IAB protocol and intent `ir.mservices.market.InAppBillingService.BIND`.
 */
class MyketBillingGateway(context: Context, private val packageName: String) : BillingGateway {
    private val app = context.applicationContext

    override val available: Boolean = false

    private fun missing(): Nothing = throw UnsupportedOperationException(
        "NOT IMPLEMENTED: add the Myket Billing SDK AAR to :core:billing + merchant key (docs/MYKET_SETUP.md)",
    )

    override suspend fun queryProducts(skus: List<String>): List<ProductInfo> = missing()

    override suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult = missing()

    override suspend fun consume(purchaseToken: String): Boolean = missing()
}

/**
 * Cafe Bazaar In-App Billing adapter.
 * Follows official Cafe Bazaar Poolakey / In-App Billing protocol (`ir.cafebazaar.pardakht.InAppBillingService.BIND`).
 */
class CafeBazaarBillingGateway(context: Context, private val rsaPublicKey: String? = null) : BillingGateway {
    private val app = context.applicationContext

    override val available: Boolean = false

    private fun missing(): Nothing = throw UnsupportedOperationException(
        "NOT IMPLEMENTED: add Cafe Bazaar Poolakey / Bazaar AIDL setup and configure merchant RSA key",
    )

    override suspend fun queryProducts(skus: List<String>): List<ProductInfo> = missing()

    override suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult = missing()

    override suspend fun consume(purchaseToken: String): Boolean = missing()
}

/**
 * Google Play Billing adapter (future expansion).
 */
class GooglePlayBillingGateway(context: Context) : BillingGateway {
    private val app = context.applicationContext

    override val available: Boolean = false

    override suspend fun queryProducts(skus: List<String>): List<ProductInfo> = emptyList()

    override suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult =
        PurchaseResult.Failed("GOOGLE_PLAY_UNAVAILABLE", "Google Play Billing is not configured on this device")

    override suspend fun consume(purchaseToken: String): Boolean = false
}

sealed interface PurchaseOutcome {
    data class Success(val sku: String, val token: String) : PurchaseOutcome
    data object Cancelled : PurchaseOutcome
    data object Unavailable : PurchaseOutcome
    data class Failed(val code: String, val message: String) : PurchaseOutcome
}

/**
 * Purchase orchestration: gateway -> payload check -> SERVER verification
 * (POST /billing/verify) -> consume. Server rejection never grants, and
 * consume only runs after the server approves.
 */
class PurchaseFlow(
    private val gateway: BillingGateway,
    private val verify: suspend (sku: String, token: String) -> Boolean,
) {
    suspend fun buy(host: Any, sku: String, developerPayload: String): PurchaseOutcome {
        if (!gateway.available) return PurchaseOutcome.Unavailable
        return when (val r = gateway.launchPurchase(host, sku, developerPayload)) {
            is PurchaseResult.Cancelled -> PurchaseOutcome.Cancelled
            is PurchaseResult.Failed -> PurchaseOutcome.Failed(r.code, r.message)
            is PurchaseResult.Success -> {
                if (r.sku != sku) return PurchaseOutcome.Failed("SKU_MISMATCH", "Market returned a different SKU")
                if (r.developerPayload != developerPayload) {
                    return PurchaseOutcome.Failed("PAYLOAD_MISMATCH", "Developer payload mismatch (replay attempt?)")
                }
                if (!verify(r.sku, r.token)) return PurchaseOutcome.Failed("SERVER_REJECTED", "Server did not approve this purchase")
                gateway.consume(r.token)
                PurchaseOutcome.Success(r.sku, r.token)
            }
        }
    }
}

// ---------- Section 23: BAZICHE App Subscriptions (com.baziche.app) ----------
enum class BazicheSubscriptionPlan(
    val id: String,
    val sku: String,
    val titleFa: String,
    val priceToman: Long,
    val days: Int,
) {
    FREE("FREE", "baziche_free", "رایگان", 0L, 0),
    MONTHLY("MONTHLY", "baziche_monthly", "ماهانه", 300_000L, 30),
    QUARTERLY("QUARTERLY", "baziche_quarterly", "سه‌ماهه", 500_000L, 90),
    YEARLY("YEARLY", "baziche_yearly", "سالانه", 1_000_000L, 365),
}

// ---------- Section 25: User Exported Game Monetization (e.g. com.example.zombie) ----------
enum class GameProductType {
    CONSUMABLE,       // e.g. coins, gems, revives
    NON_CONSUMABLE,   // e.g. remove ads, unlock level
    SUBSCRIPTION,     // e.g. VIP club
}

data class GameMonetizationItem(
    val sku: String,
    val name: String,
    val description: String = "",
    val type: GameProductType = GameProductType.CONSUMABLE,
    val priceToman: Long = 10_000L,
    val actionType: String = "add_coins", // add_coins, remove_ads, unlock_level, custom_event
    val actionPayload: String = "100",
)
