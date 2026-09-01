package com.manzzzl.ai.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun LoginScreen(onEnter: () -> Unit) {
    Button(onClick = onEnter) {
        Text("دخول")
    }
}
