package com.videosplit.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.view.View
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.min

@UnstableApi
class MainActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var projectList: LinearLayout
    private lateinit var store: ProjectStore
    private val queue = ArrayDeque<String>()
    private var processingId: String? = null
    private var selectedPart: File? = null
    private var selectedProject: Project? = null

    private val chooseVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(::importVideo)
    }
    private val savePart = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = selectedPart
        if (uri != null && file?.exists() == true) copyToUri(file, uri)
        selectedPart = null
    }
    private val saveProject = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val project = selectedProject
        selectedProject = null
        if (uri != null && project != null) {
            val folder = DocumentFile.fromTreeUri(this, uri)
            val files = store.parts(project)
            worker.execute {
                var count = 0
                for (file in files) {
                    try {
                        val destination = folder?.createFile("video/mp4", file.name) ?: continue
                        contentResolver.openOutputStream(destination.uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                            count++
                        }
                    } catch (_: Exception) {}
                }
                main.post { toast("$count / ${files.size} parça kaydedildi") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(filesDir)
        window.statusBarColor = Color.rgb(247, 248, 250)
        window.navigationBarColor = Color.rgb(247, 248, 250)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(32))
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        label(root, "VideoSplit", 30)
        hint(root, "Video parçalama, kolay ve düzenli.")
        val pickCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            background = rounded(0xFFFFFFFF.toInt(), 18f)
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22) }
        }
        root.addView(pickCard)
        label(pickCard, "Yeni video", 19)
        hint(pickCard, "Bir veya birden fazla video seç. Her biri 10 saniyelik parçalara ayrılır.")
        button(pickCard, "+  Video seç") { chooseVideos.launch(arrayOf("video/*")) }
        hint(pickCard, "Dosyalar sadece uygulamada saklanır. İstersen dışa aktarabilirsin.")
        label(root, "Projelerim", 21)
        projectList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(projectList)
        render()
        store.all().filter { it.status != "Bitti" }.forEach {
            if (it.total > 0 && File(store.folder(it.id), "source").exists()) {
                it.status = "Sırada"
                store.save(it)
                enqueue(it.id)
            }
        }
    }

    private fun importVideo(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "video.mp4"
        val id = UUID.randomUUID().toString()
        val src = File(store.folder(id).also { it.mkdirs() }, "source")
        val project = Project(id, name, 0, "İçe aktarılıyor", 0, 0)
        store.save(project)
        render()
        worker.execute {
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    src.outputStream().use { input.copyTo(it) }
                } ?: error("Video açılamadı")
                val retriever = MediaMetadataRetriever()
                val duration = try {
                    retriever.setDataSource(src.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { retriever.release() }
                require(duration > 0L) { "Video süresi okunamadı" }
                project.duration = duration
                project.total = ceil(duration / 10000.0).toInt()
                project.status = "Sırada"
                store.save(project)
                main.post { render(); enqueue(id) }
            } catch (e: Exception) {
                project.status = "Hata"
                store.save(project)
                main.post { render(); toast("İçe aktarma hatası: ${e.message}") }
            }
        }
    }

    private fun enqueue(id: String) {
        if (id != processingId && id !in queue) queue.addLast(id)
        pump()
    }

    private fun pump() {
        if (processingId != null || queue.isEmpty()) return
        val id = queue.removeFirst()
        val project = store.load(id)
        if (project == null || project.status == "Bitti") { pump(); return }
        processingId = id
        exportNext(project)
    }

    private fun exportNext(project: Project) {
        if (project.done >= project.total) {
            project.status = "Bitti"
            store.save(project)
            processingId = null
            render()
            pump()
            return
        }
        val index = project.done
        val start = index * 10_000L
        val end = min(project.duration, (index + 1L) * 10_000L)
        val input = File(store.folder(project.id), "source")
        val output = File(store.folder(project.id), "part_%03d.mp4".format(index + 1))
        if (output.exists()) output.delete()
        project.status = "İşleniyor"
        store.save(project)
        render()
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(start)
            .setEndPositionMs(end)
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .setClippingConfiguration(clipping)
            .build()
        val editedItem = EditedMediaItem.Builder(mediaItem).build()
        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    project.done = index + 1
                    store.save(project)
                    exportNext(project)
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    output.delete()
                    project.status = "Hata"
                    store.save(project)
                    processingId = null
                    render()
                    toast("Bölme hatası: ${exportException.message}")
                    pump()
                }
            }).build()
        try {
            transformer.start(editedItem, output.absolutePath)
        } catch (e: Exception) {
            project.status = "Hata"
            store.save(project)
            processingId = null
            render()
            toast("Bölme hatası: ${e.message}")
            pump()
        }
    }

    private fun render() {
        projectList.removeAllViews()
        val projects = store.all().sortedByDescending { it.created }
        if (projects.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(24), dp(22), dp(24))
                background = rounded(Color.WHITE, 18f)
            }
            projectList.addView(empty)
            label(empty, "Henüz proje yok", 17)
            hint(empty, "Yukarıdaki butondan bir video seçerek ilk projenizi oluşturun.")
        }
        for (project in projects) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(14))
                background = rounded(Color.WHITE, 18f)
                elevation = dp(1).toFloat()
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
            }
            projectList.addView(card)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            card.addView(header)
            header.addView(TextView(this).apply {
                text = project.name
                textSize = 16f
                setTextColor(0xFF1F2937.toInt())
                setTypeface(null, Typeface.BOLD)
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) }
            })
            val isDone = project.status == "Bitti"
            header.addView(TextView(this).apply {
                text = if (isDone) "Hazır" else project.status
                textSize = 11f
                setTextColor(if (isDone) 0xFF176B48.toInt() else 0xFF53627B.toInt())
                setPadding(dp(9), dp(6), dp(9), dp(6))
                background = rounded(if (isDone) 0xFFE8F6EE.toInt() else 0xFFF0F2F6.toInt(), 9f)
            })
            hint(card, "${project.done} / ${project.total} parça   •   ${formatDuration(project.duration)}")
            if (project.total > 0) {
                card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = project.total
                    progress = project.done
                    progressTintList = ColorStateList.valueOf(0xFF3466D6.toInt())
                    progressBackgroundTintList = ColorStateList.valueOf(0xFFEAEDF3.toInt())
                    layoutParams = LinearLayout.LayoutParams(-1, dp(5)).apply { bottomMargin = dp(12) }
                })
            }
            if (project.status == "Hata") button(card, "Tekrar dene") {
                project.status = "Sırada"
                store.save(project)
                enqueue(project.id)
                render()
            }
            if (isDone) button(card, "Tüm parçaları kaydet") {
                selectedProject = project
                saveProject.launch(null)
            }
            val parts = store.parts(project)
            if (parts.isNotEmpty()) {
                val divider = View(this).apply {
                    setBackgroundColor(0xFFF1F2F5.toInt())
                    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(9)
                    }
                }
                card.addView(divider)
            }
            for ((index, file) in parts.withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                card.addView(row)
                row.addView(TextView(this).apply {
                    text = "Parça %02d".format(index + 1)
                    textSize = 13f
                    setTextColor(0xFF4B5563.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                smallButton(row, "İzle") { preview(file) }
                smallButton(row, "Kaydet") { selectedPart = file; savePart.launch(file.name) }
            }
            val delete = TextView(this).apply {
                text = "Projeyi sil"
                textSize = 12f
                gravity = Gravity.END
                setTextColor(0xFF9A4B4B.toInt())
                setPadding(0, dp(16), dp(2), dp(4))
                setOnClickListener {
                    if (project.id == processingId) {
                        toast("İşlem sürerken silinemez")
                    } else {
                        queue.remove(project.id)
                        store.remove(project.id)
                        render()
                    }
                }
            }
            card.addView(delete)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun preview(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { toast("Video oynatıcı açılamadı") }
    }

    private fun copyToUri(file: File, uri: Uri) {
        worker.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: error("Dosya oluşturulamadı")
                main.post { toast("Parça kaydedildi") }
            } catch (e: Exception) {
                main.post { toast("Kaydetme hatası: ${e.message}") }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun label(parent: LinearLayout, text: String, size: Int) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(Color.rgb(30, 34, 44))
            setTypeface(null, 1)
            setPadding(0, 7, 0, 9)
        })
    }
    private fun hint(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 5, 0, 14)
        })
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }

    private fun button(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(0xFF3466D6.toInt(), 12f)
            layoutParams = LinearLayout.LayoutParams(-1, dp(46))
            setOnClickListener { action() }
        })
    }

    private fun smallButton(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFF3466D6.toInt())
            background = rounded(0xFFF0F4FD.toInt(), 10f)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(7) }
            setOnClickListener { action() }
        })
    }
}

data class Project(
    val id: String,
    val name: String,
    var duration: Long,
    var status: String,
    var done: Int,
    var total: Int,
    val created: Long = System.currentTimeMillis()
)

class ProjectStore(base: File) {
    private val root = File(base, "projects").also { it.mkdirs() }
    fun folder(id: String) = File(root, id)
    fun save(project: Project) {
        val data = JSONObject()
            .put("id", project.id)
            .put("name", project.name)
            .put("duration", project.duration)
            .put("status", project.status)
            .put("done", project.done)
            .put("total", project.total)
            .put("created", project.created)
        val dir = folder(project.id).also { it.mkdirs() }
        val temporary = File(dir, "project.tmp")
        temporary.writeText(data.toString())
        val target = File(dir, "project.json")
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }
    fun load(id: String): Project? = try {
        val j = JSONObject(File(folder(id), "project.json").readText())
        Project(j.getString("id"), j.getString("name"), j.getLong("duration"),
            j.getString("status"), j.getInt("done"), j.getInt("total"), j.getLong("created"))
    } catch (_: Exception) { null }
    fun all(): List<Project> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it.name) } ?: emptyList()
    fun parts(project: Project): List<File> =
        (1..project.done).map { File(folder(project.id), "part_%03d.mp4".format(it)) }
            .filter { it.exists() }
    fun remove(id: String) { folder(id).deleteRecursively() }
}
