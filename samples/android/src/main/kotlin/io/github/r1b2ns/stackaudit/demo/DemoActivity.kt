package io.github.r1b2ns.stackaudit.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.r1b2ns.stackaudit.StackAudit
import kotlinx.coroutines.launch

class DemoActivity : ComponentActivity() {
    private val runner = DemoRunner()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DemoScreen(runner) }
    }
}

@Composable
private fun DemoScreen(runner: DemoRunner) {
    val results = remember { mutableStateMapOf<String, String>() }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F7F8))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicText("StackAudit Demo", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
        BasicText("Shake the device or tap Open StackAudit.", style = TextStyle(color = Color(0xFF6B7080)))
        Spacer(Modifier.height(8.dp))
        DemoButton("Open StackAudit", primary = true) { StackAudit.show() }
        DemoButton(if (running) "Running…" else "Run all scenarios", primary = true) {
            if (running) return@DemoButton
            running = true
            scope.launch {
                DemoScenarios.all.forEach { results[it.title] = runner.run(it) }
                running = false
            }
        }
        DemoScenarios.all.forEach { scenario ->
            DemoButton(scenario.title, subtitle = results[scenario.title] ?: "${scenario.client} · ${scenario.method}") {
                scope.launch { results[scenario.title] = runner.run(scenario) }
            }
        }
    }
}

@Composable
private fun DemoButton(
    title: String,
    subtitle: String? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val background = if (primary) Color(0xFF3B5BDB) else Color.White
    val text = if (primary) Color.White else Color(0xFF16181D)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicText(title, style = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium))
        subtitle?.let { BasicText(it, style = TextStyle(color = text.copy(alpha = 0.7f), fontSize = 13.sp)) }
    }
}
