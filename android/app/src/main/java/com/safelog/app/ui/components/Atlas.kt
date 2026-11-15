package com.safelog.app.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import com.safelog.app.R

/** 꾸짖는 표정은 없다(기획서 F9). */
enum class AtlasMood(@DrawableRes val image: Int) {
    Default(R.drawable.atlas_default),
    Joy(R.drawable.atlas_joy),
    Cheer(R.drawable.atlas_cheer),
    Surprise(R.drawable.atlas_surprise),
}

@Composable
fun AtlasImage(mood: AtlasMood, size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(mood.image),
        contentDescription = "캐릭터",
        modifier = modifier.size(size),
    )
}

/** 카드 안에서 남는 자리를 채우는 아틀라스 */
@Composable
fun AtlasFill(mood: AtlasMood, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(mood.image),
        contentDescription = "캐릭터",
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * 운전하는 아틀라스. [trigger]가 바뀔 때마다 움직이는 WebP(61프레임, 초당 30프레임, 약 2초)를 한 번만 재생하고,
 * 끝나면 첫 프레임 정지 이미지로 돌아간다. 마지막 프레임이 정지 이미지와 같아서 돌아갈 때 튀지 않는다.
 * 재생은 안드로이드 기본 AnimatedImageDrawable(API 28 이상)이 맡는다.
 * 재생하지 않는 동안에는 정지 이미지 한 장이라 그리기 비용이 없다.
 */
@Composable
fun AtlasDriving(trigger: Int, modifier: Modifier = Modifier) {
    var playing by remember { mutableStateOf(false) }
    var startedFor by remember { mutableIntStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (trigger != startedFor) {
            startedFor = trigger
            playing = true
        }
    }
    // 화면 이미지 시험(Paparazzi)에서는 재생할 수 없어 정지 이미지를 그린다.
    if (!playing || LocalInspectionMode.current) {
        Image(painterResource(R.drawable.atlas_drive_still), contentDescription = "캐릭터", contentScale = ContentScale.Fit, modifier = modifier)
        return
    }
    key(startedFor) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                ImageView(context).apply {
                    contentDescription = "캐릭터"
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, R.raw.atlas_drive))
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = 0 // 한 번만
                        drawable.registerAnimationCallback(
                            object : Animatable2.AnimationCallback() {
                                override fun onAnimationEnd(d: Drawable?) {
                                    playing = false
                                }
                            },
                        )
                        setImageDrawable(drawable)
                        drawable.start()
                    } else {
                        setImageDrawable(drawable)
                        playing = false
                    }
                }
            },
        )
    }
}
