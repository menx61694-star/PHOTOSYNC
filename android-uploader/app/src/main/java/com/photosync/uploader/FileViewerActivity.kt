package com.photosync.uploader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class FileViewerActivity : AppCompatActivity() {
    private val client = OkHttpClient()
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFile: File? = null
    private var pdfPage = 0
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "File"
        val url = intent.getStringExtra("url") ?: return finish()
        val mime = intent.getStringExtra("mime") ?: "application/octet-stream"
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B1220.toInt()); setPadding(16, 16, 16, 16) }
        val title = TextView(this).apply { text = name; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, 0, 0, 12) }
        content.addView(title)
        setContentView(content)
        if (mime == "application/pdf" || name.lowercase().endsWith(".pdf")) showPdf(url, name)
        else if (mime.startsWith("video/") || name.lowercase().matches(Regex(".*\\.(mp4|mkv|webm|3gp|mov|avi)$"))) showVideo(url)
        else if (mime.startsWith("audio/") || name.lowercase().matches(Regex(".*\\.(mp3|wav|m4a|aac|ogg|flac)$"))) showAudio(url)
        else finish()
    }

    private fun showVideo(url: String) {
        val video = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setMediaController(MediaController(this@FileViewerActivity))
            setVideoURI(Uri.parse(url))
            setOnPreparedListener { start() }
        }
        content.addView(video)
    }

    private fun showAudio(url: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        val label = TextView(this).apply { text = "Audio"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER }
        val button = Button(this).apply { text = "Play" }
        var player: MediaPlayer? = null
        button.setOnClickListener {
            try {
                if (player?.isPlaying == true) { player?.pause(); text = "Play" }
                else {
                    if (player == null) player = MediaPlayer().apply { setDataSource(url); prepare(); setOnCompletionListener { button.text = "Play" } }
                    player?.start(); text = "Pause"
                }
            } catch (e: Exception) { button.text = "Unable to play" }
        }
        box.addView(label); box.addView(button); content.addView(box)
    }

    private fun showPdf(url: String, name: String) {
        Thread {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val file = File(cacheDir, "viewer_${System.currentTimeMillis()}_$name")
                    FileOutputStream(file).use { out -> response.body?.byteStream()?.copyTo(out) }
                    runOnUiThread { openPdf(file) }
                }
            } catch (_: Exception) { runOnUiThread { finish() } }
        }.start()
    }

    private fun openPdf(file: File) {
        pdfFile = file
        pdfRenderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(Button(this).apply { text = "‹"; setOnClickListener { renderPdfPage(pdfPage - 1) } })
        controls.addView(Button(this).apply { text = "›"; setOnClickListener { renderPdfPage(pdfPage + 1) } })
        content.addView(controls, 1)
        renderPdfPage(0)
    }

    private fun renderPdfPage(index: Int) {
        val renderer = pdfRenderer ?: return
        if (index !in 0 until renderer.pageCount) return
        pdfPage = index
        val page = renderer.openPage(index)
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        val image = content.findViewWithTag<ImageView>("pdf") ?: ImageView(this).also {
            it.tag = "pdf"; it.adjustViewBounds = true; it.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f); content.addView(it)
        }
        image.setImageBitmap(bitmap)
    }

    override fun onDestroy() {
        pdfRenderer?.close(); pdfRenderer = null; pdfFile?.delete(); super.onDestroy()
    }
}
