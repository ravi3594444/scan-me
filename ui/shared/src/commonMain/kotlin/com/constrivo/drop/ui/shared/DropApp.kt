package com.constrivo.drop.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.constrivo.drop.ui.shared.theme.DropTheme
import com.constrivo.drop.ui.shared.theme.DropType
import com.constrivo.drop.ui.shared.theme.LocalDropColors

/** Root composable both apps show. The radar replaces this placeholder in WP8. */
@Composable
fun DropApp() {
    DropTheme {
        val colors = LocalDropColors.current
        Column(
            modifier = Modifier.fillMaxSize().background(colors.bg).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nearby", style = DropType.title, color = colors.text)
            Text(
                "No one nearby yet. Ask them to open the app, or scan their code.",
                style = DropType.body,
                color = colors.textMuted,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
