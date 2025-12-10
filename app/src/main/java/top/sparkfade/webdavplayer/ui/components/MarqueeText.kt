package top.sparkfade.webdavplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        textAlign = textAlign,
        modifier = modifier
            .fillMaxWidth()
            .basicMarquee() // 恢复默认无参调用
    )
}