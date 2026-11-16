package com.videosplit.app

import android.content.Intent
import android.graphics.Color
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
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        label(root, "VideoSplit", 28)
        hint(root, "Videoları 10 saniyelik parçalara ayır.")
        button(root, "Video seç") { chooseVideos.launch(arrayOf("video/*")) }
        hint(root, "Videolar galeriye otomatik kaydedilmez.")
        label(root, "Projelerim", 20)
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
        if (projects.isEmpty()) hint(projectList, "Henüz proje yok. Video seçerek başla.")
        for (project in projects) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 18, 18, 18)
                setBackgroundColor(Color.WHITE)
                elevation = 2f
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 }
            }
            projectList.addView(card)
            label(card, project.name, 17)
            hint(card, "${project.status} · ${project.done}/${project.total} parça · ${project.duration / 1000} sn")
            if (project.total > 0) card.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = project.total
                progress = project.done
            })
            if (project.status == "Hata") button(card, "Tekrar dene") {
                project.status = "Sırada"
                store.save(project)
                enqueue(project.id)
                render()
            }
            if (project.status == "Bitti") button(card, "Tüm parçaları kaydet") {
                selectedProject = project
                saveProject.launch(null)
            }
            for ((index, file) in store.parts(project).withIndex()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                card.addView(row)
                row.addView(TextView(this).apply {
                    text = "Parça ${index + 1}"
                    setTextColor(Color.DKGRAY)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                })
                smallButton(row, "İzle") { preview(file) }
                smallButton(row, "Kaydet") { selectedPart = file; savePart.launch(file.name) }
            }
            button(card, "Projeyi sil") {
                if (project.id == processingId) {
                    toast("İşlem sürerken silinemez")
                } else {
                    queue.remove(project.id)
                    store.remove(project.id)
                    render()
                }
            }
        }
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
    private fun button(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(Button(this).apply { this.text = text; setOnClickListener { action() } })
    }
    private fun smallButton(parent: LinearLayout, text: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            this.text = text
            textSize = 11f
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
