package org.formulagen.wrapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.formulagen.wrapper.hovel.HovelAndroidApp
import org.formulagen.wrapper.hovel.hovelWebUrl

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val hovelUrl = hovelWebUrl(intent, this)
        setContent {
            if (hovelUrl != null) HovelAndroidApp(hovelUrl) else App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}