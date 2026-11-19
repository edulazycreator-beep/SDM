package com.sdm.downloadmanager.premium

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.sdm.downloadmanager.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One way to buy Premium, as Google Play offers it to this user right now. */
data class PremiumPlan(
    val key: String,
    val kind: Kind,
    val price: String,
    /** "year" or "month" for subscriptions. */
    val period: String?,
    /** Free trial length when this user is eligible for it (Play only offers it to eligible users). */
    val trialDays: Int?,
    internal val details: ProductDetails,
    internal val offerToken: String?,
) {
    enum class Kind { YEARLY, MONTHLY, LIFETIME }
}

data class PremiumState(
    val unlocked: Boolean,
    /** GitHub build: everything is included and nothing is sold. */
    val includedInBuild: Boolean,
    val ownedSubscription: Boolean = false,
    val plans: List<PremiumPlan> = emptyList(),
    val loading: Boolean = false,
    val billingAvailable: Boolean = true,
    val pending: Boolean = false,
    val message: String? = null,
)

/**
 * SDM Premium through Google Play billing: a subscription ("sdm_premium", monthly and yearly base
 * plans, each with a 3-day free-trial offer set up in Play Console) and a one-time purchase
 * ("sdm_premium_lifetime"). Premium is cosmetic and supports development; no download feature is
 * locked. The last known state is kept on the device so the look stays the same offline, and it is
 * checked again with Play whenever SDM opens, so cancelled or refunded purchases end Premium.
 */
@Singleton
class PremiumRepository @Inject constructor(@ApplicationContext private val context: Context) : PurchasesUpdatedListener {
    companion object {
        const val SUBSCRIPTION = "sdm_premium"
        const val LIFETIME = "sdm_premium_lifetime"
        private val OURS = setOf(SUBSCRIPTION, LIFETIME)
    }

    private val sold = BuildConfig.PLAY_STORE
    private val prefs = context.getSharedPreferences("premium", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(PremiumState(unlocked = !sold || prefs.getBoolean("unlocked", false), includedInBuild = !sold))
    val state: StateFlow<PremiumState> = _state.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectLock = Mutex()

    private val client: BillingClient by lazy {
        BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
    }

    /** Checks purchases and loads prices; call when SDM opens and when the Premium page shows. */
    fun refresh() {
        if (!sold) return
        scope.launch {
            _state.update { it.copy(loading = true, message = null) }
            if (connect()) {
                restore(quiet = true)
                loadPlans()
            }
            _state.update { it.copy(loading = false) }
        }
    }

    fun restorePurchases() {
        if (!sold) return
        scope.launch {
            _state.update { it.copy(loading = true, message = null) }
            if (connect()) restore(quiet = false)
            _state.update { it.copy(loading = false) }
        }
    }

    fun buy(activity: Activity, plan: PremiumPlan) {
        if (!sold) return
        scope.launch {
            if (!connect()) return@launch
            val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(plan.details)
                .apply { plan.offerToken?.let { setOfferToken(it) } }.build()
            val result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build())
            if (result.responseCode != BillingResponseCode.OK) _state.update { it.copy(message = describe(result)) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { handle(purchases.orEmpty(), fromRestore = false) }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> restorePurchases()
            else -> _state.update { it.copy(message = describe(result)) }
        }
    }

    private suspend fun connect(): Boolean {
        connectLock.withLock {
        if (client.isReady) return true
        val ok = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (cont.isActive) cont.resume(result.responseCode == BillingResponseCode.OK)
                    }
                    override fun onBillingServiceDisconnected() {
                        if (cont.isActive) cont.resume(false)
                    }
                })
            }
        } ?: false
        _state.update {
            it.copy(
                billingAvailable = ok,
                message = if (ok) it.message else "Google Play billing isn't available. Install SDM from Google Play and sign in to the Play Store.",
            )
        }
        return ok
        }
    }

    private suspend fun queryPurchases(type: String): List<Purchase>? = suspendCancellableCoroutine { cont ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result, list ->
            if (cont.isActive) cont.resume(if (result.responseCode == BillingResponseCode.OK) list else null)
        }
    }

    private suspend fun restore(quiet: Boolean) {
        val subs = queryPurchases(ProductType.SUBS)
        val once = queryPurchases(ProductType.INAPP)
        if (subs == null || once == null) {
            if (!quiet) _state.update { it.copy(message = "Could not reach Google Play. Try again later.") }
            return
        }
        handle(subs + once, fromRestore = true)
        if (!quiet) _state.update { it.copy(message = if (it.unlocked) "Premium restored" else "No Premium purchase found for this Google account") }
    }

    /** Unlocks for completed purchases, acknowledges them (Play refunds unacknowledged ones after 3 days). */
    private suspend fun handle(purchases: List<Purchase>, fromRestore: Boolean) {
        val ours = purchases.filter { p -> p.products.any { it in OURS } }
        val owned = ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        for (p in owned) if (!p.isAcknowledged) acknowledge(p)
        val pending = ours.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        val unlocked = when {
            owned.isNotEmpty() -> true
            fromRestore -> false // nothing active any more: the subscription ended or was refunded
            else -> _state.value.unlocked
        }
        prefs.edit().putBoolean("unlocked", unlocked).apply()
        _state.update {
            it.copy(
                unlocked = unlocked, pending = pending && !unlocked,
                ownedSubscription = owned.any { p -> SUBSCRIPTION in p.products },
                message = if (!fromRestore && owned.isNotEmpty()) "Thank you! Premium is unlocked." else if (pending && !unlocked) "Payment pending. Premium unlocks when Google Play confirms it." else it.message,
            )
        }
    }

    private suspend fun acknowledge(p: Purchase): Unit = suspendCancellableCoroutine { cont ->
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun details(id: String, type: String): ProductDetails? = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()))
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (cont.isActive) cont.resume(if (result.responseCode == BillingResponseCode.OK) details.productDetailsList.firstOrNull() else null)
        }
    }

    private suspend fun loadPlans() {
        val plans = mutableListOf<PremiumPlan>()
        details(SUBSCRIPTION, ProductType.SUBS)?.let { pd ->
            pd.subscriptionOfferDetails.orEmpty().groupBy { it.basePlanId }.forEach { (_, offers) ->
                val base = offers.firstOrNull { it.offerId == null } ?: offers.first()
                val recurring = base.pricingPhases.pricingPhaseList.lastOrNull() ?: return@forEach
                val trial = offers.firstOrNull { o -> o.offerId != null && o.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L } }
                val trialDays = trial?.pricingPhases?.pricingPhaseList?.firstOrNull { it.priceAmountMicros == 0L }?.billingPeriod?.let(::days)
                val yearly = recurring.billingPeriod.endsWith("Y")
                plans += PremiumPlan(
                    key = "${pd.productId}:${base.basePlanId}", kind = if (yearly) PremiumPlan.Kind.YEARLY else PremiumPlan.Kind.MONTHLY,
                    price = recurring.formattedPrice, period = if (yearly) "year" else "month", trialDays = trialDays,
                    details = pd, offerToken = (trial ?: base).offerToken,
                )
            }
        }
        details(LIFETIME, ProductType.INAPP)?.let { pd ->
            pd.oneTimePurchaseOfferDetails?.let { o -> plans += PremiumPlan("${pd.productId}:once", PremiumPlan.Kind.LIFETIME, o.formattedPrice, null, null, pd, null) }
        }
        _state.update {
            it.copy(
                plans = plans.sortedBy { p -> p.kind.ordinal },
                message = if (plans.isEmpty() && it.billingAvailable) "Premium plans could not be loaded. Try again later." else it.message,
            )
        }
    }

    /** ISO-8601 period ("P3D", "P1W") to days. */
    private fun days(period: String): Int? {
        val m = Regex("P(?:(\\d+)W)?(?:(\\d+)D)?").matchEntire(period) ?: return null
        val d = (m.groupValues[1].toIntOrNull() ?: 0) * 7 + (m.groupValues[2].toIntOrNull() ?: 0)
        return d.takeIf { it > 0 }
    }

    private fun describe(r: BillingResult) = when (r.responseCode) {
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED ->
            "Google Play billing isn't available. Install SDM from Google Play and sign in to the Play Store."
        BillingResponseCode.ITEM_UNAVAILABLE -> "This plan isn't available in your country yet."
        BillingResponseCode.NETWORK_ERROR -> "No connection to Google Play. Check your internet and try again."
        else -> "Purchase failed. Please try again."
    }
}
