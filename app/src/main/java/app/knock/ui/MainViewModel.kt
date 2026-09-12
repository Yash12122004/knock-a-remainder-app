package app.knock.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.knock.KnockApp
import app.knock.data.Repository
import app.knock.data.Settings
import app.knock.data.SettingsStore
import app.knock.data.Task
import app.knock.parse.ParsedTask
import app.knock.parse.TaskParser
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MainViewModel(val repo: Repository, val settingsStore: SettingsStore) : ViewModel() {
    val tasks: StateFlow<List<Task>> = repo.observeTasks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<Settings> = settingsStore.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    var parsed by mutableStateOf<List<ParsedTask>>(emptyList())
        private set
    var transcript by mutableStateOf("")
        private set
    var streak by mutableStateOf(0)
        private set
    var lastAddedCount by mutableStateOf(0)

    init {
        viewModelScope.launch { tasks.collect { streak = repo.streak(it) } }
    }

    fun parse(text: String) {
        transcript = text
        parsed = TaskParser.parse(text, LocalDateTime.now(), settings.value.bareNumberRule)
    }

    fun updateParsed(index: Int, p: ParsedTask) { parsed = parsed.toMutableList().also { it[index] = p } }
    fun removeParsed(index: Int) { parsed = parsed.toMutableList().also { it.removeAt(index) } }

    /** Flags near-duplicates against pending tasks. */
    fun duplicateOf(p: ParsedTask): Task? =
        tasks.value.firstOrNull { it.state == app.knock.data.TaskState.PENDING && it.title.equals(p.title, ignoreCase = true) }

    fun addParsed(list: List<ParsedTask> = parsed, onDone: () -> Unit = {}) = viewModelScope.launch {
        repo.addParsed(list); lastAddedCount = list.size; parsed = emptyList(); onDone()
    }

    fun quickAdd(text: String, onDone: () -> Unit = {}) {
        val s = settings.value
        parse(text)
        if (!s.confirmBeforeAdd) addParsed(onDone = onDone) else onDone()
    }

    fun done(id: Long) = viewModelScope.launch { repo.markDone(id) }
    fun undo(id: Long) = viewModelScope.launch { repo.undoDone(id) }
    fun snooze(id: Long, until: LocalDateTime) = viewModelScope.launch { repo.snooze(id, until) }
    fun skip(id: Long, reason: String, allFuture: Boolean = false) = viewModelScope.launch { repo.skip(id, reason, allFuture) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun update(task: Task) = viewModelScope.launch { repo.update(task) }
    fun reschedule(id: Long, day: LocalDate, time: LocalTime?) = viewModelScope.launch { repo.rescheduleTo(id, day, time) }
    fun loadDemo() = viewModelScope.launch { repo.loadDemo() }
    fun clearAll() = viewModelScope.launch { repo.clearAll() }
    fun moveAllToToday() = viewModelScope.launch { repo.moveAllToToday() }
    fun applyZoneChange(keepLocal: Boolean) = viewModelScope.launch { repo.applyZoneChange(keepLocal) }
    fun updateSettings(transform: (Settings) -> Settings) = viewModelScope.launch { settingsStore.update(transform); repo.rescheduleAll() }
    fun dismissForceStopWarning() = updateSettings { it.copy(forceStopWarning = false) }

    fun export(onResult: (String) -> Unit) = viewModelScope.launch { onResult(repo.exportJson()) }
    fun import(json: String, onResult: (Result<Int>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { repo.importJson(json) })
    }

    class Factory(private val app: KnockApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(app.repo, app.settings) as T
    }
}
