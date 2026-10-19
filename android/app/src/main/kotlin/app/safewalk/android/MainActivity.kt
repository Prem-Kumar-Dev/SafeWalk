package app.safewalk.android

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.safewalk.android.ui.SafeWalkRoot
import app.safewalk.android.ui.SafeWalkTheme
import app.safewalk.android.ui.SafeWalkViewModel

class MainActivity : ComponentActivity() {
    private val vm: SafeWalkViewModel by viewModels()

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.onPermissionResult(r.values.any { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SafeWalkTheme {
                SafeWalkRoot(
                    vm = vm,
                    requestLocation = {
                        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                    exit = { finish() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }
}
