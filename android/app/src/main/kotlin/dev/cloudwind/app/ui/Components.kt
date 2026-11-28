package dev.cloudwind.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI

/** Shared Apple-inspired spring specs for fluid, non-stiff motion throughout the app. */
object CwMotion {
    val SpringFloat = spring<Float>(dampingRatio = 0.80f, stiffness = 360f)
    val SpringPopDp = spring<Dp>(dampingRatio = 0.74f, stiffness = 420f)
    val SpringSwitchDp = spring<Dp>(dampingRatio = 0.72f, stiffness = 480f)
    val SpringRing = spring<Float>(dampingRatio = 0.84f, stiffness = 170f)
    val SpringColor = spring<Color>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 360f)
}

/** Tactile spring press scale (subtle Apple bounce without heavy ripple). */
fun Modifier.springClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.70f, stiffness = 520f),
        label = "pressScale",
    )
    this
        .scale(scale)
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
        )
}

@Composable
fun InsetCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CwTheme.colors.bgCard,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor),
        content = content,
    )
}

@Composable
fun Hairline(indent: Dp = 16.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(0.5.dp)
            .background(CwTheme.colors.hairline),
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.35.sp,
        color = CwTheme.colors.ink2,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
fun SectionFootnote(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        lineHeight = 17.5.sp,
        color = CwTheme.colors.ink2,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** Crisp Apple-style sliding segmented control with fluid spring thumb physics. */
@Composable
fun <T> SlidingSegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    val selectedIdx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val shape = RoundedCornerShape(10.dp)
    val thumbShape = RoundedCornerShape(7.5.dp)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(c.segBg)
            .padding(2.5.dp),
    ) {
        val segWidth = maxWidth / options.size.coerceAtLeast(1)
        val thumbOffset by animateDpAsState(
            targetValue = segWidth * selectedIdx,
            animationSpec = CwMotion.SpringPopDp,
            label = "segThumb",
        )
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segWidth)
                .fillMaxHeight()
                .then(if (!c.isDark) Modifier.shadow(2.dp, thumbShape, ambientColor = Color(0x1C000000), spotColor = Color(0x18000000)) else Modifier)
                .clip(thumbShape)
                .background(c.segThumb),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) c.ink1 else c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tactile Apple-style rounded toggle switch with spring knob travel. */
@Composable
fun AppleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    val trackColor by animateColorAsState(
        targetValue = if (checked) c.switchOn else c.switchOff,
        animationSpec = CwMotion.SpringColor,
        label = "swTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = CwMotion.SpringSwitchDp,
        label = "swKnob",
    )
    Box(
        modifier = modifier
            .size(width = 51.dp, height = 31.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knobOffset, y = 2.dp)
                .size(27.dp)
                .shadow(3.dp, CircleShape, ambientColor = Color(0x28000000), spotColor = Color(0x2A000000))
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun PillPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    calm: Boolean = false,
    enabled: Boolean = true,
) {
    val c = CwTheme.colors
    val bg = when {
        !enabled -> c.blue.copy(alpha = 0.55f)
        calm -> c.fillSecondary
        else -> c.blue
    }
    val fg = if (calm) c.ink1 else Color.White
    Box(
        modifier = modifier
            .widthIn(min = 200.dp)
            .height(52.dp)
            .clip(CircleShape)
            .background(bg)
            .springClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 30.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
fun PillSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(c.fillSecondary)
            .springClickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.ink1, maxLines = 1)
    }
}

@Composable
fun TintedBluePill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(c.blueTint)
            .springClickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.blue, maxLines = 1)
    }
}

@Composable
fun SheetFullButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = CwTheme.colors.blue,
    fg: Color = Color.White,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.55f))
            .springClickable(enabled = enabled, pressedScale = 0.98f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

@Composable
fun IosIconTile(color: Color, icon: @Composable () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/**
 * 212dp 3-color semantic Precision Ring (Green = On Your PC, Blue = In Cloud, Orange = Waiting).
 *
 * Strictly NO grey background track behind the ring — gaps and empty canvas seamlessly match
 * the app's main background (`bgApp`: white/ceramic light or pure black OLED).
 */
@Composable
fun PrecisionRing(
    pcCount: Int,
    cloudCount: Int,
    waitingCount: Int,
    subtitle: String,
    paused: Boolean = false,
    noAccessEmpty: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    val total = pcCount + cloudCount + waitingCount
    val done = pcCount + cloudCount
    val pctTarget = when {
        noAccessEmpty && total == 0 -> 0
        waitingCount == 0 -> 100
        else -> ((done * 100.0) / total.coerceAtLeast(1)).toInt().coerceIn(0, 99)
    }

    val animatedPct by animateIntAsState(
        targetValue = pctTarget,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 200f),
        label = "ringPct",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (paused) 0.40f else 1f,
        animationSpec = CwMotion.SpringFloat,
        label = "ringAlpha",
    )

    // Smoothly animate each raw share with organic spring physics.
    val safeTotal = total.coerceAtLeast(1).toFloat()
    val pcFrac by animateFloatAsState(if (total == 0 && !noAccessEmpty) 1f else pcCount / safeTotal, CwMotion.SpringRing, label = "pcFrac")
    val clFrac by animateFloatAsState(cloudCount / safeTotal, CwMotion.SpringRing, label = "clFrac")
    val wtFrac by animateFloatAsState(waitingCount / safeTotal, CwMotion.SpringRing, label = "wtFrac")

    Box(modifier.size(226.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .alpha(ringAlpha),
        ) {
            val sw = 19.dp.toPx()
            val inset = sw / 2f + 2.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val stroke = Stroke(width = sw, cap = StrokeCap.Round)

            if (total == 0) {
                // Zero grey background track: only draw a crisp green ring when all caught up.
                if (!noAccessEmpty) {
                    drawArc(c.ringPc, -90f, 360f, false, topLeft, arcSize, style = stroke)
                }
                return@Canvas
            }

            val fracs = floatArrayOf(pcFrac, clFrac, wtFrac)
            val counts = intArrayOf(pcCount, cloudCount, waitingCount)
            val colors = arrayOf(c.ringPc, c.ringCloud, c.ringWait)
            val liveCount = counts.count { it > 0 }

            if (liveCount == 1) {
                val idx = counts.indexOfFirst { it > 0 }
                drawArc(colors[idx], -90f, 360f, false, topLeft, arcSize, style = stroke)
                return@Canvas
            }

            // Round cap overhang in degrees + crisp bgApp gap between segments.
            val radius = (arcSize.width / 2f).coerceAtLeast(1f)
            val capDeg = ((sw / 2f) / radius * (180f / PI.toFloat()))
            val gapDeg = 5.5f
            val minSlot = 2f * capDeg + gapDeg + 4.5f

            val shares = FloatArray(3) { i -> if (counts[i] > 0) (fracs[i] * 360f).coerceAtLeast(0f) else 0f }
            val small = BooleanArray(3) { i -> counts[i] > 0 && shares[i] < minSlot }
            var bump = 0f
            var bigSum = 0f
            for (i in 0..2) {
                if (small[i]) bump += (minSlot - shares[i])
                else if (counts[i] > 0) bigSum += shares[i]
            }
            for (i in 0..2) {
                if (counts[i] == 0) shares[i] = 0f
                else if (small[i]) shares[i] = minSlot
                else if (bigSum > 0f) shares[i] = (shares[i] - bump * (shares[i] / bigSum)).coerceAtLeast(minSlot)
            }

            var cursor = -90f
            for (i in 0..2) {
                if (counts[i] <= 0) continue
                val sweep = (shares[i] - 2f * capDeg - gapDeg).coerceAtLeast(0.5f)
                val startAngle = cursor + capDeg + gapDeg / 2f
                drawArc(colors[i], startAngle, sweep, false, topLeft, arcSize, style = stroke)
                cursor += shares[i]
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "$animatedPct",
                    fontSize = 62.sp,
                    lineHeight = 62.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-2.2).sp,
                    color = c.ink1,
                )
                Text(
                    text = "%",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.ink2,
                    modifier = Modifier.padding(top = 7.dp, start = 2.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Normal,
                color = c.ink2,
            )
        }
    }
}

enum class AppTab(val label: String) {
    SYNC("Sync"),
    FOLDERS("Folders"),
    HISTORY("History"),
    SETTINGS("Settings"),
}

/** Floating frosted-glass 4-tab Apple pill bar with fluid spring capsule highlight. */
@Composable
fun FloatingPillTabBar(
    active: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CwTheme.colors
    val tabs = AppTab.entries
    val selectedIdx = tabs.indexOf(active).coerceAtLeast(0)

    BoxWithConstraints(
        modifier = modifier
            .widthIn(max = 372.dp)
            .fillMaxWidth()
            .height(64.dp)
            .shadow(if (c.isDark) 18.dp else 12.dp, CircleShape, ambientColor = Color(0x26000000), spotColor = Color(0x26000000))
            .clip(CircleShape)
            .background(c.tabbarBg)
            .border(0.5.dp, c.tabbarBorder, CircleShape)
            .padding(4.dp),
    ) {
        val tabWidth = maxWidth / tabs.size
        val capsuleX by animateDpAsState(
            targetValue = tabWidth * selectedIdx,
            animationSpec = CwMotion.SpringPopDp,
            label = "tabCapsule",
        )
        Box(
            Modifier
                .offset(x = capsuleX)
                .width(tabWidth)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(c.tabbarThumb),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val isSelected = tab == active
                val tint by animateColorAsState(
                    targetValue = if (isSelected) c.blue else c.ink2,
                    animationSpec = CwMotion.SpringColor,
                    label = "tabTint_${tab.name}",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (tab) {
                        AppTab.SYNC -> SyncTabIcon(tint, Modifier.size(23.dp))
                        AppTab.FOLDERS -> FolderTabIcon(tint, Modifier.size(23.dp))
                        AppTab.HISTORY -> HistoryGridTabIcon(tint, Modifier.size(23.dp))
                        AppTab.SETTINGS -> SlidersTabIcon(tint, Modifier.size(23.dp))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        style = TextStyle(
                            fontSize = 11.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tint,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
