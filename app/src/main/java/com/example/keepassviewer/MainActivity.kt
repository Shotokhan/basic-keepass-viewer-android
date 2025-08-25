// app/src/main/java/com/example/keepassviewer/MainActivity.kt
package com.example.keepassviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.linguafranca.pwdb.Credentials
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.simple.SimpleDatabase
import org.linguafranca.pwdb.kdbx.simple.SimpleEntry
import org.linguafranca.pwdb.kdbx.simple.SimpleGroup
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

// -----------------------------
// Room entities / DAO / DB
// -----------------------------
@Entity(tableName = "imports")
data class ImportEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,          // stored filename under filesDir/imports/
    val originalName: String,      // original name from URL
    val importedAt: Long           // epoch millis
)

@Dao
interface ImportDao {
    @Insert
    suspend fun insert(entry: ImportEntry)

    @Query("SELECT * FROM imports ORDER BY importedAt DESC")
    fun getAll(): Flow<List<ImportEntry>>

    @Query("SELECT * FROM imports ORDER BY importedAt DESC LIMIT 1")
    suspend fun getLatest(): ImportEntry?
}

@Database(entities = [ImportEntry::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importDao(): ImportDao
}

// -----------------------------
// KeePass tree model & loader (read-only)
// -----------------------------
data class Node(
    val id: String,
    val title: String,
    val isGroup: Boolean,
    val children: List<Node> = emptyList(),
    val username: String? = null,
    val password: String? = null,
    val notes: String? = null
)

object KeePassLoader {
    fun loadTree(file: File, password: String): Node {
        val creds: Credentials = KdbxCreds(password.toByteArray())
        FileInputStream(file).use { fis ->
            // SimpleDatabase comes from the "simple" implementation; it can read KDBX v3/v4
            val db: SimpleDatabase = SimpleDatabase.load(creds, fis)
            val root: SimpleGroup = db.rootGroup
            return mapGroup(root)
        }
    }

    private fun mapGroup(group: SimpleGroup): Node {
        val children = mutableListOf<Node>()
        // Map sub-groups
        for (g: SimpleGroup in group.groups) {
            children += mapGroup(g)
        }
        // Map entries
        for (e: SimpleEntry in group.entries) {
            children += Node(
                id = e.uuid.toString(),
                title = e.title ?: "(no title)",
                isGroup = false,
                username = e.username,
                password = e.password,
                notes = e.notes
            )
        }
        return Node(
            id = group.uuid.toString(),
            title = group.name ?: "(no name)",
            isGroup = true,
            children = children
        )
    }
}

// -----------------------------
// Simple downloader (OkHttp)
// -----------------------------
object Downloader {
    private val client = OkHttpClient()

    fun downloadToFile(url: String, dest: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty body")
            FileOutputStream(dest).use { out ->
                body.byteStream().copyTo(out)
            }
        }
    }
}

// -----------------------------
// Utilities
// -----------------------------
private fun ensureImportsDir(ctx: Context): File {
    val dir = File(ctx.filesDir, "imports")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun timestampedName(base: String): String {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    return ts + "_" + base
}

private fun fileNameFromUrl(url: String): String {
    val path = url.substringAfterLast('/')
    return URLDecoder.decode(path.ifEmpty { "db.kdbx" }, "UTF-8")
}

// -----------------------------
// MainActivity with Compose UI
// -----------------------------
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appDb = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "keepass.db").build()
        val dao = appDb.importDao()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val scope = rememberCoroutineScope()
                val ctx = LocalContext.current
                val importsDir = remember { ensureImportsDir(ctx) }

                // UI States
                var imports by remember { mutableStateOf<List<ImportEntry>>(emptyList()) }
                var currentImport by remember { mutableStateOf<ImportEntry?>(null) }
                var tree by remember { mutableStateOf<Node?>(null) }
                var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
                var showImportDialog by remember { mutableStateOf(false) }
                var showSwitchDialog by remember { mutableStateOf(false) }
                var showPasswordDialog by remember { mutableStateOf(false) }
                var lastError by remember { mutableStateOf<String?>(null) }

                // Password input state
                var masterPassword by remember { mutableStateOf("") }

                // Collect history list
                LaunchedEffect(Unit) {
                    dao.getAll().collectLatest { list -> imports = list }
                }

                // Load latest import on start
                LaunchedEffect(Unit) {
                    currentImport = dao.getLatest()
                }

                // UI
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("KeePass Viewer") },
                            actions = {
                                TextButton(onClick = { showImportDialog = true }) { Text("Import") }
                                TextButton(onClick = { showSwitchDialog = true }) { Text("Switch") }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        if (currentImport == null) {
                            Text("No imports yet. Use Import to download your .kdbx.")
                        } else {
                            ImportHeader(currentImport!!)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(onClick = { showPasswordDialog = true }) { Text("Open Database") }
                                Spacer(Modifier.width(12.dp))
                                if (tree != null) Text("Loaded entries: ${countEntries(tree!!)}")
                            }
                            Spacer(Modifier.height(12.dp))
                            tree?.let { root ->
                                LazyColumn(Modifier.fillMaxSize()) {
                                    item {
                                        GroupRow(
                                            title = root.title,
                                            expanded = expanded.contains(root.id),
                                            onToggle = { expanded = toggle(expanded, root.id) },
                                            depth = 0
                                        )
                                    }
                                    if (expanded.contains(root.id)) {
                                        items(root.children, key = { it.id }) { child ->
                                            TreeView(
                                                node = child,
                                                expanded = expanded,
                                                onToggle = { id -> expanded = toggle(expanded, id) },
                                                depth = 1
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        lastError?.let { err ->
                            Spacer(Modifier.height(8.dp))
                            Text("Error: $err", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                // Import dialog
                if (showImportDialog) {
                    ImportDialog(
                        onDismiss = { showImportDialog = false },
                        onImport = { url ->
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val original = fileNameFromUrl(url)
                                    val destName = timestampedName(original)
                                    val dest = File(importsDir, destName)
                                    Downloader.downloadToFile(url, dest)
                                    dao.insert(
                                        ImportEntry(
                                            filename = dest.name,
                                            originalName = original,
                                            importedAt = System.currentTimeMillis()
                                        )
                                    )
                                    val latest = dao.getLatest()
                                    withContext(Dispatchers.Main) {
                                        currentImport = latest
                                        lastError = null
                                        showImportDialog = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        lastError = e.message
                                        showImportDialog = false
                                    }
                                }
                            }
                        }
                    )
                }

                // Switch dialog
                if (showSwitchDialog) {
                    SwitchDialog(
                        imports = imports,
                        onDismiss = { showSwitchDialog = false },
                        onPick = { sel ->
                            currentImport = sel
                            tree = null
                            expanded = emptySet()
                            showSwitchDialog = false
                        }
                    )
                }

                // Password dialog
                if (showPasswordDialog && currentImport != null) {
                    PasswordDialog(
                        masterPassword = masterPassword,
                        onChange = { masterPassword = it },
                        onDismiss = { showPasswordDialog = false },
                        onOpen = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val file = File(importsDir, currentImport!!.filename)
                                    val loaded = KeePassLoader.loadTree(file, masterPassword)
                                    withContext(Dispatchers.Main) {
                                        tree = loaded
                                        expanded = setOf(loaded.id) // expand root by default
                                        lastError = null
                                        showPasswordDialog = false
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        lastError = e.message
                                        showPasswordDialog = false
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// -----------------------------
// Composables
// -----------------------------
@Composable
private fun ImportHeader(entry: ImportEntry) {
    val date = remember(entry.importedAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.importedAt))
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Current import", fontWeight = FontWeight.Bold)
            Text(entry.originalName)
            Text("Imported at: $date")
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import .kdbx from URL") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("http://…/db.kdbx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Tip: on your PC run: python -m http.server")
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(url) }, enabled = url.isNotBlank()) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SwitchDialog(imports: List<ImportEntry>, onDismiss: () -> Unit, onPick: (ImportEntry) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Import") },
        text = {
            if (imports.isEmpty()) {
                Text("No imports yet.")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(imports, key = { it.id }) { e ->
                        val date = remember(e.importedAt) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(e.importedAt))
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(e) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.padding(4.dp)) {
                                Text(e.originalName, fontWeight = FontWeight.SemiBold)
                                Text(date, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun PasswordDialog(masterPassword: String, onChange: (String) -> Unit, onDismiss: () -> Unit, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Master Password") },
        text = {
            OutlinedTextField(
                value = masterPassword,
                onValueChange = onChange,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = onOpen, enabled = masterPassword.isNotBlank()) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun TreeView(node: Node, expanded: Set<String>, onToggle: (String) -> Unit, depth: Int) {
    if (node.isGroup) {
        GroupRow(
            title = node.title,
            expanded = expanded.contains(node.id),
            onToggle = { onToggle(node.id) },
            depth = depth
        )
        if (expanded.contains(node.id)) {
            node.children.forEach { child ->
                TreeView(child, expanded, onToggle, depth + 1)
            }
        }
    } else {
        EntryRow(node = node, depth = depth)
    }
}

@Composable
private fun GroupRow(title: String, expanded: Boolean, onToggle: () -> Unit, depth: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onToggle) {
            Text(text = if (expanded) "▼ $title" else "▶ $title")
        }
    }
}

@Composable
private fun EntryRow(node: Node, depth: Int) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var showPassword by remember(node.id) { mutableStateOf(false) }
    var lastCopiedPassword by remember { mutableStateOf<String?>(null) }

    // When lastCopiedPassword changes, start a 10s timer
    // TODO: this is not working properly
    LaunchedEffect(lastCopiedPassword) {
        if (lastCopiedPassword != null) {
            kotlinx.coroutines.delay(10_000)
            // Clear clipboard if it's still the same value we put there
            if (clipboard.hasPrimaryClip() &&
                clipboard.primaryClip?.getItemAt(0)?.text == lastCopiedPassword
            ) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            lastCopiedPassword = null
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 16).dp, top = 4.dp, bottom = 8.dp)
    ) {
        Text("🔑 ${node.title}", fontWeight = FontWeight.SemiBold)
        node.username?.takeIf { it.isNotBlank() }?.let { Text("User: $it") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (showPassword) node.password ?: "(empty)" else mask(node.password),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val clip = node.password ?: return@TextButton
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("password", clip))
                lastCopiedPassword = clip
            }) { Text("Copy") }
        }

        node.notes?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun mask(pass: String?): String {
    if (pass.isNullOrEmpty()) return "(empty)"
    return "•".repeat(pass.length.coerceAtMost(12))
}

private fun toggle(set: Set<String>, id: String): Set<String> =
    if (set.contains(id)) set - id else set + id

private fun countEntries(root: Node): Int {
    var c = 0
    fun dfs(n: Node) {
        if (!n.isGroup) c++ else n.children.forEach { dfs(it) }
    }
    dfs(root)
    return c
}
