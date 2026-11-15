package com.safelog.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Pleos 공식 앱 창 크기(dp), Figma "Form factors & Display"
 *                가로 창        세로 창
 * Connect-S-17   1700x1184     840x1184  (기본)
 * Connect-S-14.6 1360x948      672x948
 * Connect-L-12.9 1274x855      628x855
 * Connect-W-Std  1640x810      800x810
 * Connect-W-Ext  1640x1400     800x1400
 * Connect-WF     1256x800      648x800
 *
 */

/** 창 폭이 이보다 넓으면 가로 창 배치. 세로 창(최대 840)과 가로 창(최소 1256) 사이의 값이다. */
val WIDE_BREAKPOINT = 1000.dp

/*
 * 배치 규칙. 모든 화면이 이 값만 쓴다.
 * - 화면 가장자리: 가로 창 24, 세로 창 16
 * - 칸과 칸 사이(가로): 16. 카드와 카드 사이(세로): 12
 * - 카드 안 여백: 24. 카드 모서리 24, 팝업 모서리 48
 * - 흐름 화면(퀴즈·설정·첫 설정·운전자)은 가로 창에서도 한 칸, 폭 최대 840(세로 창 최대 폭)
 * - 모아 보기 화면(홈·기록·캐릭터)은 가로 창에서 왼쪽 패널(창 높이를 채우는 카드 하나) + 오른쪽 목록
 *
 * 글자 규칙(공식 글자 표 안에서 역할을 고정)
 * - 화면 제목 title 36 · 화면의 주인공 숫자 하나 number 60 · 둘째 숫자 numberMedium 48, 좁으면 numberCompact 40
 * - 섹션 제목 label 24(secondary) · 카드 안 이름표 label 24(tertiary)
 * - 카드 제목 bodyLarge 30 굵게 · 행 제목 body 26 보통 · 값 bodyStrong 26 굵게 · 행 설명 label 24(tertiary) · 가격 bodyLarge 30
 * - 위아래로 붙은 글줄 사이 8(LINE_GAP)
 * - 고르는 항목도 박스 없이 행으로 두고 오른쪽 체크로 고른다(카드 안에 카드를 넣지 않는다). 한 질문의 선택지는 한 카드에 모은다
 * - labelSmall 20은 꼬리표와 달력 요일·날짜 아래에만
 */
fun screenInset(wide: Boolean) = if (wide) 24.dp else 16.dp
val COLUMN_GAP = 16.dp
val STACK_GAP = 12.dp
val CARD_PADDING = 24.dp
/** 위아래로 붙은 글줄(이름표·제목·설명) 사이 */
val LINE_GAP = 8.dp // 휴대폰 15pt 글자에 4pt를 띄우는 비율을 차 글자(24~26)에 맞춘 값
val FLOW_MAX_WIDTH = 840.dp

/** 가장 넓은 창에서 줄이 지나치게 길어지지 않게 하는 내용 폭 상한 */
private val MAX_CONTENT_WIDTH = 1280.dp

/**
 * 스크롤되는 화면 본문. 내용이 창보다 작으면(W-Extended, S-17 같은 큰 창) 세로 가운데에 둔다.
 * 내용이 넘치면 위에서부터 스크롤한다.
 */
@Composable
fun ScreenBody(
    inset: Dp,
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxWidth().padding(inset),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content,
            )
        }
    }
}
