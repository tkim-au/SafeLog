package com.safelog.app.ui.screens

import androidx.compose.animation.Crossfade
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.HoldTabSwipe
import com.safelog.app.ui.components.tabContentSlide
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.safelog.app.model.MileageGrade
import com.safelog.app.model.NextReward
import com.safelog.app.model.ShopCategory
import com.safelog.app.model.ShopItem
import com.safelog.app.model.cost
import com.safelog.app.model.wonValue
import com.safelog.app.ui.components.PushContent
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.CardShape
import com.safelog.app.ui.components.ProgressBar
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import java.util.Locale

/**
 * S50 포인트 교환소. 카카오톡 선물하기처럼 위에서 분야를 고르고, 그림 카드를 눌러 상품 화면에서 교환한다.
 * 상품은 포인트가 낮은 순이라 지금 바꿀 수 있는 상품이 앞에 오고, 모자란 상품은 "얼마 더"를 적는다(기획서 F8 보이는 진척도).
 * 등급 할인이 있으면 원래 값에 줄을 긋고 할인 값을 보여 준다.
 */
@Composable
fun ShopScreen(
    balance: Int,
    nextReward: NextReward,
    grade: MileageGrade,
    items: List<ShopItem>,
    onExchange: (ShopItem) -> Boolean,
    header: @Composable () -> Unit,
    initialDetailId: String? = null, // 화면 이미지 시험용
    initialDone: Boolean = false, // 화면 이미지 시험용: 교환 완료 팝업이 뜬 모습
    insurance: com.safelog.app.model.InsuranceProgress? = null, // 보험 공유에 동의했을 때만
) {
    var category by rememberSaveable { mutableStateOf<ShopCategory?>(null) }
    var detailId by rememberSaveable { mutableStateOf(initialDetailId) }
    var done by rememberSaveable { mutableStateOf(initialDone) }
    val detail = items.firstOrNull { it.id == detailId }
    val close = {
        detailId = null
        done = false
    }
    BackHandler(enabled = detail != null, onBack = close)
    // 상품 화면은 탭 안에서 들어간 화면이라, 보는 동안 화면을 밀어 옆 탭으로 넘기지 않는다.
    if (detail != null) HoldTabSwipe()
    Box(Modifier.fillMaxSize().background(SafeLogTheme.colors.background)) {
        PushContent(detail, forward = { _, to -> to != null }, label = "product") { item ->
            if (item == null) {
                ShopList(balance, nextReward, grade, items, category, { category = it }, { detailId = it.id }, header, insurance)
            } else {
                ProductDetail(item, grade, balance, onExchange = { done = onExchange(item) }, onBack = close)
            }
        }
        if (done && detail != null) {
            detail?.let {
                // 제휴 전이라 모의 쿠폰이다(기획서 6장). 실제 쿠폰을 어디로 보낼지는 제휴와 서버 연동 때 정한다.
                SafeLogPopup(title = "교환했어요", caption = it.name, body = "시연용 모의 쿠폰이에요", confirmText = "확인", onConfirm = close, onDismiss = close)
            }
        }
    }
}

@Composable
private fun ShopList(
    balance: Int,
    nextReward: NextReward,
    grade: MileageGrade,
    items: List<ShopItem>,
    category: ShopCategory?,
    onCategory: (ShopCategory?) -> Unit,
    onOpen: (ShopItem) -> Unit,
    header: @Composable () -> Unit,
    insurance: com.safelog.app.model.InsuranceProgress?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val inset = screenInset(maxWidth >= WIDE_BREAKPOINT)
        Column(Modifier.fillMaxSize().padding(top = inset, start = inset, end = inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            header()
            // 위쪽 탭이 바뀔 때는 분야 줄과 목록이 함께 옆으로 밀린다.
            Column(Modifier.weight(1f).tabContentSlide(), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            CategoryTabs(category, onCategory)
            // 분야를 바꾸면 목록이 짧게 교차 페이드한다.
            Crossfade(category, Modifier.weight(1f), animationSpec = tween(Motion.FADE), label = "category") { shownCategory ->
            // 등급 할인을 반영한 포인트가 낮은 순. 잔액보다 싼 상품이 늘 앞에 오므로 "지금 바꿀 수 있는 것 먼저"도 함께 지켜진다.
            val shown = items.filter { shownCategory == null || it.category == shownCategory }.sortedBy { it.cost(grade) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = inset),
                horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                verticalArrangement = Arrangement.spacedBy(COLUMN_GAP),
            ) {
                // 가장 큰 혜택(연 1회 보험 할인)을 맨 위에. 전체 분야에서만 보인다.
                if (insurance != null && shownCategory == null) item(span = { GridItemSpan(maxLineSpan) }) { InsuranceCard(insurance) }
                item(span = { GridItemSpan(maxLineSpan) }) { BalanceBanner(balance, nextReward, grade) }
                items(shown, key = { it.id }) { ProductCard(it, grade, balance) { onOpen(it) } }
            }
            }
            }
        }
    }
}

@Composable
private fun BalanceBanner(balance: Int, nextReward: NextReward, grade: MileageGrade) {
    val colors = SafeLogTheme.colors
    val shown by animateIntAsState(balance, tween(400), label = "balance")
    Row(
        Modifier.fillMaxWidth().clip(CardShape).background(colors.surface).padding(CARD_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CARD_PADDING),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("내 포인트", style = SafeLogType.label, color = colors.inkMuted)
                if (grade.discountPercent > 0) Chip("${grade.label} ${grade.discountPercent}% 할인", colors.accent)
            }
            Text(points(shown), style = numberStyle(points(shown), hero = false), color = colors.ink, maxLines = 1)
            Text(wonValue(shown), style = SafeLogType.label, color = colors.inkMuted)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${nextReward.itemName}까지 ${points(nextReward.remainingPoints)}", style = SafeLogType.label, color = colors.inkMuted)
            ProgressBar(nextReward.progress, colors.accent)
        }
    }
}

/** 위쪽 분야 탭. 알약 모양, 고른 것만 짙게. 공식 버튼 Small 높이 64. */
@Composable
private fun CategoryTabs(selected: ShopCategory?, onSelect: (ShopCategory?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("전체", selected == null) { onSelect(null) }
        ShopCategory.entries.forEach { c -> Pill(c.label, selected == c) { onSelect(c) } }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    Box(
        Modifier
            .height(ButtonSize.Small.height)
            .clip(RoundedCornerShape(32.dp))
            .background(if (on) colors.buttonFilled else colors.surface)
            .selectable(on, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = SafeLogType.bodyStrong, color = if (on) colors.onButtonFilled else colors.inkSecondary, maxLines = 1)
    }
}

@Composable
private fun ProductCard(item: ShopItem, grade: MileageGrade, balance: Int, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    val cost = item.cost(grade)
    val short = cost - balance
    Column(Modifier.pressable(onClick = onClick).clip(CardShape).background(colors.surface)) {
        ProductArt(item, Modifier.fillMaxWidth().aspectRatio(1.25f))
        // 이름은 한 줄이어도 두 줄 자리를, "더 필요" 줄은 바로 바꿀 수 있어도 한 줄 자리를 늘 차지해
        // 모든 카드의 높이와 가격 위치가 같다. 글자 크기를 키워도 함께 커질 뿐 어긋나지 않는다.
        Column(Modifier.padding(horizontal = CARD_PADDING, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
            Text(item.brand, style = SafeLogType.label, color = colors.inkMuted, maxLines = 1)
            Text(item.name, style = SafeLogType.body, color = colors.ink, maxLines = 2, minLines = 2)
            Price(item, cost)
            Text(if (short > 0) "${points(short)} 더 필요" else "", style = SafeLogType.label, color = colors.inkMuted, maxLines = 1, minLines = 1)
        }
    }
}

/** 할인 값(굵게)과 원래 값(줄 그음). 할인이 없으면 값 하나. */
@Composable
private fun Price(item: ShopItem, cost: Int, hero: Boolean = false) {
    val colors = SafeLogTheme.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(points(cost), style = if (hero) SafeLogType.number else SafeLogType.bodyLarge, color = colors.ink, maxLines = 1)
        if (cost < item.price) {
            Text(
                points(item.price),
                style = SafeLogType.label.copy(textDecoration = TextDecoration.LineThrough),
                color = colors.inkMuted,
                maxLines = 1,
                modifier = Modifier.padding(bottom = if (hero) 14.dp else 4.dp),
            )
        }
    }
}

private val ShopCategory.icon
    get() = when (this) {
        ShopCategory.Car -> Icons.Filled.Build
        ShopCategory.Life -> Icons.Filled.ShoppingCart
        ShopCategory.Culture -> Icons.Filled.Star
        ShopCategory.Atlas -> Icons.Filled.Face
    }

/**
 * 상품 그림. 사진이 있으면 사진만(아이콘을 얹지 않는다). 사진이 없으면 쿠폰처럼 왼쪽 위 분야 아이콘과 가운데 큰 글자.
 * 무대는 홈의 다음 보상 그림과 같은 회색이다([productStage]).
 */
@Composable
private fun ProductArt(item: ShopItem, modifier: Modifier, large: Boolean = false) {
    val colors = SafeLogTheme.colors
    Box(modifier.productStage(RectangleShape)) {
        if (item.image != null) {
            ProductPhoto(item.image, item.name, Modifier.fillMaxSize())
        } else {
            Icon(item.category.icon, null, Modifier.padding(20.dp).size(32.dp).align(Alignment.TopStart), colors.accent.copy(alpha = 0.64f))
            Text(
                item.art,
                style = when {
                    large -> SafeLogType.number
                    item.art.length > 4 -> SafeLogType.numberCompact
                    else -> SafeLogType.numberMedium
                },
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** 상품 화면. 가로 창은 그림 패널과 정보, 세로 창은 위아래. 교환 버튼은 늘 아래 끝. */
@Composable
private fun ProductDetail(item: ShopItem, grade: MileageGrade, balance: Int, onExchange: () -> Unit, onBack: () -> Unit) {
    val colors = SafeLogTheme.colors
    val cost = item.cost(grade)
    val short = cost - balance
    val info: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            Column(Modifier.padding(horizontal = CARD_PADDING), verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                Text(item.brand, style = SafeLogType.label, color = colors.inkMuted)
                Text(item.name, style = SafeLogType.title, color = colors.ink)
                Price(item, cost, hero = true)
            }
            ListCard {
                if (cost < item.price) ListRow("${grade.label} 할인") { ValueText("-" + points(item.price - cost), colors.accent) }
                ListRow("남는 포인트") { ValueText(if (short > 0) "-" else points(balance - cost)) }
            }
        }
    }
    val button: @Composable () -> Unit = {
        SafeLogButton(
            if (short > 0) "${points(short)} 부족해요" else "교환하기",
            onExchange,
            Modifier.fillMaxWidth(),
            primary = true,
            enabled = short <= 0,
            size = ButtonSize.Large,
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val inset = screenInset(wide)
        val artHeight = minOf(320.dp, maxHeight * 0.3f)
        Column(Modifier.fillMaxSize().padding(inset), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
            DepthHeader("상품 정보", onBack)
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)) {
                    ProductArt(item, Modifier.weight(1f).fillMaxHeight().clip(CardShape), large = true)
                    Column(Modifier.weight(1.4f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { info() }
                        button()
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(STACK_GAP)) {
                    // 작은 세로 창에서도 가격과 남는 포인트가 첫 화면에 보이게 그림 높이를 창의 30%까지로 둔다.
                    ProductArt(item, Modifier.fillMaxWidth().height(artHeight).clip(CardShape), large = true)
                    info()
                }
                button()
            }
        }
    }
}

private fun points(value: Int) = "%,dP".format(Locale.US, value)
