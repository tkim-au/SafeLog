package com.safelog.app.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import com.safelog.app.ui.components.EaseOut
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.HoldTabSwipe
import com.safelog.app.ui.components.tabContentSlide
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import android.graphics.BitmapFactory
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import com.safelog.app.model.Avatar
import com.safelog.app.model.Driver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safelog.app.R
import com.safelog.app.model.AtlasStage
import com.safelog.app.model.atlasStage
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.ButtonTone
import com.safelog.app.ui.components.CardShape
import com.safelog.app.ui.components.LocalDemoButton
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.components.pressScale
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/*
 * 메뉴에서 들어가는 화면의 공통 틀과 부품. 배치·글자 규칙은 Layout.kt 위쪽에 있다.
 */

/**
 * 모아 보기 화면(기록·캐릭터·내 차 익히기). 가로 창은 왼쪽 패널(창 높이를 채우는 카드 하나)과 오른쪽 목록,
 * 세로 창은 패널 카드 아래로 목록을 쌓는다. 맨 위 줄은 [header]: 탭 화면은 [AppTopBar], 하위 화면은 [DepthHeader].
 */
@Composable
fun SubScreen(
    header: @Composable () -> Unit,
    panel: @Composable ColumnScope.() -> Unit,
    centering: CenteringScroll? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = centering?.state ?: rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().background(SafeLogTheme.colors.background)) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val inset = screenInset(wide)
        Column(Modifier.fillMaxSize().padding(start = inset, end = inset, top = inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            header()
            if (wide) {
                Row(Modifier.weight(1f).tabContentSlide().padding(bottom = inset), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    FillPanel(Modifier.weight(1f).fillMaxHeight(), panel)
                    Column(
                        Modifier.weight(1.4f).fillMaxHeight().onGloballyPositioned { centering?.viewport = it }.verticalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(STACK_GAP),
                        content = content,
                    )
                }
            } else {
                Column(
                    Modifier.weight(1f).tabContentSlide().onGloballyPositioned { centering?.viewport = it }.verticalScroll(scroll).padding(bottom = inset),
                    verticalArrangement = Arrangement.spacedBy(STACK_GAP),
                ) {
                    Column(
                        Modifier.fillMaxWidth().clip(CardShape).background(SafeLogTheme.colors.surface).padding(CARD_PADDING),
                        verticalArrangement = PanelArrangement,
                        content = panel,
                    )
                    content()
                }
            }
        }
    }
}

/**
 * 목록의 한 줄을 화면 가운데로 끌어오는 스크롤. [SubScreen]에 넘기면 목록 칸(세로 창은 화면 전체)에 붙는다.
 * 지금 할 일이 늘 눈높이에 오도록 진행에 맞춰 목록이 스스로 내려간다(내 차 익히기).
 */
class CenteringScroll(val state: ScrollState) {
    internal var viewport: LayoutCoordinates? = null

    /** [row]가 보이는 칸의 가운데에 오게 스크롤한다. 처음에는 바로, 그 뒤로는 화면 전환과 같은 300ms로. */
    suspend fun center(row: LayoutCoordinates, animate: Boolean) {
        val box = viewport?.takeIf { it.isAttached } ?: return
        if (!row.isAttached) return
        val top = box.localPositionOf(row, Offset.Zero).y
        val target = (state.value + top - (box.size.height - row.size.height) / 2f).roundToInt().coerceIn(0, state.maxValue)
        if (animate) state.animateScrollTo(target, tween(Motion.PUSH, easing = EaseOut)) else state.scrollTo(target)
    }
}

@Composable
fun rememberCenteringScroll() = remember { CenteringScroll(ScrollState(0)) }

/** 창 높이를 채우는 패널. 내용이 넘치면 패널 안에서 스크롤한다. */
@Composable
private fun FillPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(modifier.clip(CardShape).background(SafeLogTheme.colors.surface)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(CARD_PADDING),
            verticalArrangement = PanelArrangement,
            content = content,
        )
    }
}

/**
 * 패널 안 배치: 위에서부터 24 간격으로 쌓고, 남는 높이는 마지막 묶음 앞에 둔다.
 * 그래서 패널의 마지막 묶음(요약·성장 단계)은 늘 바닥에 붙는다.
 */
private val PanelArrangement = object : Arrangement.Vertical {
    override val spacing = 24.dp
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val gap = spacing.roundToPx()
        var y = 0
        sizes.forEachIndexed { i, size ->
            outPositions[i] = y
            y += size + gap
        }
        val extra = totalSize - (y - gap)
        if (extra > 0 && sizes.isNotEmpty()) outPositions[sizes.lastIndex] += extra
    }
}

/** 흐름 화면(퀴즈·설정·운전자). 가로 창에서도 가운데 한 칸, 폭 최대 840. */
@Composable
fun FlowScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(SafeLogTheme.colors.background)) {
        val inset = screenInset(maxWidth >= WIDE_BREAKPOINT)
        Column(Modifier.fillMaxSize().padding(start = inset, end = inset, top = inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            DepthHeader(title, onBack)
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.widthIn(max = FLOW_MAX_WIDTH).fillMaxWidth().padding(bottom = inset),
                    verticalArrangement = Arrangement.spacedBy(STACK_GAP),
                    content = content,
                )
            }
        }
    }
}

/** 공식 ivi_title Depth: 높이 80, 뒤로 아이콘 버튼 Medium 80 */
@Composable
fun DepthHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(80.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "뒤로", Modifier.size(40.dp), SafeLogTheme.colors.ink)
        }
        Text(title, style = SafeLogType.title, color = SafeLogTheme.colors.ink, modifier = Modifier.weight(1f))
        LocalDemoButton.current?.invoke() // 디버그 빌드의 시연 단추
    }
}

/** 공식 ivi_icon_button Small: 터치 칸 64, 아이콘 32 */
@Composable
fun IconAction(icon: ImageVector, label: String, tint: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.size(64.dp).pressable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(32.dp), if (enabled) tint else SafeLogTheme.colors.inkDisabled)
    }
}

/**
 * 카드 제목. 토스처럼 묶음의 제목은 카드 밖이 아니라 카드 안 맨 위에 둔다.
 * 그래서 가로 창의 칸들이 모두 카드로 시작하고, 칸마다 카드의 위 끝이 맞는다.
 */
@Composable
fun CardTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = SafeLogType.bodyLarge, color = SafeLogTheme.colors.ink, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** 숫자 하나가 주인공인 카드. 화면의 주인공이 아니면 [hero]를 false로 둔다. */
@Composable
fun StatCard(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = SafeLogTheme.colors
    Column(
        modifier.fillMaxWidth().clip(CardShape).background(colors.surface).padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = SafeLogType.label, color = colors.inkMuted)
        Text(value, style = numberStyle(value, hero), color = valueColor, maxLines = 1)
        footer()
    }
}

/** 숫자 글자 크기. 주인공은 60, 둘째는 48, 길면 한 단계 작게. */
fun numberStyle(value: String, hero: Boolean) = when {
    hero && value.length <= 7 -> SafeLogType.number
    value.length <= 7 -> SafeLogType.numberMedium
    else -> SafeLogType.numberCompact
}

/** 토스식 묶음 목록. 선 없이 한 카드 안에 행을 쌓는다. 제목이 있으면 카드 안 맨 위에 둔다. */
@Composable
fun ListCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().clip(CardShape).background(SafeLogTheme.colors.surface).padding(vertical = 4.dp)) {
        title?.let {
            Column(
                Modifier.padding(start = CARD_PADDING, end = CARD_PADDING, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(LINE_GAP),
            ) {
                CardTitle(it, trailing = trailing)
                subtitle?.let { s -> Text(s, style = SafeLogType.label, color = SafeLogTheme.colors.inkMuted, maxLines = 1) }
            }
        }
        content()
    }
}

/**
 * 목록 한 줄. 공식 dropdown_item Medium 높이 100. 제목 body(보통), 설명 label, 값은 ValueText(굵게).
 * 누르는 행은 [modifier]에 pressable·pressSelect·toggleable을 넘긴다.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = SafeLogTheme.colors
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 100.dp).padding(horizontal = CARD_PADDING, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        leading?.invoke()
        // 토스처럼 행 제목은 보통 굵기, 굵은 글씨는 카드 제목과 값에만 쓴다.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text(title, style = SafeLogType.body, color = if (enabled) colors.ink else colors.inkDisabled)
            // 꼬리표는 오른쪽이 아니라 설명 줄 옆에 둔다. 좁은 칸에서 제목을 접지 않는다.
            if (sub != null || badge != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sub?.let { Text(it, style = SafeLogType.label, color = colors.inkMuted) }
                    badge?.invoke()
                }
            }
        }
        trailing()
    }
}

/** 다음 화면으로 가는 행의 오른쪽 표시 */
@Composable
fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(32.dp), SafeLogTheme.colors.inkMuted)
}

/** 눌리는 곳. 누르는 동안 0.97로 줄어든다. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction).clickable(interaction, null, enabled, role = role, onClick = onClick)
}

/** 누르면 고르는 곳(체크·라디오). 누르는 동안 0.97로 줄어든다. */
@Composable
fun Modifier.pressSelect(selected: Boolean, enabled: Boolean = true, role: Role = Role.Checkbox, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return pressScale(interaction).selectable(selected, interaction, null, enabled, role, onClick)
}

@Composable
fun ValueText(value: String, color: Color = SafeLogTheme.colors.ink) {
    Text(value, style = SafeLogType.bodyStrong, color = color, maxLines = 1)
}

/** 옅은 색 바탕의 작은 꼬리표. 글자 20은 여기에만 쓴다. */
@Composable
fun Chip(text: String, color: Color) {
    val ground = SafeLogTheme.colors.surface
    Text(
        text,
        style = SafeLogType.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f).compositeOver(ground)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 공식 segmented_controls: 항목 높이 84, 모서리 24, 바탕 패딩 8 */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = SafeLogTheme.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.tabBackground).padding(8.dp)) {
        options.forEachIndexed { i, option ->
            val on = i == selected
            val bg by animateColorAsState(if (on) colors.tabSelected else Color.Transparent, tween(150), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .height(84.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = SafeLogType.bodyStrong, color = if (on) colors.ink else colors.inkMuted, maxLines = 1)
            }
        }
    }
}

/** 공식 ivi_switch Medium 80×48. 행 전체를 눌러 켜고 끈다. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, enabled: Boolean = true) {
    ListRow(
        title,
        Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        sub = sub,
        enabled = enabled,
    ) { SwitchMark(checked, enabled) }
}

@Composable
private fun SwitchMark(checked: Boolean, enabled: Boolean) {
    val colors = SafeLogTheme.colors
    val track by animateColorAsState(if (checked) colors.switchOn else colors.inkDisabled, tween(150), label = "track")
    val x by animateDpAsState(if (checked) 36.dp else 4.dp, spring(dampingRatio = 1f, stiffness = 438f), label = "thumb")
    Box(Modifier.size(80.dp, 48.dp).clip(CircleShape).background(if (enabled) track else track.copy(alpha = 0.4f))) {
        Box(Modifier.offset(x = x, y = 4.dp).size(40.dp).clip(CircleShape).background(Color.White))
    }
}

/** 강조 면: 카드 위 파랑 12%. 미션 클리어·아틀라스 얼굴에 쓴다. 상품 그림은 회색 무대([ProductStage]). */
@Composable
fun accentTint(): Color = SafeLogTheme.colors.accent.copy(alpha = 0.12f).compositeOver(SafeLogTheme.colors.surface)

/** 운전자 얼굴: 아틀라스(레벨만큼 자란 모습)·색 글자·사진 중 그 사람이 고른 것. 고른 사람은 파란 테두리. */
@Composable
fun DriverAvatar(driver: Driver, size: Dp, modifier: Modifier = Modifier, selected: Boolean = false) {
    val shape = RoundedCornerShape(size * 0.25f)
    val avatar = driver.avatar
    val photo = (avatar as? Avatar.Photo)?.let { rememberPhoto(it.path) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (avatar is Avatar.Letter) AVATAR_COLORS[avatar.color % AVATAR_COLORS.size] else accentTint())
            .then(if (selected) Modifier.border(4.dp, SafeLogTheme.colors.accent, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            photo != null -> Image(photo, driver.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            avatar is Avatar.Letter -> {
                val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
                Text(driver.name.take(1), style = SafeLogType.number.copy(fontSize = fontSize, lineHeight = fontSize), color = Color.White)
            }
            // 사진 파일을 못 읽으면(지워졌거나 아직 읽는 중) 아틀라스로 보인다.
            else -> Image(painterResource(atlasStage(driver.level).image), null, Modifier.size(size * 0.8f), contentScale = ContentScale.Fit)
        }
    }
}

/** 색 글자 얼굴의 색. 흰 글자가 라이트·다크 모두에서 읽히는 진한 색만 둔다. */
val AVATAR_COLORS = listOf(
    Color(0xFF3D5FB8), Color(0xFF0E8A50), Color(0xFFD9622B), Color(0xFFC93D6E), Color(0xFF6F4FC0), Color(0xFF16808C),
)

@Composable
private fun rememberPhoto(path: String): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
    return bitmap
}

/**
 * 상품 그림의 무대. 토스 선물하기처럼 사진은 색이 묻지 않는 옅은 회색 위에 둔다(파란 면 위의 흰 컵은 푸르게 보인다).
 * 큰 글자 쿠폰도 같은 무대를 써서 교환소 칸들이 한 벌로 보인다.
 */
@Composable
fun Modifier.productStage(shape: Shape = RoundedCornerShape(16.dp)) = clip(shape).background(SafeLogTheme.colors.surfaceHigh)

/**
 * 상품 사진 하나. 칸의 크기가 바뀌어도 물건은 늘 짧은 변의 70%라 여백 비율과 무게가 같다(애플 제품 사진처럼).
 * 바닥에 옅은 그림자를 깔아 물건이 떠 보이지 않게 한다. 사진은 배경이 투명한 것을 쓴다.
 */
@Composable
fun ProductPhoto(@DrawableRes image: Int, description: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight) * 0.7f
        Box(Modifier.size(side)) {
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.86f).height(side * 0.12f)) {
                val r = size.width / 2
                scale(scaleX = 1f, scaleY = size.height / size.width) {
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent), center, r), r, center)
                }
            }
            Image(
                painterResource(image), description,
                Modifier.fillMaxSize().padding(bottom = side * 0.05f),
                alignment = Alignment.BottomCenter, contentScale = ContentScale.Fit,
            )
        }
    }
}

val AtlasStage.image
    get() = when (this) {
        AtlasStage.Baby -> R.drawable.atlas_baby
        AtlasStage.Child -> R.drawable.atlas_child
        AtlasStage.Grown -> R.drawable.atlas_default
    }

/**
 * 공식 ivi_system_popup: 폭 최대 808, 모서리 48, 여백 48, 간격 24. 버튼은 Large.
 * [dismissText]가 없으면 버튼 하나. 바깥을 누르면 닫힌다.
 */
@Composable
fun SafeLogPopup(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    caption: String? = null,
    body: String? = null,
    dismissText: String? = null,
    image: (@Composable () -> Unit)? = null,
    confirmTone: ButtonTone = ButtonTone.Primary,
) {
    val colors = SafeLogTheme.colors
    // 들어올 때 가림막은 페이드, 카드는 0.94에서 커진다. 닫으면 짧게 사라진 뒤에 [onConfirm]·[onDismiss]를 부른다.
    // 그래서 부르는 쪽은 그냥 if로 띄우고 닫으면 지우면 된다. 스냅샷(검사 모드)에서는 처음부터 다 보인다.
    val inspection = LocalInspectionMode.current
    HoldTabSwipe() // 팝업이 떠 있는 동안 화면을 밀어 탭을 넘기지 않는다.
    val shown = remember { MutableTransitionState(inspection) }
    LaunchedEffect(Unit) { shown.targetState = true }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> () -> Unit = { action -> { if (pending == null) { pending = action; shown.targetState = false } } }
    LaunchedEffect(shown.currentState, shown.isIdle, pending) {
        val action = pending
        if (action != null && shown.isIdle && !shown.currentState) {
            pending = null
            action()
        }
    }
    AnimatedVisibility(shown, enter = EnterTransition.None, exit = ExitTransition.None) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .animateEnterExit(fadeIn(tween(Motion.POPUP_IN)), fadeOut(tween(Motion.POPUP_OUT)))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = close(onDismiss)),
            )
            Column(
                Modifier
                    .animateEnterExit(
                        fadeIn(tween(Motion.POPUP_IN)) + scaleIn(tween(Motion.POPUP_IN, easing = EaseOut), initialScale = 0.94f),
                        fadeOut(tween(Motion.POPUP_OUT)) + scaleOut(tween(Motion.POPUP_OUT), targetScale = 0.96f),
                    )
                    .padding(16.dp)
                    .widthIn(max = 808.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(48.dp))
                    .background(colors.popup)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                image?.invoke()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    caption?.let { Text(it, style = SafeLogType.label, color = colors.inkMuted) }
                    Text(title, style = SafeLogType.title, color = colors.ink)
                    body?.let { Text(it, style = SafeLogType.body, color = colors.inkSecondary) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    dismissText?.let { SafeLogButton(it, close(onDismiss), Modifier.weight(1f), size = ButtonSize.Large) }
                    SafeLogButton(confirmText, close(onConfirm), Modifier.weight(1f), size = ButtonSize.Large, tone = confirmTone)
                }
            }
        }
    }
}
