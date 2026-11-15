package com.safelog.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 아직 만들지 않은 하위 화면(S32 퀴즈). 위쪽은 공식 Depth 제목이다. */
@Composable
fun PlaceholderScreen(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) { DepthHeader(title, onBack) }
}
