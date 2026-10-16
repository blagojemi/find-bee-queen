package lab.mgb.findqueen

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import lab.mgb.findqueen.debug.DebugLog
import lab.mgb.findqueen.ui.FindQueenApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.installCrashHandler(this)
        // The screen never dims or sleeps while the app is open (in the foreground);
        // Android lifts this automatically when the user leaves the app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent { FindQueenApp() }
    }
}
