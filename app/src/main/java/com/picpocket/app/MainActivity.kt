package com.picpocket.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.picpocket.app.drive.BiometricGate
import com.picpocket.app.ui.navigation.PicPocketNavGraph
import com.picpocket.app.ui.theme.PicPocketTheme
import com.picpocket.app.ui.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var biometricGate: BiometricGate

    private var authenticated by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (biometricGate.isRequired()) {
            biometricGate.authenticate(
                activity = this,
                onSuccess = { authenticated = true },
                onFallback = { authenticated = true },
            )
        } else {
            authenticated = true
        }

        setContent {
            PicPocketTheme(themeManager = themeManager) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    if (authenticated) {
                        val navController = rememberNavController()
                        PicPocketNavGraph(navController = navController)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Authenticating...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
