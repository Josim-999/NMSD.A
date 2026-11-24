package com.videostudio.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val Accent = Color(0xFFFFD23F)
val Accent2 = Color(0xFF00E5FF)
val Bg = Color(0xFF0E0F1A)
val Surface1 = Color(0xFF181A2B)
val Surface2 = Color(0xFF22253A)
val Danger = Color(0xFFFF5266)
val Good = Color(0xFF3DFFA0)
val Muted = Color(0xFFA9ABBE)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Accent2,
    onSecondary = Color.Black,
    background = Bg,
    onBackground = Color.White,
    surface = Bg,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFC9CBDA),
    error = Danger,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    surfaceContainerLow = Surface1,
    surfaceContainerLowest = Bg,
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

/** A rounded card with a small heading. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface1)
            .padding(16.dp)
    ) {
        Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

@Composable
fun Hint(text: String, color: Color = Muted) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = Color.Black,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

/** Loads [file] as a downscaled image, reloading when the file changes. */
@Composable
fun rememberImage(file: File, maxWidth: Int = 540): ImageBitmap? {
    var img by remember(file.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    val stamp = if (file.exists()) file.lastModified() else 0L
    LaunchedEffect(file.absolutePath, stamp) {
        img = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return img
}
