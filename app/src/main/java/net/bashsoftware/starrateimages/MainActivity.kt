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
import android.content.IntentSender
import android.provider.MediaStore
import android.provider.OpenableColumns


class MainActivity : AppCompatActivity() {

    private lateinit var tvNumberOfFiles: TextView
    private lateinit var tvInfo: TextView
    private lateinit var ratingBar: RatingBar
    private lateinit var btnApply: Button
    private lateinit var btnOpenFilePicker: Button
    private lateinit var btnOpenDirPicker: Button
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
            when {
                intent.action == Intent.ACTION_SEND -> {
                    // Single image shared
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uri ->
                        imageUris = mutableListOf()
                        imageUris.add(uri)
                    }
                }
                intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                    // Multiple images shared
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                        imageUris = mutableListOf()
                        imageUris.addAll(uris)
                        uris.forEach { uri ->
                        }
                    }
                }
            }
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                intent.clipData?.let { clipData ->
                    imageUris = mutableListOf()
                    for (i in 0 until clipData.itemCount) {
                        val uri = clipData.getItemAt(i).uri
                        imageUris.add(uri)
                    }
                } ?: intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                    imageUris = mutableListOf()
                    imageUris.addAll(uris)
                    uris.forEach { uri ->
                    }
                }
            }


            // Update UI to reflect the number of images
            tvNumberOfFiles.text = "Number of files: ${imageUris.size}"
            val urisText = imageUris.joinToString(separator = "\n") { uri ->
                val currentRating = getCurrentRating(uri)
                val displayName = getFileName(uri) ?: "Unknown"
                val parentFolder = getParentFolderName(uri) ?: "Unknown"
                "/$parentFolder/$displayName (${currentRating.toStars()})"
            }
            tvInfo.text = urisText

        }
    }




    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, MODIFY_IMAGE_REQUEST)
    }



    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == MODIFY_IMAGE_REQUEST && resultCode == Activity.RESULT_OK  && data != null) {
            handleFilePickerResult(data)
        }

        if (requestCode == WRITE_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // User granted write access to the selected files
                // Proceed with modifying the files
            } else {
                // User denied the write access
                Toast.makeText(this, "Write access denied", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun handleFilePickerResult(data: Intent) {

        // Clear the previous list
        imageUris.clear()

        // Handling a single selected image
        data.data?.let { uri ->
            imageUris.add(uri)
        }

        // Handling multiple selected images
        data.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                val imageUri = clipData.getItemAt(i).uri
                imageUris.add(imageUri)
            }
        }

        // Log URIs for debugging
        imageUris.forEach { uri ->
            Log.d("SelectedURI", uri.toString())
        }

        // Request write access for the selected URIs
        if (imageUris.isNotEmpty()) {
            requestWriteAccess()
        }

        tvNumberOfFiles.text = "Number of files: ${imageUris.size}"
        val urisText = imageUris.joinToString(separator = "\n") { uri ->
            val currentRating = getCurrentRating(uri)
            val displayName = getFileName(uri) ?: "Unknown"
            val parentFolder = getParentFolderName(uri) ?: "Unknown"
            "/$parentFolder/$displayName (${currentRating.toStars()})"
        }
        tvInfo.text = urisText
    }



    private fun getCurrentRating(uri: Uri): Int {
        Log.d("getCurrentRating", "Checking rating for URI: $uri")
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val fileData = inputStream.readBytes()
                Log.d("getCurrentRating", "Read ${fileData.size} bytes from URI: $uri")

                val metadata = Imaging.getMetadata(fileData)
                Log.d("getCurrentRating", "Metadata: $metadata")

                val xmpXml = Imaging.getXmpXml(fileData)
                Log.d("getCurrentRating", "XMP data: $xmpXml")

                extractRatingFromXmp(xmpXml)
            } ?: 0
        } catch (e: Exception) {
            Log.e("getCurrentRating", "Exception occurred: ${e.message}")
            0
        }
    }


    private fun extractRatingFromXmp(xmpXml: String?): Int {
        if (xmpXml == null) {
            Log.d("extractRatingFromXmp", "No XMP data found")
            return 0
        }

        Log.d("extractRatingFromXmp", "Parsing XMP data: $xmpXml")

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmpXml.byteInputStream())
        val rdfNamespaceUri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xmpNamespaceUri = "http://ns.adobe.com/xap/1.0/"

        // Access the rdf:Description element properly
        val rdfDescriptions = doc.getElementsByTagNameNS(rdfNamespaceUri, "Description")

        if (rdfDescriptions.length == 0) {
            Log.d("extractRatingFromXmp", "No rdf:Description element found")
            return 0
        }

        for (i in 0 until rdfDescriptions.length) {
            val rdfDescription = rdfDescriptions.item(i) as Element
            val ratingString = rdfDescription.getAttributeNS(xmpNamespaceUri, "Rating")
            if (ratingString.isNotEmpty()) {
                Log.d("extractRatingFromXmp", "Extracted rating: $ratingString")
                return ratingString.toIntOrNull() ?: 0
            }
        }

        Log.d("extractRatingFromXmp", "Rating attribute not found in rdf:Description elements")
        return 0
    }



    private fun getFileName(uri: Uri): String? {
        var fileName: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex != -1) {
                    fileName = it.getString(columnIndex)
                }
            }
        }
        return fileName
    }

    private fun getParentFolderName(uri: Uri): String? {
        val pathSegments = uri.pathSegments
        return if (pathSegments.size > 1) pathSegments[pathSegments.size - 2] else null
    }

    private fun Int.toStars(): String {
        return "★".repeat(this)
    }



    private fun requestWriteAccess() {
        if (imageUris.isNotEmpty()) {
            val intentSender = MediaStore.createWriteRequest(contentResolver, imageUris).intentSender
            try {
                startIntentSenderForResult(intentSender, WRITE_REQUEST_CODE, null, 0, 0, 0)
            } catch (e: IntentSender.SendIntentException) {
                Log.e("requestWriteAccess", "Error requesting write access: $e")
            }
        } else {
            Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
        }
    }





    companion object {
        private const val MODIFY_IMAGE_REQUEST = 1
        private const val WRITE_REQUEST_CODE = 2
    }


    private fun applyRatingToImages() {
        modifySharedFiles(applicationContext)
        refreshFileList()
        Toast.makeText(this, "Ratings applied successfully", Toast.LENGTH_SHORT).show()
    }


    private fun refreshFileList() {
        tvNumberOfFiles.text = "Number of files: ${imageUris.size}"
        val urisText = imageUris.joinToString(separator = "\n") { uri ->
            val currentRating = getCurrentRating(uri)
            val displayName = getFileName(uri) ?: "Unknown"
            val parentFolder = getParentFolderName(uri) ?: "Unknown"
            "/$parentFolder/$displayName (${currentRating.toStars()})"
        }
        tvInfo.text = urisText
    }




    fun modifyXmpXml(xmpXml: String?, newRating: Int): String {
        if (xmpXml == null) return ""

        val dbFactory = DocumentBuilderFactory.newInstance()
        dbFactory.isNamespaceAware = true // Enable namespace awareness
        val dBuilder = dbFactory.newDocumentBuilder()
        val doc = dBuilder.parse(xmpXml.byteInputStream())
        val rdfNamespaceUri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        val xmpNamespaceUri = "http://ns.adobe.com/xap/1.0/"

        // Find the rdf:Description element
        val rdfDescription = doc.getElementsByTagNameNS(rdfNamespaceUri, "Description").run {
            if (this.length > 0) this.item(0) as Element else null
        }

        if (rdfDescription != null) {
            // Update the xmp:Rating attribute if rdf:Description exists
            rdfDescription.setAttributeNS(xmpNamespaceUri, "xmp:Rating", newRating.toString())
        } else {
            // Create a new rdf:Description with the rating if it doesn't exist
            val newRdfDescription = doc.createElementNS(rdfNamespaceUri, "rdf:Description").apply {
                setAttributeNS(xmpNamespaceUri, "xmp:Rating", newRating.toString())
            }
            doc.documentElement.appendChild(newRdfDescription)
        }

        // Convert back to String
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
        Log.d("apply", "start modifying")
        imageUris.forEach { uri ->
            try {
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
                        val jpegXmpRewriter = JpegXmpRewriter()
                        ByteArrayOutputStream().use { outputStream ->
                            jpegXmpRewriter.updateXmpXml(imageData, outputStream, modifiedXmp)
                            val modifiedImageData = outputStream.toByteArray()

                            if (modifiedImageData.isNotEmpty()) {
                                updateMediaStoreFile(context, uri, modifiedImageData)
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("modifySharedFile", "IOException occurred: ${e.message}")
                showToast(context, "IOException occurred: ${e.message}")
            }
        }
        Log.d("modifySharedFile", "Done!")
        showToast(context, "Done!")
        refreshFileList()
    }



    private fun updateMediaStoreFile(context: Context, uri: Uri, imageData: ByteArray) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(imageData)
            }
        } catch (e: IOException) {
            Log.e("updateMediaStoreFile", "Failed to write to MediaStore file: $e")
        }
    }


    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

}
