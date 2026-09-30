package one.monero.moneroone.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.monero.moneroone.core.locale.tr
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The QR image out of the app: into the Pictures collection (iOS Save to
 * Photos) and into the share sheet. The image is iOS QRCodeRenderer's: the
 * code with its one-module margin and the logo, 1200 px square.
 */
object QrExport {
    private const val TAG = "QrExport"

    /** Writes the code to Pictures. Call off the main thread. Below Android 10 it needs the storage permission. */
    fun saveToPictures(context: Context, content: String): Boolean {
        val image = QrCodes.image(context, content) ?: return false
        val name = "Monero QR ${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) savePending(context, image, name)
            else saveFile(context, image, name)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Save to Pictures failed: ${e.javaClass.simpleName}")
            false
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun savePending(context: Context, image: Bitmap, name: String) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("insert")
        try {
            resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IOException("open")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveFile(context: Context, image: Bitmap, name: String) {
        val folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        if (!folder.exists() && !folder.mkdirs()) throw IOException("mkdirs")
        val file = File(folder, name)
        FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
    }

    /** The code as a PNG the share sheet can read, or null. Call off the main thread. */
    fun shareableImage(context: Context, content: String): Uri? {
        val image = QrCodes.image(context, content) ?: return null
        return try {
            val folder = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(folder, "monero-qr.png")
            FileOutputStream(file).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.qrshare", file)
        } catch (e: Exception) {
            Log.w(TAG, "Share image failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The share sheet with the code's image and, when given, [text] (iOS ReceiveView's share items). */
    fun share(context: Context, image: Uri?, text: String?) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            if (image != null) {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, image)
                clipData = ClipData.newRawUri(null, image)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
        }
        if (image == null && text == null) return
        context.startActivity(Intent.createChooser(intent, null))
    }
}

/**
 * Save to Photos for a code (iOS ReceiveView.saveQRToPhotos): asks for the
 * storage permission first below Android 10. A short tick and a toast when
 * it is saved; the error haptic and how to fix it when it is not.
 */
@Composable
fun rememberSaveQrToPhotos(): (String) -> Unit {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf<String?>(null) }

    fun save(content: String) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { QrExport.saveToPictures(context.applicationContext, content) }
            if (saved) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                Toast.makeText(context, tr("QR code saved to Photos"), Toast.LENGTH_SHORT).show()
            } else {
                view.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
                    else HapticFeedbackConstants.LONG_PRESS
                )
                Toast.makeText(context, tr("Couldn't save the QR code. Allow Photos access in Settings."), Toast.LENGTH_LONG).show()
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val content = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (granted) save(content) else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            Toast.makeText(context, tr("Couldn't save the QR code. Allow Photos access in Settings."), Toast.LENGTH_LONG).show()
        }
    }

    return { content ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            waiting = content
            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(content)
        }
    }
}

/** Opens the share sheet with the code's image and [text]; the image is made off the main thread. */
@Composable
fun rememberShareQr(): (content: String, text: String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { content, text ->
        scope.launch {
            val image = withContext(Dispatchers.IO) { QrExport.shareableImage(context.applicationContext, content) }
            QrExport.share(context, image, text)
        }
    }
}
