package ai.storyteller.photocraft

import android.Manifest
import android.app.AlertDialog
import android.app.NativeActivity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File

/**
 * NativeActivity with a thin Kotlin shell. The UI is the original egui one; this class only asks
 * for storage access, hides the system bars and answers the file dialogs Rust writes as requests
 * under `filesDir/PhotoCraft/req`: the system picker (SAF) chooses a file, its contents are copied
 * into the app sandbox, and the result is written to `filesDir/PhotoCraft/res` for Rust to read.
 * That works without "All files access" on every Android version.
 */
class PhotoCraftActivity : NativeActivity() {

    private var prompting = false
    private var declined = false

    private val root: File by lazy { File(filesDir, "PhotoCraft").apply { mkdirs() } }
    private val reqDir: File by lazy { File(root, "req").apply { mkdirs() } }
    private val resDir: File by lazy { File(root, "res").apply { mkdirs() } }

    private var pendingSave: PendingSave? = null

    private val poller = object : Runnable {
        override fun run() {
            pollRequests()
            handler.postDelayed(this, 250)
        }
    }
    private val handler = Handler(Looper.getMainLooper())

    private data class PendingSave(val staged: File, val name: String, val mime: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Landscape only: the canvas is laid out for the wide side of the screen, and rotating
        // mid-edit would resize the whole workspace. Locked before super so the first frame
        // never starts portrait.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
        // Rust reads this to tell whether the shell is really running in the same data dir.
        runCatching { File(root, "boot.txt").writeText("alive ${filesDir.absolutePath}") }
        askForStorage()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        handler.post(poller)
        // Back from Settings without the grant: ask again instead of leaving raw paths unusable.
        askForStorage()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    // ---- Rust -> Kotlin requests -------------------------------------------------------------

    private fun pollRequests() {
        val open = File(reqDir, "open.req")
        if (open.exists()) {
            val mime = open.readText().lineSequence().firstOrNull()?.trim().orEmpty()
            open.delete()
            if (mime == "folder") {
                launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    REQ_FOLDER,
                )
            } else {
                launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mime.ifEmpty { "*/*" }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    REQ_OPEN,
                )
            }
            return
        }

        val save = File(reqDir, "save.req")
        if (save.exists()) {
            // Lines: staged path, suggested name, mime type.
            val lines = save.readLines()
            save.delete()
            if (lines.size >= 2) {
                val staged = File(lines[0])
                val name = lines[1]
                val mime = lines.getOrElse(2) { mimeOf(name) }
                pendingSave = PendingSave(staged, name, mime)
                launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mime
                        putExtra(Intent.EXTRA_TITLE, name)
                    },
                    REQ_SAVE,
                )
            } else {
                answerSave("ERR malformed save request")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OPEN -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) {
                    answerOpen("CANCEL")
                    return
                }
                try {
                    val name = displayName(uri) ?: "opened"
                    val inbox = File(root, "inbox").apply { mkdirs() }
                    val copy = unique(inbox, name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        copy.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("empty stream")
                    answerOpen("OK\n${copy.absolutePath}\n$name")
                } catch (e: Exception) {
                    answerOpen("ERR ${e.message}")
                }
            }
            REQ_SAVE -> {
                val pending = pendingSave
                pendingSave = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null || pending == null) {
                    answerSave("CANCEL")
                    return
                }
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        pending.staged.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("empty stream")
                    pending.staged.delete()
                    answerSave("OK\n${pending.name}")
                } catch (e: Exception) {
                    answerSave("ERR ${e.message}")
                }
            }
            REQ_FOLDER -> {
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null) {
                    answerFolder("CANCEL")
                    return
                }
                val path = treePath(uri)
                if (path != null) answerFolder("OK\n$path") else answerFolder("ERR that folder is not on shared storage")
            }
        }
    }

    /** Starts a system picker, reporting instead of dying when the system refuses it. */
    private fun launch(intent: Intent, code: Int) {
        try {
            startActivityForResult(intent, code)
        } catch (e: Exception) {
            val message = "ERR ${e.javaClass.simpleName}: ${e.message}"
            when (code) {
                REQ_OPEN -> answerOpen(message)
                REQ_SAVE -> answerSave(message)
                REQ_FOLDER -> answerFolder(message)
            }
        }
    }

    private fun answerOpen(result: String) {
        if (result.startsWith("ERR")) Toast.makeText(this, result.removePrefix("ERR "), Toast.LENGTH_LONG).show()
        File(resDir, "open.result").writeText(result)
    }

    private fun answerSave(result: String) {
        if (result.startsWith("OK")) {
            Toast.makeText(this, "Saved ${result.lineSequence().drop(1).firstOrNull() ?: ""}".trim(), Toast.LENGTH_LONG).show()
        } else if (result.startsWith("ERR")) {
            Toast.makeText(this, result.removePrefix("ERR "), Toast.LENGTH_LONG).show()
        }
        File(resDir, "save.result").writeText(result)
    }

    private fun answerFolder(result: String) {
        File(resDir, "folder.result").writeText(result)
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun unique(dir: File, name: String): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (target.exists()) {
            target = File(dir, "$stem-$i$ext")
            i++
        }
        return target
    }

    /** `content://.../tree/primary:Pictures` -> `/storage/emulated/0/Pictures`; only shared storage. */
    private fun treePath(uri: Uri): String? {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val parts = id.split(':', limit = 2)
        val volume = parts[0]
        val rest = parts.getOrElse(1) { "" }
        if (volume != "primary") return null
        val base = Environment.getExternalStorageDirectory().absolutePath
        return if (rest.isEmpty()) base else "$base/$rest"
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "tif", "tiff" -> "image/tiff"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "psd", "psb" -> "image/vnd.adobe.photoshop"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    // ---- Storage access ----------------------------------------------------------------------

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
                "PhotoCraft opens and saves pictures with Android's file picker, so it works " +
                    "without extra permissions.\n\n" +
                    "Granting access to all files lets it browse folders directly and save to " +
                    "Pictures/PhotoCraft without asking each time."
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
        const val REQ_OPEN = 2
        const val REQ_SAVE = 3
        const val REQ_FOLDER = 4
    }
}
