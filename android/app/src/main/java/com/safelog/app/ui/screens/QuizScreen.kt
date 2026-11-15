package com.safelog.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import com.safelog.app.ui.components.EaseOut
import com.safelog.app.ui.components.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.safelog.app.model.Quiz
import com.safelog.app.ui.components.AtlasImage
import com.safelog.app.ui.components.AtlasMood
import com.safelog.app.ui.components.CardShape
import com.safelog.app.ui.components.PopCheck
import com.safelog.app.ui.components.Text
import com.safelog.app.ui.theme.SafeLogTheme
import com.safelog.app.ui.theme.SafeLogType

/**
 * S32 오늘의 퀴즈. 흐름 화면이라 가로 창에서도 가운데 한 칸이다: 문제 → 보기 → 결과.
 * 보기 행 전체를 눌러 답하면 정답 행에 체크가 튀고 받은 포인트와 해설이 나온다. 한 번만 답한다.
 * 기어를 D로 넣으면 앱이 주행 화면으로 바꾼다.
 */
@Composable
fun QuizScreen(quiz: Quiz, onAnswer: (Int) -> Unit, onBack: () -> Unit) {
    val colors = SafeLogTheme.colors
    val answered = quiz.picked != null
    val correct = quiz.picked == quiz.answer
    FlowScreen("오늘의 퀴즈", onBack) {
        Row(
            Modifier.fillMaxWidth().clip(CardShape).background(colors.surface).padding(CARD_PADDING),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CARD_PADDING),
        ) {
            AtlasImage(if (answered && correct) AtlasMood.Joy else AtlasMood.Default, 112.dp)
            Text(quiz.question, style = SafeLogType.title, color = colors.ink, modifier = Modifier.weight(1f))
        }
        ListCard(title = if (answered) "결과" else "하나를 골라요") {
            quiz.choices.forEachIndexed { i, choice ->
                val isAnswer = i == quiz.answer
                ListRow(
                    choice,
                    Modifier.pressSelect(quiz.picked == i, enabled = !answered, role = Role.RadioButton) { onAnswer(i) },
                    enabled = !answered || isAnswer || quiz.picked == i,
                    leading = { Text("${i + 1}", style = SafeLogType.bodyLarge, color = colors.inkMuted, modifier = Modifier.width(32.dp)) },
                ) {
                    // 답하기 전에는 모든 행에 빈 원. 답하면 정답 행의 원이 팝 버스트로 채워진다.
                    if (answered && quiz.picked == i && !isAnswer) {
                        Chip("내 답", colors.inkMuted)
                    } else {
                        PopCheck(answered && isAnswer, colors.accent, colors.onAccent, colors.surfaceHigh)
                    }
                }
            }
        }
        // 답하면 결과 카드가 아래로 펼쳐지며 나타난다.
        AnimatedVisibility(answered, enter = fadeIn(tween(Motion.POPUP_IN)) + expandVertically(tween(Motion.POPUP_IN, easing = EaseOut))) {
            StatCard(if (correct) "정답이에요" else "정답은 ${quiz.answer + 1}번", "+${quiz.reward}P", colors.reward) {
                Text(quiz.explanation, style = SafeLogType.bodyStrong, color = colors.ink)
            }
        }
    }
}
