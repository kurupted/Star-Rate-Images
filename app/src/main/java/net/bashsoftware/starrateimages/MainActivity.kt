package net.bashsoftware.starrateimages


import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.common.ImageMetadata
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.IOException

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter
import kotlin.math.roundToInt

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.IntentSender
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

private var tempFilePath: String? = null



class MainActivity : AppCompatActivity() {

    private lateinit var tvNumberOfFiles: TextView
    private lateinit var tvInfo: TextView
    private lateinit var ratingBar: RatingBar
    private lateinit var btnApply: Button
    private lateinit var btnOpenFilePicker: Button
    private var imageUris = mutableListOf<Uri>()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvNumberOfFiles = findViewById(R.id.tvNumberOfFiles)
        tvInfo = findViewById(R.id.tvInfo)
        ratingBar = findViewById(R.id.ratingBar)
        btnApply = findViewById(R.id.btnApply)

        handleIntent(intent)

        btnApply.setOnClickListener {
            applyRatingToImages()
        }

        btnOpenFilePicker = findViewById(R.id.btnOpenFilePicker)
        btnOpenFilePicker.setOnClickListener {
            openFilePicker()
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            Toast.makeText(this, "Note: When sharing files to this app, you may have to save copies instead of updating the originals.", Toast.LENGTH_SHORT).show()

            when {
                intent.action == Intent.ACTION_SEND -> {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        imageUris = mutableListOf()
                        imageUris.add(uri)
                    }
                }
                intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                        imageUris = mutableListOf()
                        imageUris.addAll(uris)
                    }
                }
            }

            // Update UI to reflect the number of images
            updateUiWithImages()

            // Request write access for shared URIs using MediaStore
            // This will prompt the user for permission to modify files you did not create.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requestWriteAccessViaMediaStore(imageUris)
            }
        }
    }


    private fun updateUiWithImages() {
        Log.d("UpdateUI", "Updating UI with ${imageUris.size} images")
        tvNumberOfFiles.text = "Number of files: ${imageUris.size}"

        val urisText = imageUris.joinToString(separator = "\n") { uri ->
            val currentRating = getCurrentRating(uri)
            val displayName = getFileName(uri)
            //val parentFolder = getParentFolderName(uri) ?: "Unknown"
            "$displayName (${currentRating.toStars()})"
        }

        tvInfo.text = urisText
        Log.d("UpdateUI", "UI updated with text: $urisText")
    }


    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/jpeg"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, MODIFY_IMAGE_REQUEST)
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        Log.d("ActivityResult", "onActivityResult called with requestCode: $requestCode, resultCode: $resultCode")
        Log.d("ActivityResult", "Intent data: ${data?.toString()}")

        when (requestCode) {
            MODIFY_IMAGE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.let { handleFilePickerResult(it) }
                } else {
                    Log.e("ActivityResult", "File selection failed or was cancelled")
                    Toast.makeText(this, "File selection failed or was cancelled", Toast.LENGTH_SHORT).show()
                }
            }
            WRITE_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    Toast.makeText(this, "Write access granted. Please retry the update.", Toast.LENGTH_SHORT).show()
                    // Optionally re-run your update for the affected files.
                } else {
                    Toast.makeText(this, "Write access denied.", Toast.LENGTH_SHORT).show()
                }
            }
            SAVE_MODIFIED_FILE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val destinationUri = data.data
                    if (destinationUri != null && tempFilePath != null) {
                        try {
                            val tempFile = File(tempFilePath!!)
                            val fileBytes = tempFile.readBytes()
                            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                                outputStream.write(fileBytes)
                            }
                            showToast(this, "Modified file saved successfully.")
                            tempFile.delete()  // Clean up the temporary file.
                        } catch (ex: Exception) {
                            Log.e("onActivityResult", "Error saving modified file: ${ex.message}")
                            showToast(this, "Failed to save modified file.")
                        }
                    }
                }
            }
            else -> {
                Log.e("ActivityResult", "Unhandled request code: $requestCode")
                Toast.makeText(this, "Unhandled request code", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun handleFilePickerResult(data: Intent) {
        imageUris.clear()

        when {
            data.clipData != null -> {
                val clipData = data.clipData!!
                for (i in 0 until clipData.itemCount) {
                    imageUris.add(clipData.getItemAt(i).uri)
                }
            }
            data.data != null -> {
                imageUris.add(data.data!!)
            }
        }

        Log.d("FilePickerResult", "Selected ${imageUris.size} files")

        if (imageUris.isNotEmpty()) {
            requestWriteAccess()
        } else {
            Log.e("FilePickerResult", "No files selected")
            Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
        }

        updateUiWithImages()
    }



    private fun requestWriteAccess() {
        imageUris.forEach { uri ->
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
                Log.d("WriteAccess", "Took persistable URI permission for $uri")
            } catch (e: SecurityException) {
                Log.e("WriteAccess", "Failed to take persistable URI permission: ${e.message}")
            }
        }
    }


    private fun requestWriteAccessViaMediaStore(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                // Create a write request for the given URIs.
                val writeRequest = MediaStore.createWriteRequest(contentResolver, uris)
                val intentSender = writeRequest.intentSender
                // Launch the permission prompt.
                startIntentSenderForResult(intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0)
            } catch (e: Exception) {
                Log.e("RequestWriteAccess", "Error requesting write permission via MediaStore: ${e.message}")
            }
        } else {
            Log.e("RequestWriteAccess", "MediaStore.createWriteRequest is not available below Android R.")
        }
    }




    private fun getContentUriFromFileUri(uri: Uri): Uri? {
        Log.d("ContentUri", "Converting URI: $uri")
        return when {
            DocumentsContract.isDocumentUri(this, uri) -> {
                Log.d("ContentUri", "Is document URI")
                uri
            }
            uri.scheme == "content" -> {
                Log.d("ContentUri", "Is content URI")
                uri
            }
            else -> {
                Log.d("ContentUri", "Attempting to get content URI from file URI")
                val projection = arrayOf(MediaStore.Images.Media._ID)
                contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Images.Media.DATA} = ?",
                    arrayOf(uri.path),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    } else {
                        Log.e("ContentUri", "Failed to get content URI")
                        null
                    }
                }
            }
        }
    }



    private fun extractRatingFromXmp(xmpXml: String?): Int {
        if (xmpXml == null) return 0

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmpXml.byteInputStream())
        val rdfNamespaceUri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xmpNamespaceUri = "http://ns.adobe.com/xap/1.0/"

        val rdfDescriptions = doc.getElementsByTagNameNS(rdfNamespaceUri, "Description")

        if (rdfDescriptions.length == 0) return 0

        for (i in 0 until rdfDescriptions.length) {
            val rdfDescription = rdfDescriptions.item(i) as Element
            val ratingString = rdfDescription.getAttributeNS(xmpNamespaceUri, "Rating")
            if (ratingString.isNotEmpty()) {
                return ratingString.toIntOrNull() ?: 0
            }
        }
        return 0
    }


    private fun getCurrentRating(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileData = inputStream.readBytes()
                val xmpXml = Imaging.getXmpXml(fileData)
                extractRatingFromXmp(xmpXml)
            } ?: 0
        } catch (e: Exception) {
            Log.e("getCurrentRating", "Exception occurred: ${e.message}")
            0
        }
    }

    private fun getFileName(uri: Uri): String {
        Log.d("FileName", "Getting filename for URI: $uri")
        return when (uri.scheme) {
            "content" -> {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            cursor.getString(displayNameIndex)
                        } else {
                            Log.e("FileName", "DISPLAY_NAME column not found")
                            uri.lastPathSegment ?: "Unknown"
                        }
                    } else {
                        Log.e("FileName", "Cursor is empty")
                        "Unknown"
                    }
                } ?: run {
                    Log.e("FileName", "Query returned null")
                    "Unknown"
                }
            }
            "file" -> uri.lastPathSegment ?: "Unknown"
            else -> {
                Log.e("FileName", "Unhandled URI scheme: ${uri.scheme}")
                "Unknown"
            }
        }.also { Log.d("FileName", "Filename: $it") }
    }


    private fun getParentFolderName(uri: Uri): String? {
        val pathSegments = uri.pathSegments
        return if (pathSegments.size > 1) pathSegments[pathSegments.size - 2] else null
    }

    private fun Int.toStars(): String {
        return "★".repeat(this)
    }


    private fun applyRatingToImages() {
        modifySharedFiles(applicationContext)
        refreshFileList()
        Toast.makeText(this, "Ratings applied successfully", Toast.LENGTH_SHORT).show()
    }

    private fun refreshFileList() {
        updateUiWithImages()
    }

    fun modifyXmpXml(xmpXml: String?, newRating: Int): String {
        if (xmpXml == null) return ""

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmpXml.byteInputStream())
        val rdfNamespaceUri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xmpNamespaceUri = "http://ns.adobe.com/xap/1.0/"

        val rdfDescription = doc.getElementsByTagNameNS(rdfNamespaceUri, "Description").run {
            if (this.length > 0) this.item(0) as Element else null
        }

        if (rdfDescription != null) {
            rdfDescription.setAttributeNS(xmpNamespaceUri, "xmp:Rating", newRating.toString())
        } else {
            val newRdfDescription = doc.createElementNS(rdfNamespaceUri, "rdf:Description").apply {
                setAttributeNS(xmpNamespaceUri, "xmp:Rating", newRating.toString())
            }
            doc.documentElement.appendChild(newRdfDescription)
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        val writer = StringWriter().apply {
            transformer.transform(DOMSource(doc), StreamResult(this))
        }

        return writer.toString()
    }

    fun createXmpXmlWithRating(rating: Int): String {
        return """
        <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XMP Core 5.1.2">
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                     xmp:Rating="$rating">
                </rdf:Description>
            </rdf:RDF>
        </x:xmpmeta>
    """.trimIndent()
    }

    private fun modifySharedFiles(context: Context) {
        imageUris.forEach { uri ->
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    // Try to take persistable permission; if it fails, log and continue.
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: SecurityException) {
                    Log.e("modifySharedFiles", "Could not take persistable permission for ${getFileName(uri)}: ${e.message}")
                    // Continue processing; we'll handle write issues later.
                }

                // Check if the file is a valid JPEG.
                val mimeType = context.contentResolver.getType(uri)
                if (mimeType != "image/jpeg") {
                    Log.e("modifySharedFiles", "Skipping non-JPEG file: $uri")
                    showToast(context, "Skipping non-JPEG file: ${getFileName(uri)}")
                    return@forEach
                }

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val imageData = inputStream.readBytes()
                    val xmpXml = Imaging.getXmpXml(imageData)
                    val newRating = ratingBar.rating.toInt()
                    val modifiedXmp = if (xmpXml != null) {
                        modifyXmpXml(xmpXml, newRating)
                    } else {
                        createXmpXmlWithRating(newRating)
                    }

                    if (modifiedXmp.isNotEmpty()) {
                        val jpegXmpRewriter = org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter()
                        ByteArrayOutputStream().use { outputStream ->
                            jpegXmpRewriter.updateXmpXml(imageData, outputStream, modifiedXmp)
                            val modifiedImageData = outputStream.toByteArray()

                            if (modifiedImageData.isNotEmpty()) {
                                updateMediaStoreFile(context, uri, modifiedImageData)
                            }
                        }
                    }
                }
                Log.d("modifySharedFiles", "Successfully modified file: ${getFileName(uri)}")
            } catch (e: SecurityException) {
                Log.e("modifySharedFiles", "Permission denied for file: ${getFileName(uri)}")
                showToast(context, "Permission denied for file: ${getFileName(uri)}")
            } catch (e: IOException) {
                Log.e("modifySharedFiles", "IOException occurred: ${e.message}")
                showToast(context, "IOException occurred: ${e.message}")
            } catch (e: org.apache.commons.imaging.ImageReadException) {
                Log.e("modifySharedFiles", "ImageReadException occurred: ${e.message}")
                showToast(context, "File is not a valid JPEG: ${getFileName(uri)}")
            }
        }
        refreshFileList()
    }


    private fun fallbackUpdateSharedFile(originalUri: Uri, modifiedImageData: ByteArray) {
        try {
            // Write modified data to a temporary file in the cache directory.
            val tempFile = File.createTempFile("modified_", ".jpg", cacheDir)
            FileOutputStream(tempFile).use { fos ->
                fos.write(modifiedImageData)
            }
            tempFilePath = tempFile.absolutePath

            // Launch ACTION_CREATE_DOCUMENT to let the user choose where to save the modified file.
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TITLE, getFileName(originalUri) ?: "modified.jpg")
            }
            startActivityForResult(intent, SAVE_MODIFIED_FILE_REQUEST)
        } catch (ex: Exception) {
            Log.e("fallbackUpdateSharedFile", "Error in fallback file update: ${ex.message}")
            showToast(this, "Failed to save modified file via fallback.")
        }
    }


    private fun updateMediaStoreFile(context: Context, uri: Uri, imageData: ByteArray) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(imageData)
            } ?: run {
                Log.e("updateMediaStoreFile", "Output stream is null for URI: $uri")
                showToast(context, "Unable to open file for writing.")
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                try {
                    // Use getUserAction() to obtain the intent sender.
                    val intentSender = e.userAction.actionIntent.intentSender
                    startIntentSenderForResult(intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0)
                } catch (ex: Exception) {
                    Log.e("updateMediaStoreFile", "Error requesting write permission: ${ex.message}")
                    // Fallback if the permission request fails.
                    fallbackUpdateSharedFile(uri, imageData)
                }
            } else {
                Log.e("updateMediaStoreFile", "Failed to write to MediaStore file: $e")
                // For any non-recoverable SecurityException, try fallback.
                fallbackUpdateSharedFile(uri, imageData)
            }
        } catch (ioe: IOException) {
            Log.e("updateMediaStoreFile", "Failed to write to MediaStore file: $ioe")
        }
    }



    // todo: could use this to find files being shared to app by looking for them in the mediastore, but it's by filename so may no be correct...
    private fun findMediaStoreUriByFilename(context: Context, filename: String): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val id = cursor.getLong(idColumn)
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        return null
    }



    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MODIFY_IMAGE_REQUEST = 1
        private const val WRITE_REQUEST_CODE = 2
        private const val SAVE_MODIFIED_FILE_REQUEST = 4
    }
}
