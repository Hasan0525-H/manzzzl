package com.manzzzl.ai.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun LoginScreenCompose(onLogin: () -> Unit) {
    Button(onClick = onLogin) {
        Text("دخول")
    }
}
