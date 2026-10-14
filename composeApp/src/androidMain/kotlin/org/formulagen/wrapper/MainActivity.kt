package org.formulagen.wrapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.google.firebase.Firebase
import com.google.firebase.initialize
import org.formulagen.wrapper.hovel.HovelAndroidApp
import org.formulagen.wrapper.hovel.hovelWebUrl
import org.formulagen.wrapper.platform.AppContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Firebase.initialize(context = this)
        AppContext.init(this)
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