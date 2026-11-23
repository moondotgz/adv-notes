package com.advnotes.app.ui

import com.advnotes.app.i18n.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.advnotes.app.ai.AiClients
import com.advnotes.app.ai.ModelInfo
import com.advnotes.app.data.ProviderType
import com.advnotes.app.data.SettingsStore
import kotlinx.coroutines.launch

private enum class ModelSort(private val en: String) { NEWEST("Newest"), CHEAPEST("Cheapest"), NAME("Name") ;
    /** Shown to the user, in the app language. */
    val label: String get() = tr(en)
}

/**
 * Shows the chosen model (with its price on OpenRouter) and opens a searchable list loaded from
 * the provider. Any id can still be typed in, for models the list doesn't include.
 */
@Composable
fun ModelPicker(p: ProviderType, st: SettingsStore) {
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(st.model(p)) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf(false) }

    fun canList() = when (p) {
        ProviderType.OPENROUTER, ProviderType.CHATGPT -> true
        ProviderType.OPENAI, ProviderType.ANTHROPIC -> st.apiKey(p).isNotBlank()
        ProviderType.CUSTOM_OPENAI, ProviderType.CUSTOM_ANTHROPIC -> st.baseUrl(p).isNotBlank()
        ProviderType.LOCAL -> false
    }
    val canList = canList()
    fun load() {
        if (!canList()) return
        loading = true; error = null
        scope.launch {
            try { models = AiClients.listModels(p, st) } catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(p, canList) { load() }

    val current = models?.firstOrNull { it.id == model }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().clickable { open = true; if (models == null && !loading) load() },
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Model"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current?.name ?: model.ifBlank { "Choose a model" }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (current != null && current.name != current.id) Text(current.id, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current?.let { ModelFacts(it) }
                if (current?.vision == false) Text(tr("This model can't read images, so it won't work for the solver or handwriting."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.UnfoldMore, tr("Choose model"))
        }
    }
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        !canList && p != ProviderType.LOCAL -> Text(
            if (p.needsBaseUrl) tr("Enter the base URL to load the model list.") else tr("Add your API key to load the model list."),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) ModelDialog(
        provider = p, models = models, loading = loading, current = model,
        onReload = { load() },
        onPick = { id -> model = id; st.setModel(p, id); open = false },
        onDismiss = { open = false },
    )
}

@Composable
private fun ModelFacts(m: ModelInfo) {
    val parts = listOfNotNull(
        m.priceLabel,
        m.contextLabel,
        if (m.vision == true) "reads images" else null,
    )
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
        color = if (m.free) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelDialog(
    provider: ProviderType,
    models: List<ModelInfo>?,
    loading: Boolean,
    current: String,
    onReload: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val knowsVision = models?.any { it.vision != null } == true
    val knowsPrice = models?.any { it.inputPerM != null } == true
    var visionOnly by remember { mutableStateOf(true) }
    var freeOnly by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(ModelSort.NEWEST) }

    val shown = remember(models, query, visionOnly, freeOnly, sort) {
        val q = query.trim().lowercase()
        (models ?: emptyList())
            .filter { q.isEmpty() || q in it.id.lowercase() || q in it.name.lowercase() }
            .filter { !knowsVision || !visionOnly || it.vision == true }
            .filter { !freeOnly || it.free }
            .let { l ->
                when (sort) {
                    ModelSort.NEWEST -> if (l.any { it.created > 0 }) l.sortedByDescending { it.created } else l.sortedBy { it.id }
                    ModelSort.CHEAPEST -> l.sortedBy { (it.inputPerM ?: Double.MAX_VALUE) + (it.outputPerM ?: 0.0) }
                    ModelSort.NAME -> l.sortedBy { it.name.lowercase() }
                }
            }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 760.dp),
        ) {
            Column(Modifier.padding(top = 20.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Choose a model"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            buildString {
                                append(provider.label)
                                models?.let { append(" · ${shown.size} of ${it.size} models") }
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, tr("Reload list")) }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, tr("Close")) }
                }
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(tr("Search, or type a model id")) },
                    shape = RoundedCornerShape(16.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (knowsVision) FilterChip(visionOnly, { visionOnly = !visionOnly }, label = { Text(tr("Reads images")) },
                        leadingIcon = { Icon(Icons.Default.Image, null, Modifier.size(18.dp)) })
                    if (knowsPrice) FilterChip(freeOnly, { freeOnly = !freeOnly }, label = { Text(tr("Free")) })
                    ModelSort.entries.filter { it != ModelSort.CHEAPEST || knowsPrice }.forEach { so ->
                        FilterChip(sort == so, { sort = so }, label = { Text(so.label) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                HorizontalDivider()
                Box(Modifier.weight(1f, fill = false)) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        val typed = query.trim()
                        if (typed.isNotEmpty() && models?.none { it.id == typed } != false) item {
                            ModelRow(ModelInfo(typed, "Use “$typed”"), selected = typed == current, onClick = { onPick(typed) })
                        }
                        if (models == null && loading) item {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp)); Text(tr("Loading models…"))
                            }
                        }
                        if (models != null && shown.isEmpty()) item {
                            Text(tr("No models match."), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(shown, key = { it.id }) { m -> ModelRow(m, selected = m.id == current, onClick = { onPick(m.id) }) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(tr("Done")) }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(m: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.name != m.id) Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ModelFacts(m)
        }
        if (selected) Icon(Icons.Default.Check, tr("Selected"), tint = MaterialTheme.colorScheme.primary)
    }
}
