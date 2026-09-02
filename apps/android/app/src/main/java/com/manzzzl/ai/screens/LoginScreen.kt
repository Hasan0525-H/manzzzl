package com.manzzzl.ai.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(onLogin: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⌂",
                    style = MaterialTheme.typography.displayLarge
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "منزلي AI",
                    style = MaterialTheme.typography.headlineLarge
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "صمّم منزلك بالذكاء الاصطناعي\nمن المخطط ثنائي الأبعاد إلى نموذج ثلاثي الأبعاد"
                )

                Spacer(modifier = Modifier.height(28.dp))

                Button(onClick = onLogin) {
                    Text("ابدأ تصميم منزلي")
                }
            }
        }
    }
}
