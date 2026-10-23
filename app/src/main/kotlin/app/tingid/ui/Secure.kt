// What shows a secret keeps it on this screen: no screenshot, no screen
// recording, no picture in the recent apps, while it shows; and where one is
// typed, no autofill service reads it.
package app.tingid.ui

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Blocks screenshots and recordings of the window while this is in the
 * composition. From Android 14 on, the screen's content is also marked
 * sensitive for accessibility: services that are not accessibility tools,
 * such as an app reading the screen to fill forms, get none of it, while a
 * screen reader still reads the words to a person who needs it.
 */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current.window
    val view = LocalView.current
    DisposableEffect(window, view) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
        }
    }
}

/**
 * Keeps the phone's autofill service, such as a password manager, away from
 * the screen while this is in the composition: recovery words typed here are
 * neither offered nor saved anywhere.
 */
@Composable
fun NoAutofill() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = before }
    }
}
