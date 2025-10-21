package com.wyderlan.canhaopodcast

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset

data class Episode(val id: Long, val title: String, val description: String)

class EpisodeRepo(private val activity: Activity) {
    private val fileName = "episodes.json"

    suspend fun load(): List<Episode> = withContext(Dispatchers.IO) {
        try {
            val f = activity.getFileStreamPath(fileName)
            if (!f.exists()) return@withContext emptyList<Episode>()
            val text = activity.openFileInput(fileName).bufferedReader().use { it.readText() }
            val lines = text.trim().split("\n").filter { it.isNotBlank() }
            val out = mutableListOf<Episode>()
            for (line in lines) {
                val p = line.split("\t")
                if (p.size >= 3)
                    out.add(Episode(p[0].toLongOrNull() ?: System.currentTimeMillis(), p[1], p[2]))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun save(list: List<Episode>) = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        list.forEach {
            sb.append(it.id).append("\t")
                .append(it.title.replace("\n", " "))
                .append("\t")
                .append(it.description.replace("\n", " "))
                .append("\n")
        }
        activity.openFileOutput(fileName, Activity.MODE_PRIVATE).use {
            it.write(sb.toString().toByteArray(Charset.forName("UTF-8")))
        }
    }

    suspend fun exportTxt(uri: Uri, episodes: List<Episode>) = withContext(Dispatchers.IO) {
        activity.contentResolver.openOutputStream(uri)?.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                episodes.forEach { ep ->
                    val t = ep.title.replace("\n", " ").replace("|||", "|")
                    val d = ep.description.replace("\n", " ").replace("|||", "|")
                    w.write("$t|||$d\n")
                }
                w.flush()
            }
        }
    }

    suspend fun importTxt(uri: Uri): List<Episode> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Episode>()
        activity.contentResolver.openInputStream(uri)?.use { ins ->
            BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    val parts = line!!.split("|||")
                    if (parts.isNotEmpty()) {
                        val title = parts.getOrNull(0)?.trim() ?: ""
                        val desc = parts.getOrNull(1)?.trim() ?: ""
                        if (title.isNotBlank() || desc.isNotBlank())
                            list.add(Episode(System.currentTimeMillis() + list.size, title, desc))
                    }
                }
            }
        }
        list
    }
}

class MainViewModel : ViewModel() {
    var episodes by mutableStateOf(listOf<Episode>())
        private set

    fun updateEpisodes(list: List<Episode>) {
        episodes = list
    }

    fun addEpisode(title: String, description: String) {
        episodes = episodes + Episode(System.currentTimeMillis(), title, description)
    }

    fun deleteEpisode(id: Long) {
        episodes = episodes.filterNot { it.id == id }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = EpisodeRepo(this)

        lifecycleScope.launchWhenCreated {
            val loaded = repo.load()
            vm.updateEpisodes(loaded)
        }

        val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                val r = EpisodeRepo(this)
                val list = vm.episodes
                this@MainActivity.lifecycleScope.launchWhenResumed {
                    r.exportTxt(uri, list)
                }
            }
        }

        val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val r = EpisodeRepo(this)
                this@MainActivity.lifecycleScope.launchWhenResumed {
                    val imported = r.importTxt(uri)
                    if (imported.isNotEmpty()) {
                        vm.updateEpisodes(imported)
                        r.save(imported)
                    }
                }
            }
        }

        setContent {
            val context = LocalContext.current as Activity
            val scope = rememberCoroutineScope()
            val repoState = remember { EpisodeRepo(context) }

            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(
                        episodes = vm.episodes,
                        onAdd = { t, d ->
                            vm.addEpisode(t, d)
                            scope.launch { repoState.save(vm.episodes) }
                        },
                        onDelete = { id ->
                            vm.deleteEpisode(id)
                            scope.launch { repoState.save(vm.episodes) }
                        },
                        onExport = {
                            createDoc.launch("canhao_podcast_backup.txt")
                        },
                        onImport = {
                            openDoc.launch(arrayOf("text/plain"))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AppScreen(
    episodes: List<Episode>,
    onAdd: (String, String) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    var title by remember { mutableStateOf(TextFieldValue("")) }
    var desc by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Canhão Podcast", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Título") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = desc,
            onValueChange = { desc = it },
            label = { Text("Descrição") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(onClick = {
                val t = title.text.trim()
                val d = desc.text.trim()
                if (t.isNotEmpty() || d.isNotEmpty()) {
                    onAdd(t, d)
                    title = TextFieldValue("")
                    desc = TextFieldValue("")
                }
            }) {
                Text("Adicionar Episódio")
            }

            OutlinedButton(onClick = onExport) { Text("Exportar") }
            OutlinedButton(onClick = onImport) { Text("Importar") }
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(episodes, key = { it.id }) { ep ->
                EpisodeRow(ep, onDelete)
            }
        }
    }
}

@Composable
fun EpisodeRow(ep: Episode, onDelete: (Long) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(ep.title.ifBlank { "(Sem título)" }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(ep.description.ifBlank { "(Sem descrição)" }, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onDelete(ep.id) }) { Text("Excluir") }
        }
    }
}
