package com.cauce.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cauce.core.ui.CauceTheme

class CauceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CauceTheme {
                CauceNavHost()
            }
        }
    }
}
