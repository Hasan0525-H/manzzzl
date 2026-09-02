package com.manzzzl.ai.screens

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * First login screen.
 */
@Composable
fun LoginScreen(onLogin: () -> Unit = {}) {
    Text("منزلي AI")
    Button(onClick = onLogin) {
        Text("دخول")
    }
}
