package com.sdm.downloadmanager.feature.premium

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.sdm.core.common.I18n
import com.sdm.core.designsystem.i18n.Icon
import com.sdm.core.designsystem.i18n.Text
import com.sdm.core.designsystem.theme.LocalSpacing
import com.sdm.downloadmanager.premium.PremiumPlan
import com.sdm.downloadmanager.premium.PremiumRepository
import com.sdm.downloadmanager.ui.BrandGradient
import com.sdm.downloadmanager.ui.BrandLogo
import com.sdm.downloadmanager.ui.LocalAppActions
import com.sdm.downloadmanager.ui.SdmScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PremiumViewModel @Inject constructor(val repo: PremiumRepository) : ViewModel() {
    val state = repo.state
}

private data class Benefit(val icon: ImageVector, val title: String, val body: String)

private val benefits = listOf(
    Benefit(Icons.Outlined.Palette, "Every accent color", "Choose the color SDM uses for buttons, progress and highlights."),
    Benefit(Icons.Outlined.Wallpaper, "Colors from your wallpaper", "SDM follows your wallpaper on Android 12 and newer."),
    Benefit(Icons.Outlined.DarkMode, "AMOLED black theme", "True black background that saves battery on OLED screens."),
    Benefit(Icons.Outlined.FavoriteBorder, "Support SDM", "Every download feature stays free. Premium keeps SDM ad-free and growing."),
)

@Composable
fun PremiumScreen(vm: PremiumViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    val app = LocalAppActions.current
    val context = LocalContext.current
    val sp = LocalSpacing.current
    LaunchedEffect(Unit) { vm.repo.refresh() }
    LaunchedEffect(s.message) {
        val m = s.message ?: return@LaunchedEffect
        app.snackbar.showSnackbar(I18n.tr(m)); vm.repo.clearMessage()
    }
    var chosen by remember { mutableStateOf<String?>(null) }
    val plan = s.plans.firstOrNull { it.key == chosen } ?: s.plans.firstOrNull { it.trialDays != null } ?: s.plans.firstOrNull()

    SdmScaffold("SDM Premium", onBack = { app.nav.popBackStack() }) { p ->
        Column(Modifier.padding(p).verticalScroll(rememberScrollState()).padding(horizontal = sp.l)) {
            // Brand header
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(BrandGradient).padding(horizontal = 28.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandLogo(Modifier.fillMaxWidth(0.62f))
                    Spacer(Modifier.height(14.dp))
                    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.16f)) {
                        Text("PREMIUM", style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            benefits.forEach { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(b.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleSmall)
                        Text(b.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                s.includedInBuild -> StatusCard("All Premium features are included in this version of SDM.")
                s.unlocked -> {
                    StatusCard("SDM Premium is active. Thank you for supporting SDM!")
                    if (s.ownedSubscription) OutlinedButton(onClick = { context.openSubscriptions() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Manage subscription") }
                }
                s.plans.isEmpty() -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        if (s.loading) CircularProgressIndicator()
                        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (!s.billingAvailable) "Google Play billing isn't available. Install SDM from Google Play and sign in to the Play Store." else "Premium plans could not be loaded.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { vm.repo.refresh() }) { Text("Try again") }
                        }
                    }
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.plans.forEach { pl -> PlanCard(pl, selected = pl.key == plan?.key) { chosen = pl.key } }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { plan?.let { pl -> context.findActivity()?.let { vm.repo.buy(it, pl) } } },
                        enabled = plan != null && !s.pending,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        AnimatedContent(plan?.let(::ctaFor) ?: "", transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "cta") { Text(it, maxLines = 1) }
                    }
                    plan?.let {
                        Text(
                            termsFor(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
            if (!s.includedInBuild) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { vm.repo.restorePurchases() }) { Text("Restore purchases") }
                    if (!s.unlocked) TextButton(onClick = { context.openSubscriptions() }) { Text("Manage subscription") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PlanCard(plan: PremiumPlan, selected: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "border")
    val width by animateDpAsState(if (selected) 2.dp else 1.dp, label = "width")
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp), border = BorderStroke(width, border),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (plan.kind) { PremiumPlan.Kind.YEARLY -> "Yearly"; PremiumPlan.Kind.MONTHLY -> "Monthly"; PremiumPlan.Kind.LIFETIME -> "Lifetime" }, style = MaterialTheme.typography.titleMedium)
                    val badge = when {
                        plan.trialDays != null -> "{} days free".replace("{}", plan.trialDays.toString())
                        plan.kind == PremiumPlan.Kind.LIFETIME -> "One-time purchase"
                        else -> null
                    }
                    if (badge != null) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp)) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                Text(
                    if (plan.period != null) "${plan.price} / ${I18n.tr(plan.period)}" else "Pay once, keep Premium on this Google account",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun StatusCard(text: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

private fun ctaFor(p: PremiumPlan) = when {
    p.trialDays != null -> "Start {}-day free trial".replace("{}", p.trialDays.toString())
    p.kind == PremiumPlan.Kind.LIFETIME -> "Buy once · {}".replace("{}", p.price)
    else -> "Subscribe"
}

/** What the user agrees to, stated before they tap: Play requires clear trial and renewal terms. */
private fun termsFor(p: PremiumPlan): String = when {
    p.kind == PremiumPlan.Kind.LIFETIME -> "One payment of {}. No subscription.".replace("{}", p.price)
    p.trialDays != null -> I18n.tr("Free for {} days, then {} per {}. Cancel anytime in Google Play before the trial ends and you won't be charged.")
        .replaceFirst("{}", p.trialDays.toString()).replaceFirst("{}", p.price).replaceFirst("{}", I18n.tr(p.period.orEmpty()))
    else -> I18n.tr("{} per {}, renews automatically until you cancel in Google Play.")
        .replaceFirst("{}", p.price).replaceFirst("{}", I18n.tr(p.period.orEmpty()))
}

private fun Context.openSubscriptions() {
    val uri = Uri.parse("https://play.google.com/store/account/subscriptions?sku=${PremiumRepository.SUBSCRIPTION}&package=$packageName")
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
