package com.baziche.core.billing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeGateway(
    override val available: Boolean = true,
    var result: PurchaseResult = PurchaseResult.Success("build_single", "tok1", "u1", "sig", "{}"),
) : BillingGateway {
    val consumed = mutableListOf<String>()
    override suspend fun queryProducts(skus: List<String>): List<ProductInfo> =
        skus.map { ProductInfo(it, it, "1000") }
    override suspend fun launchPurchase(host: Any, sku: String, developerPayload: String): PurchaseResult = result
    override suspend fun consume(purchaseToken: String): Boolean {
        consumed.add(purchaseToken)
        return true
    }
}

class BillingTest {
    @Test
    fun noop_is_honestly_unavailable() = runTest {
        val g = NoOpBillingGateway()
        assertFalse(g.available)
        assertTrue(g.queryProducts(listOf("x")).isEmpty())
        val r = g.launchPurchase(Any(), "x", "") as PurchaseResult.Failed
        assertEquals("BILLING_UNAVAILABLE", r.code)
        assertEquals(PurchaseOutcome.Unavailable, PurchaseFlow(g) { _, _ -> true }.buy(Any(), "x", ""))
    }

    @Test
    fun happy_path_verifies_then_consumes() = runTest {
        val g = FakeGateway()
        var verified: Pair<String, String>? = null
        val flow = PurchaseFlow(g) { sku, token -> verified = sku to token; true }
        val out = flow.buy(Any(), "build_single", "u1")
        assertEquals(PurchaseOutcome.Success("build_single", "tok1"), out)
        assertEquals("build_single" to "tok1", verified)
        assertEquals(listOf("tok1"), g.consumed)
    }

    @Test
    fun server_rejection_grants_nothing_and_consumes_nothing() = runTest {
        val g = FakeGateway()
        val flow = PurchaseFlow(g) { _, _ -> false }
        val out = flow.buy(Any(), "build_single", "u1") as PurchaseOutcome.Failed
        assertEquals("SERVER_REJECTED", out.code)
        assertTrue(g.consumed.isEmpty())
    }

    @Test
    fun payload_and_sku_mismatch_are_rejected() = runTest {
        val g = FakeGateway(result = PurchaseResult.Success("build_single", "tok1", "EVIL", "sig", "{}"))
        val flow = PurchaseFlow(g) { _, _ -> true }
        assertEquals("PAYLOAD_MISMATCH", (flow.buy(Any(), "build_single", "u1") as PurchaseOutcome.Failed).code)
        val g2 = FakeGateway(result = PurchaseResult.Success("other", "tok1", "u1", "sig", "{}"))
        assertEquals("SKU_MISMATCH", (PurchaseFlow(g2) { _, _ -> true }.buy(Any(), "build_single", "u1") as PurchaseOutcome.Failed).code)
    }

    @Test
    fun cancelled_and_failed_pass_through() = runTest {
        val c = FakeGateway(result = PurchaseResult.Cancelled)
        assertEquals(PurchaseOutcome.Cancelled, PurchaseFlow(c) { _, _ -> true }.buy(Any(), "x", ""))
        val f = FakeGateway(result = PurchaseResult.Failed("E", "m"))
        assertEquals(PurchaseOutcome.Failed("E", "m"), PurchaseFlow(f) { _, _ -> true }.buy(Any(), "x", ""))
    }
}
