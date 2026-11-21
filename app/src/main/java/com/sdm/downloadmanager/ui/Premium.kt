package com.sdm.downloadmanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sdm.core.common.PremiumFeature
import com.sdm.core.designsystem.i18n.Icon
import com.sdm.core.designsystem.i18n.Text

/** Whether Premium is unlocked (always true in the GitHub build). */
val LocalPremium = compositionLocalOf { true }

fun AppActions.openPremium() = nav.navigate(PremiumRoute)

/** Small "Premium" label with a lock, next to options that need it. */
@Composable
fun PremiumTag(modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Premium", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Explains that [feature] is part of Premium and opens the Premium page. */
@Composable
fun PremiumUpsell(feature: PremiumFeature, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val app = LocalAppActions.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(BrandGradient).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WorkspacePremium, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(feature.title, style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(start = 10.dp))
        }
        Text(
            "This is part of SDM Premium. Try it free for 3 days, then subscribe or buy it once.",
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = { onOpen?.invoke(); app.openPremium() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF10264D)),
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("See Premium") }
    }
}
