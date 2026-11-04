package com.product.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Check

/**
 * The mark wherever several things can be chosen (notes, tags, what to share), at the start of the line: a filled round tick, an
 * empty ring when not chosen, as Google's own apps select. One of several (a setting, a filter, the tag to merge into) is
 * Material's radio button in the same place and of the same width; on or off is the compact switch.
 */
@Composable
fun ChoiceMark(
    chosen: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = if (chosen) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
    Box(modifier.requiredSize(24.dp).clip(CircleShape).then(shape), contentAlignment = Alignment.Center) {
        if (chosen) Icon(PhosphorIcons.Regular.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}
