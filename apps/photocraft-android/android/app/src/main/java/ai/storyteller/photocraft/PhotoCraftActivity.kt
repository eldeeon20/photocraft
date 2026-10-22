package ai.storyteller.photocraft

import android.Manifest
import android.app.AlertDialog
import android.app.NativeActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * NativeActivity with a thin Kotlin shell: asks for storage access on launch (the media grant
 * on Android 13+, "All files access" in Settings on 11+) and hides the system bars. The UI
 * itself is still the original egui one; nothing here draws over it.
 */
class PhotoCraftActivity : NativeActivity() {

    private var prompting = false
    private var declined = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForStorage()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Back from Settings without the grant: ask again instead of leaving the picker unable
        // to list the phone's pictures.
        askForStorage()
    }

    /// Android 9/10 read and write the shared storage with the legacy storage permissions;
    /// writing is what saving to /sdcard/Pictures needs, so both are requested together.
    private val legacyPermissions = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    private fun hasStorage(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> legacyPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun askForStorage() {
        if (prompting || declined || hasStorage()) return
        prompting = true
        AlertDialog.Builder(this)
            .setTitle("Storage access")
            .setMessage(
                "PhotoCraft needs access to your files to open and save images.\n\n" +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        "Android 11+ grants this in Settings: open it and switch on " +
                            "\"Allow access to all files\" for PhotoCraft."
                    } else {
                        "Allow storage access so PhotoCraft can list your pictures and save to them."
                    }
            )
            .setPositiveButton(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "Open settings" else "Allow") { _, _ ->
                requestStorage()
            }
            .setNeutralButton("App settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Not now") { _, _ -> declined = true }
            .setOnDismissListener { prompting = false }
            .show()
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            try {
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(legacyPermissions, REQ_STORAGE)
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_STORAGE && (results.isEmpty() || results.any { it != PackageManager.PERMISSION_GRANTED })) {
            askForStorage()
        }
    }

    /// Immersive mode: the canvas owns the screen; a swipe from the edge brings the bars back.
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private companion object {
        const val REQ_STORAGE = 1
    }
}
