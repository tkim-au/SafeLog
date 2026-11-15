package com.safelog.app.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.safelog.app.ui.components.Motion
import com.safelog.app.ui.components.PushContent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safelog.app.model.Avatar
import com.safelog.app.model.Driver
import com.safelog.app.model.MAX_DRIVERS
import com.safelog.app.model.ProfileAnswer
import com.safelog.app.ui.components.ButtonSize
import com.safelog.app.ui.components.SafeLogButton
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 운전자 고르기. 넷플릭스 프로필 화면처럼 가운데에 질문 하나와 얼굴 칸만 둔다.
 * 지금 운전자는 파란 테두리. 누르면 바로 바뀌고 이전 화면으로 돌아간다.
 * "얼굴 바꾸기"를 누르면 칸마다 연필이 뜨고, 누르면 그 사람의 얼굴을 고른다.
 * [onAdd]가 있으면 얼굴 칸 끝에 "운전자 추가" 칸이 붙는다([MAX_DRIVERS]명까지). 누르면 프로필 질문([AddDriverScreen])으로 들어간다.
 */
@Composable
fun DriverPickerScreen(
    drivers: List<Driver>,
    currentId: String,
    onSelect: (String) -> Unit,
    onSetAvatar: (String, Avatar) -> Unit,
    onBack: () -> Unit,
    initialEditing: Boolean = false,
    initialEditId: String? = null,
    onAdd: ((ProfileAnswer) -> Unit)? = null,
    initialAdding: Boolean = false, // 화면 이미지 시험용
) {
    var editing by rememberSaveable { mutableStateOf(initialEditing || initialEditId != null) }
    var editId by rememberSaveable { mutableStateOf(initialEditId) }
    var adding by rememberSaveable { mutableStateOf(initialAdding) }
    BackHandler(enabled = editId != null || adding) { if (adding) adding = false else editId = null }
    // 얼굴 고르기와 운전자 추가는 한 단계 깊은 화면이라 오른쪽에서 밀려 들어온다.
    PushContent(if (adding) ADD_DRIVER else editId, forward = { _, to -> to != null }, label = "avatar") { id ->
    if (id == ADD_DRIVER) {
        AddDriverScreen(number = drivers.size + 1, onAdd = { adding = false; onAdd?.invoke(it) }, onBack = { adding = false })
        return@PushContent
    }
    val editDriver = drivers.firstOrNull { it.id == id }
    if (editDriver != null) {
        AvatarPicker(editDriver, onPick = { onSetAvatar(editDriver.id, it); editId = null }, onBack = { editId = null })
        return@PushContent
    }
    val colors = SafeLogTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val wide = maxWidth >= WIDE_BREAKPOINT
        val inset = screenInset(wide)
        // 한 줄에 모두 들어가게 칸 크기를 정한다. 가장 클 때 200(공식 앱 아이콘 칸과 같은 비율).
        val gap = if (wide) 40.dp else 24.dp
        // 추가 칸은 얼굴을 바꾸는 동안에도 자리를 지킨다(칸 크기가 출렁이지 않게). 그동안은 흐리게 두고 누를 수 없다.
        val showAdd = onAdd != null && drivers.size < MAX_DRIVERS
        val tiles = drivers.size + if (showAdd) 1 else 0
        val tile = minOf(200.dp, (maxWidth - inset * 2 - gap * (tiles - 1)) / tiles)
        Column(Modifier.fillMaxSize().padding(inset)) {
            DepthHeader("운전자", onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterVertically),
            ) {
                Crossfade(editing, animationSpec = tween(Motion.FADE), label = "pickerTitle") {
                    Text(if (it) "누구의 얼굴을 바꿀까요?" else "누가 운전하나요?", style = SafeLogType.title, color = colors.ink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    drivers.forEach { driver ->
                        val current = driver.id == currentId
                        Column(
                            Modifier.width(tile).pressable { if (editing) editId = driver.id else onSelect(driver.id) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box {
                                DriverAvatar(driver, tile, selected = current && !editing)
                                androidx.compose.animation.AnimatedVisibility(editing, enter = fadeIn(tween(Motion.FADE)), exit = fadeOut(tween(Motion.FADE))) {
                                    EditScrim(tile)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LINE_GAP)) {
                                Text(driver.name, style = SafeLogType.bodyLarge, color = if (current) colors.ink else colors.inkSecondary, textAlign = TextAlign.Center)
                                Text(driver.mode.label, style = SafeLogType.label, color = colors.inkMuted, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    if (showAdd) AddTile(tile, enabled = !editing) { adding = true }
                }
                SafeLogButton(if (editing) "완료" else "얼굴 바꾸기", { editing = !editing }, primary = editing, size = ButtonSize.Small)
            }
        }
    }
    }
}

/** 운전자 추가 화면을 가리키는 열쇠. 운전자 id와 겹치지 않는 값이다. */
private const val ADD_DRIVER = "\u0000add"

/** 얼굴 칸과 같은 크기의 "운전자 추가" 칸. */
@Composable
private fun AddTile(tile: Dp, enabled: Boolean, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    Column(
        Modifier.width(tile).alpha(if (enabled) 1f else 0.38f).pressable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(tile).clip(RoundedCornerShape(tile * 0.25f)).background(colors.surface), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, Modifier.size(tile * 0.32f), colors.inkSecondary)
        }
        Text("운전자 추가", style = SafeLogType.bodyLarge, color = colors.inkSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun EditScrim(tile: Dp) {
    Box(
        Modifier.size(tile).clip(RoundedCornerShape(tile * 0.25f)).background(Color.Black.copy(alpha = 0.48f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Edit, "얼굴 바꾸기", Modifier.size(tile * 0.28f), Color.White)
    }
}

/** 얼굴 고르기: 사진, 아틀라스, 색 글자 여섯. 지금 얼굴은 파란 테두리. 고르면 바로 바뀌고 돌아간다. */
@Composable
private fun AvatarPicker(driver: Driver, onPick: (Avatar) -> Unit, onBack: () -> Unit) {
    val colors = SafeLogTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { copyPhoto(context, uri, driver.id)?.let { onPick(Avatar.Photo(it)) } }
    }
    val choices = listOf(Avatar.Atlas) + AVATAR_COLORS.indices.map { Avatar.Letter(it) }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
        val inset = screenInset(maxWidth >= WIDE_BREAKPOINT)
        Column(Modifier.fillMaxSize().padding(start = inset, end = inset, top = inset), horizontalAlignment = Alignment.CenterHorizontally) {
            DepthHeader("${driver.name}의 얼굴", onBack)
            LazyVerticalGrid(
                GridCells.Adaptive(160.dp),
                Modifier.weight(1f).widthIn(max = FLOW_MAX_WIDTH).padding(top = STACK_GAP),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item {
                    PhotoTile(driver.avatar is Avatar.Photo) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                items(choices) { avatar ->
                    BoxWithConstraints(Modifier.aspectRatio(1f).pressable { onPick(avatar) }) {
                        DriverAvatar(driver.copy(avatar = avatar), maxWidth, selected = driver.avatar == avatar)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(selected: Boolean, onClick: () -> Unit) {
    val colors = SafeLogTheme.colors
    BoxWithConstraints(Modifier.aspectRatio(1f).pressable(onClick = onClick)) {
        val shape = RoundedCornerShape(maxWidth * 0.25f)
        Column(
            Modifier.fillMaxSize().clip(shape).background(colors.surface)
                .then(if (selected) Modifier.border(4.dp, colors.accent, shape) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LINE_GAP, Alignment.CenterVertically),
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(48.dp), colors.inkSecondary)
            Text("사진", style = SafeLogType.label, color = colors.inkSecondary)
        }
    }
}

/**
 * 고른 사진을 512px로 줄여 앱 저장소에 복사한다. 원본 주소는 권한이 곧 끊겨서 그대로 쓰지 않는다.
 * ponytail: 사진의 회전 정보(EXIF)는 읽지 않는다. 옆으로 누운 사진이 나오면 ExifInterface로 돌린다.
 */
private suspend fun copyPhoto(context: Context, uri: Uri, driverId: String): String? = withContext(Dispatchers.IO) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@withContext null
    val scale = 512f / maxOf(decoded.width, decoded.height)
    val small = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
    // 이름에 시각을 넣어야 같은 사람이 사진을 다시 골라도 화면이 새 파일을 읽는다.
    context.filesDir.listFiles { f -> f.name.startsWith("avatar_${driverId}_") }?.forEach { it.delete() }
    val file = File(context.filesDir, "avatar_${driverId}_${System.currentTimeMillis()}.jpg")
    file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    file.path
}
