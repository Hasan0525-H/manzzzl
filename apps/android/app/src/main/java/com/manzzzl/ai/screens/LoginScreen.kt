package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(onLogin: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "منزلي AI",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "حوّل مخطط منزلك إلى تصميم ثلاثي الأبعاد"
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onLogin) {
            Text("ابدأ التصميم")
        }
    }
}
