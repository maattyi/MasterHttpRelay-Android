package com.velum.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Last-resort visible error screen. If MainActivity.onCreate throws while
 * wiring up the real composition, we mount this instead of letting the
 * activity die. Ensures the user always sees a diagnostic instead of a
 * blank/closed app.
 */
@Composable
fun ErrorScreen(t: Throwable) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0C0C0C)).padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            "Startup error",
            color = Color(0xFFB2FF05),
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "VelumVPN couldn't initialise on this device. " +
                "The details below help diagnose the cause.",
            color = Color.White.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            Text(
                t.stackTraceToString(),
                color = Color.White.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
