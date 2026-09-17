package com.baziche.core.billing

import android.content.Context

/**
 * Store billing port. Implementations talk to a market app (Myket); the
 * [PurchaseFlow] orchestrator adds the mandatory server verification step.
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
 *
 * NOT IMPLEMENTED: requires the proprietary Myket Billing SDK AAR
 * (`myketbilling`, downloaded from the Myket developer panel) + merchant
 * setup. Every method throws until the SDK is added — this class can never
 * be mistaken for a working integration. Setup: docs/MYKET_SETUP.md.
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
