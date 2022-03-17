package free.dmo.tagdump

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var nfcAdapter: NfcAdapter
    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView = findViewById(R.id.text_view)
        textView.text = "Scan SLIX2 tag"
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (!nfcAdapter.isEnabled)
            textView.text = "NFC is not available / disabled."
    }

    override fun onResume() {
        super.onResume()
        checkAndAskForPermission()
        setupForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        stopForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action) {
            val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            val nfcVTag: NfcV? = NfcV.get(tag)
            if (tag == null || nfcVTag == null) {
                textView.text = "Could not read tag."
                return
            }

            object : Thread() {
                override fun run() {
                    lateinit var inventoryResponse: ByteArray
                    lateinit var uid: ByteArray
                    lateinit var sysInfoResponse: ByteArray
                    lateinit var signatureResponse: ByteArray
                    lateinit var blk00to0F: ByteArray
                    lateinit var blk10to1F: ByteArray
                    lateinit var blk20to2F: ByteArray
                    lateinit var blk30to3F: ByteArray
                    lateinit var blk40to4F: ByteArray

                    try {
                        nfcVTag.connect()
                        if (nfcVTag.isConnected) {
                            val inventory = byteArrayOfInts(0x36, 0x01, 0x00, 0x00)
                            inventoryResponse = transceive(nfcVTag, inventory)
                            uid = inventoryResponse.copyOfRange(1, 9)
                            sysInfoResponse = transceive(nfcVTag, byteArrayOfInts(0x22, 0x2B).plus(uid))
                            signatureResponse = transceive(nfcVTag, byteArrayOfInts(0x22, 0xBD, 0x04).plus(uid))
                            blk00to0F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x00, 0x0F))
                            blk10to1F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x10, 0x0F))
                            blk20to2F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x20, 0x0F))
                            blk30to3F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x30, 0x0F))
                            blk40to4F = transceive(nfcVTag, createMultipleBlockReadRequest(uid, 0x40, 0x0F))
                        }
                        nfcVTag.close()

                        val stringBuilder = StringBuilder()
                            .append("inventory: ").append(bytesToHex(inventoryResponse)).append("\n")
                            .append("sysInfo: ").append(bytesToHex(sysInfoResponse)).append("\n")
                            .append("signature: ").append(bytesToHex(signatureResponse)).append("\n")
                            .append("blocks:\n")
                            .append(bytesToHex(blk00to0F)).append(", ")
                            .append(bytesToHex(blk10to1F)).append(", ")
                            .append(bytesToHex(blk20to2F)).append(", ")
                            .append(bytesToHex(blk30to3F)).append(", ")
                            .append(bytesToHex(blk40to4F)).append("\n")

                        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
                        val fileName = bytesToHex(uid.reversedArray(), false) + "_" + dateFormat.format(Date()) + ".txt"
                        val content = stringBuilder.toString()
                        runOnUiThread {
                            textView.text = content
                        }
                        appendToFile(fileName, content)
                    } catch (e: Exception) {
                        Log.e(TAG, e.toString())
                    }
                }
            }.start()
        }
    }

    private fun transceive(nfcTag: NfcV, data: ByteArray): ByteArray{
        val response = nfcTag.transceive(data)
        return response.copyOfRange(1, response.size)
    }

    private fun appendToFile(fileName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveContentInFileToExternalStorageAfterQ(content, fileName)
        } else {
            saveContentInFileToExternalStorage(content, fileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveContentInFileToExternalStorageAfterQ(content: String, fileName: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            contentResolver.openOutputStream(uri, "wa").use { output ->
                output?.write(content.toByteArray())
                output?.close()
            }
        }
    }

    private fun saveContentInFileToExternalStorage(content: String, fileName: String) {
        @Suppress("DEPRECATION") var root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        root = File(root, fileName)

        try {
            if (!root.exists()) {
                root.createNewFile()
            }
            val fileOutputStream = FileOutputStream(root, true)
            fileOutputStream.write(content.toByteArray())

            fileOutputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, Log.getStackTraceString(e))
        }
    }

    private fun createMultipleBlockReadRequest(uid: ByteArray, vararg integers: Int): ByteArray {
        return byteArrayOfInts(0x22, 0x23).plus(uid).plus(byteArrayOfInts(integers[0], integers[1]))
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                textView.text = "NFC permission not granted.";
            }
        }

    private fun checkAndAskForPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun setupForegroundDispatch() {
        val activity: Activity = this
        val intent = Intent(activity.applicationContext, activity.javaClass)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            activity.applicationContext,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        )
        nfcAdapter.enableForegroundDispatch(activity, pendingIntent, null, null)
    }

    private fun stopForegroundDispatch() {
        nfcAdapter.disableForegroundDispatch(this)
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()
    private fun bytesToHex(bytes: ByteArray?, cstyle: Boolean = true): String {
        if (null == bytes)
            return ""
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v: Int = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        if (!cstyle)
            return String(hexChars)

        val prettyHexChars = ArrayList<Char>()
        for (index in hexChars.indices) {
            if (index % 2 == 0) {
                prettyHexChars.add('0')
                prettyHexChars.add('x')
            }
            prettyHexChars.add(hexChars[index])
            if (index % 2 == 1 && index != hexChars.size - 1)
                prettyHexChars.add(',')
        }
        return String(prettyHexChars.toCharArray())
    }

    private fun byteArrayOfInts(vararg integers: Int) = ByteArray(integers.size) { position -> integers[position].toByte() }

    companion object {
        private const val TAG = "MainActivity"
    }
}