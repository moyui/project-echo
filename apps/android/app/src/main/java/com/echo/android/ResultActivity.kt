package com.echo.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.echo.android.ui.CaptureStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 识别结果页：悬浮球抓取的截图叠加译文 + 逐条原文/译文对照 */
class ResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { com.echo.android.ui.EchoTheme { ResultScreen() } }
    }
}

@Composable
fun ResultScreen() {
    val context = LocalContext.current
    val captured = CaptureStore.latest

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                    Text("返回")
                }
                Text(
                    "识别结果",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                captured?.let {
                    Text(
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.atMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (captured == null) {
                Text(
                    "还没有识别结果。开启悬浮球后，在任意界面点击小球翻译。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                com.echo.android.ui.ResultList(captured.results)
            }
        }
    }
}
