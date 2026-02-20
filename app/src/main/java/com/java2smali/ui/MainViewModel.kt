package com.java2smali.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.java2smali.deps.DependencyManager
import com.java2smali.engine.JavaSmaliEngine
import com.java2smali.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainViewModel : ViewModel() {

    private data class FileHistory(
        var current: String,
        val undo: MutableList<String> = mutableListOf(),
        val redo: MutableList<String> = mutableListOf()
    )

    companion object {
        private const val MAX_HISTORY_PER_FILE = 1000
    }
    
    private lateinit var workspaceManager: WorkspaceManager
    private lateinit var dependencyManager: DependencyManager
    private lateinit var engine: JavaSmaliEngine
    
    private var activeJavaFile: File? = null
    private var lastDexFile: File? = null
    private val smaliByJavaPath = mutableMapOf<String, String>()
    private val historyByPath = mutableMapOf<String, FileHistory>()
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    fun init(context: Context) {
        workspaceManager = WorkspaceManager(context)
        workspaceManager.switchWorkspace(workspaceManager.currentWorkspaceName())
        dependencyManager = DependencyManager(workspaceManager)
        engine = JavaSmaliEngine(workspaceManager, dependencyManager)
        val defaultFile = workspaceManager.ensureDefaultJavaFile()
        activeJavaFile = defaultFile
        ensureHistoryForFile(defaultFile)
        refreshFiles()
    }

    fun listFiles(): List<JavaSourceFileUi> {
        val out = mutableListOf<JavaSourceFileUi>()
        out.add(
            JavaSourceFileUi(
                path = workspaceManager.currentWorkspaceName(),
                name = workspaceManager.currentWorkspaceName(),
                type = EntryType.WORKSPACE,
                depth = 0
            )
        )
        collectTreeEntries(workspaceManager.srcDir, 1, out)
        return out
    }

    fun getActiveFile(): JavaSourceFileUi? {
        val file = activeJavaFile ?: return null
        return JavaSourceFileUi(path = file.absolutePath, name = file.name, type = EntryType.FILE)
    }

    fun setActiveFile(path: String): String {
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            val fallback = ensureActiveFileValid()
            val history = ensureHistoryForFile(fallback)
            _uiState.value = _uiState.value.copy(
                activeFilePath = fallback.absolutePath,
                activeSmaliText = smaliByJavaPath[fallback.absolutePath]
            )
            return history.current
        }
        activeJavaFile = file
        val history = ensureHistoryForFile(file)
        _uiState.value = _uiState.value.copy(
            activeFilePath = path,
            activeSmaliText = smaliByJavaPath[path]
        )
        return history.current
    }

    fun readActiveJavaCode(): String {
        val file = ensureActiveFileValid()
        return ensureHistoryForFile(file).current
    }

    fun saveJavaCode(code: String) {
        recordActiveCode(code)
    }

    fun recordActiveCode(code: String) {
        val file = ensureActiveFileValid()
        val normalized = normalizeJavaSource(code)
        val history = ensureHistoryForFile(file)
        if (normalized == history.current) return

        history.undo.add(history.current)
        trimHistory(history.undo)
        history.current = normalized
        history.redo.clear()
        workspaceManager.writeText(file, normalized)
        persistHistory(file.absolutePath, history)
    }

    fun undoActive(): String {
        val file = ensureActiveFileValid()
        val history = ensureHistoryForFile(file)
        if (history.undo.isEmpty()) return history.current

        history.redo.add(history.current)
        trimHistory(history.redo)
        history.current = history.undo.removeAt(history.undo.size - 1)
        workspaceManager.writeText(file, history.current)
        persistHistory(file.absolutePath, history)
        return history.current
    }

    fun redoActive(): String {
        val file = ensureActiveFileValid()
        val history = ensureHistoryForFile(file)
        if (history.redo.isEmpty()) return history.current

        history.undo.add(history.current)
        trimHistory(history.undo)
        history.current = history.redo.removeAt(history.redo.size - 1)
        workspaceManager.writeText(file, history.current)
        persistHistory(file.absolutePath, history)
        return history.current
    }

    fun createJavaFile(baseName: String): JavaSourceFileUi {
        val file = workspaceManager.createJavaFile(baseName)
        activeJavaFile = file
        ensureHistoryForFile(file)
        refreshFiles()
        return JavaSourceFileUi(file.absolutePath, file.name, EntryType.FILE)
    }

    fun createJavaFile(baseName: String, parentRelativeFolder: String?): JavaSourceFileUi {
        val file = workspaceManager.createJavaFile(baseName, parentRelativeFolder)
        activeJavaFile = file
        ensureHistoryForFile(file)
        refreshFiles()
        return JavaSourceFileUi(file.absolutePath, file.name, EntryType.FILE)
    }

    fun importJavaFile(context: Context, uri: Uri): JavaSourceFileUi {
        val file = workspaceManager.importJavaFile(context.contentResolver, uri)
        activeJavaFile = file
        ensureHistoryForFile(file)
        refreshFiles()
        return JavaSourceFileUi(file.absolutePath, file.name, EntryType.FILE)
    }

    fun importJavaFile(context: Context, uri: Uri, parentRelativeFolder: String?): JavaSourceFileUi {
        val file = workspaceManager.importJavaFile(context.contentResolver, uri, parentRelativeFolder)
        activeJavaFile = file
        ensureHistoryForFile(file)
        refreshFiles()
        return JavaSourceFileUi(file.absolutePath, file.name, EntryType.FILE)
    }

    fun renameActiveJavaFile(newName: String): JavaSourceFileUi {
        val current = activeJavaFile ?: throw IllegalStateException("没有活动文件")
        val oldPath = current.absolutePath
        val renamed = workspaceManager.renameJavaFile(current, newName)
        activeJavaFile = renamed
        val oldHistory = historyByPath.remove(oldPath)
        if (oldHistory != null) {
            historyByPath[renamed.absolutePath] = oldHistory
            persistHistory(renamed.absolutePath, oldHistory)
            deleteHistory(oldPath)
        } else {
            ensureHistoryForFile(renamed)
        }
        refreshFiles()
        return JavaSourceFileUi(renamed.absolutePath, renamed.name, EntryType.FILE)
    }

    fun createFolder(relativePath: String) {
        workspaceManager.createFolder(relativePath)
        refreshFiles()
    }

    fun listWorkspaceNames(): List<String> = workspaceManager.listWorkspaceNames()

    fun createWorkspace(name: String): String {
        val created = workspaceManager.createWorkspace(name)
        workspaceManager.switchWorkspace(created)
        resetForWorkspaceSwitch()
        return created
    }

    fun switchWorkspace(name: String) {
        workspaceManager.switchWorkspace(name)
        resetForWorkspaceSwitch()
    }

    fun renameWorkspace(oldName: String, newName: String): String {
        val renamed = workspaceManager.renameWorkspace(oldName, newName)
        workspaceManager.switchWorkspace(renamed)
        resetForWorkspaceSwitch()
        return renamed
    }

    fun deleteWorkspace(name: String) {
        workspaceManager.deleteWorkspace(name)
        workspaceManager.switchWorkspace(workspaceManager.currentWorkspaceName())
        resetForWorkspaceSwitch()
    }

    fun renameFolder(path: String, newName: String) {
        val oldPrefix = File(path).absolutePath
        val renamed = workspaceManager.renameFolder(File(path), newName)
        val newPrefix = renamed.absolutePath
        remapPathPrefix(oldPrefix, newPrefix)
        refreshFiles()
    }

    fun deleteFolder(path: String) {
        val folder = File(path)
        val removedPaths = workspaceManager.listJavaFiles()
            .filter { it.absolutePath.startsWith(folder.absolutePath) }
            .map { it.absolutePath }
        workspaceManager.deleteFolder(folder)
        for (removed in removedPaths) {
            smaliByJavaPath.remove(removed)
            historyByPath.remove(removed)
            deleteHistory(removed)
        }
        val remaining = workspaceManager.listJavaFiles()
        if (remaining.isEmpty()) {
            activeJavaFile = workspaceManager.ensureDefaultJavaFile()
            ensureHistoryForFile(activeJavaFile!!)
        } else if (activeJavaFile?.absolutePath?.startsWith(folder.absolutePath) == true) {
            activeJavaFile = remaining.first()
            ensureHistoryForFile(activeJavaFile!!)
        }
        refreshFiles()
    }

    fun moveOrCopyEntry(path: String, targetFolderRelativePath: String, copy: Boolean): String {
        val source = File(path)
        val oldPath = source.absolutePath
        val target = workspaceManager.moveEntryToFolder(source, targetFolderRelativePath, copy)
        if (source.isDirectory) {
            if (!copy) {
                remapPathPrefix(oldPath, target.absolutePath)
            }
            refreshFiles()
            return target.absolutePath
        }
        if (source.isFile && source.name.endsWith(".java", true)) {
            val oldHistory = historyByPath[oldPath]
            if (copy) {
                if (oldHistory != null) {
                    val cloned = FileHistory(
                        current = oldHistory.current,
                        undo = oldHistory.undo.toMutableList(),
                        redo = oldHistory.redo.toMutableList()
                    )
                    historyByPath[target.absolutePath] = cloned
                    persistHistory(target.absolutePath, cloned)
                }
            } else {
                val moved = historyByPath.remove(oldPath)
                if (moved != null) {
                    historyByPath[target.absolutePath] = moved
                    persistHistory(target.absolutePath, moved)
                    deleteHistory(oldPath)
                }
                val smali = smaliByJavaPath.remove(oldPath)
                if (smali != null) smaliByJavaPath[target.absolutePath] = smali
                if (activeJavaFile?.absolutePath == oldPath) {
                    activeJavaFile = target
                }
            }
        }
        refreshFiles()
        return target.absolutePath
    }

    fun relativeFolderFor(path: String): String {
        val file = File(path)
        val src = workspaceManager.srcDir
        val folder = if (file.isDirectory) file else file.parentFile
        if (folder == null) return ""
        return try {
            val rel = folder.relativeTo(src).path.replace('\\', '/')
            if (rel == ".") "" else rel
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    fun listFolderRelativePaths(): List<String> {
        val src = workspaceManager.srcDir
        val folders = workspaceManager.listSourceFolders()
            .mapNotNull {
                try {
                    val rel = it.relativeTo(src).path.replace('\\', '/')
                    if (rel == ".") "" else rel
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .toMutableSet()
        folders.add("")
        return folders.toList().sortedBy { it.lowercase() }
    }

    fun relativePathFor(path: String): String {
        val src = workspaceManager.srcDir
        val file = File(path)
        return try {
            val rel = file.relativeTo(src).path.replace('\\', '/')
            if (rel == ".") "" else rel
        } catch (_: IllegalArgumentException) {
            file.name
        }
    }

    fun clearAllWorkspaces() {
        workspaceManager.clearAllWorkspaces()
        resetForWorkspaceSwitch()
    }

    fun deleteFile(path: String) {
        val file = File(path)
        workspaceManager.deleteJavaFile(file)
        smaliByJavaPath.remove(path)
        historyByPath.remove(path)
        deleteHistory(path)
        val remaining = workspaceManager.listJavaFiles()
        if (remaining.isEmpty()) {
            activeJavaFile = workspaceManager.ensureDefaultJavaFile()
            ensureHistoryForFile(activeJavaFile!!)
        } else if (activeJavaFile?.absolutePath == path) {
            activeJavaFile = remaining.first()
            ensureHistoryForFile(activeJavaFile!!)
        }
        refreshFiles()
    }
    
    fun compileToSmali(javaCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                saveJavaCode(javaCode)
                withContext(Dispatchers.IO) {
                    engine.compileWorkspaceToSmali()
                }

                val smaliMap = withContext(Dispatchers.IO) {
                    engine.readAllSmali()
                }
                updateSmaliCache(smaliMap)
                val activePath = activeJavaFile?.absolutePath
                val activeSmali = if (activePath == null) null else smaliByJavaPath[activePath]
                lastDexFile = workspaceManager.dexFile

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    smaliResult = activeSmali,
                    activeSmaliText = activeSmali,
                    dependencies = dependencyManager.listDependencyNames()
                )
            } catch (e: Exception) {
                val activePath = activeJavaFile?.absolutePath
                if (activePath != null) {
                    smaliByJavaPath[activePath] = "转换失败"
                }
                val failedSmali = if (activePath == null) null else smaliByJavaPath[activePath]
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "未知错误",
                    smaliResult = null,
                    activeSmaliText = failedSmali
                )
            }
        }
    }

    fun getSmaliForFile(path: String): String {
        return smaliByJavaPath[path] ?: "该文件还没有对应的 Smali，请先转换"
    }
    
    fun importDependency(context: Context, uri: Uri): File {
        val result = dependencyManager.importDependency(context, uri)
        _uiState.value = _uiState.value.copy(
            dependencies = dependencyManager.listDependencyNames()
        )
        return result
    }

    fun preflightDependency(context: Context, uri: Uri): DependencyManager.PreflightResult {
        return dependencyManager.preflightDependency(context, uri)
    }

    fun importDependency(context: Context, uri: Uri, includeInMerge: Boolean): File {
        val result = dependencyManager.importDependency(context, uri, includeInMerge)
        _uiState.value = _uiState.value.copy(
            dependencies = dependencyManager.listDependencyNames()
        )
        return result
    }

    fun listDependencyClasses(): List<String> {
        return dependencyManager.listAvailableClasses()
    }

    fun listDependencyFiles(): List<DependencyManager.DependencyFilePreview> {
        return dependencyManager.listDependencyFilePreviews()
    }

    fun listClassesInDependency(fileName: String): List<String> {
        return dependencyManager.listClassesInFile(fileName)
    }

    fun listMembersInDependencyClass(fileName: String, className: String): List<String> {
        return dependencyManager.listMembersInClass(fileName, className)
    }

    fun deleteDependencyFile(fileName: String) {
        dependencyManager.deleteDependencyFile(fileName)
        _uiState.value = _uiState.value.copy(dependencies = dependencyManager.listDependencyNames())
    }

    fun deleteDependencyClass(fileName: String, className: String) {
        dependencyManager.deleteClassFromFile(fileName, className)
        _uiState.value = _uiState.value.copy(dependencies = dependencyManager.listDependencyNames())
    }

    fun clearDependencies() {
        dependencyManager.clearAllDependencies()
        _uiState.value = _uiState.value.copy(dependencies = dependencyManager.listDependencyNames())
    }

    fun isBuiltinDependency(fileName: String): Boolean {
        return dependencyManager.isBuiltinDependency(fileName)
    }

    fun searchOccurrences(query: String, ignoreCase: Boolean, workspace: Boolean): Int {
        if (query.isEmpty()) return 0
        val files = if (workspace) workspaceManager.listJavaFiles() else listOf(ensureActiveFileValid())
        var total = 0
        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            val text = runCatching { workspaceManager.readText(file) }.getOrNull() ?: continue
            total += countOccurrences(text, query, ignoreCase)
        }
        return total
    }

    fun replaceOccurrences(search: String, replace: String, ignoreCase: Boolean, workspace: Boolean): Int {
        if (search.isEmpty()) return 0
        val files = if (workspace) workspaceManager.listJavaFiles() else listOf(ensureActiveFileValid())
        var total = 0
        for (file in files) {
            if (!file.exists() || !file.isFile) continue
            val history = ensureHistoryForFile(file)
            val text = history.current
            val count = countOccurrences(text, search, ignoreCase)
            if (count > 0) {
                val updated = replaceAll(text, search, replace, ignoreCase)
                history.undo.add(history.current)
                trimHistory(history.undo)
                history.current = updated
                history.redo.clear()
                workspaceManager.writeText(file, updated)
                persistHistory(file.absolutePath, history)
                total += count
            }
        }
        return total
    }
    
    fun getDexFile(): File? {
        return lastDexFile?.takeIf { it.exists() }
    }
    
    fun formatCode(code: String, isSmali: Boolean): String {
        return if (isSmali) {
            formatSmali(code)
        } else {
            formatJava(code)
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun refreshFiles() {
        val files = listFiles()
        val activePath = ensureActiveFileValid().absolutePath
        _uiState.value = _uiState.value.copy(
            files = files,
            activeFilePath = activePath,
            activeSmaliText = smaliByJavaPath[activePath],
            dependencies = dependencyManager.listDependencyNames(),
            activeWorkspaceName = workspaceManager.currentWorkspaceName()
        )
    }

    private fun remapPathPrefix(oldPrefix: String, newPrefix: String) {
        if (oldPrefix == newPrefix) return

        val historyUpdates = historyByPath.entries
            .filter { it.key == oldPrefix || it.key.startsWith(oldPrefix + File.separator) }
            .map { entry ->
                val old = entry.key
                val suffix = old.removePrefix(oldPrefix)
                val next = newPrefix + suffix
                Triple(old, next, entry.value)
            }
        for ((old, next, history) in historyUpdates) {
            historyByPath.remove(old)
            historyByPath[next] = history
            persistHistory(next, history)
            deleteHistory(old)
        }

        val smaliUpdates = smaliByJavaPath.entries
            .filter { it.key == oldPrefix || it.key.startsWith(oldPrefix + File.separator) }
            .map { entry ->
                val old = entry.key
                val suffix = old.removePrefix(oldPrefix)
                val next = newPrefix + suffix
                Triple(old, next, entry.value)
            }
        for ((old, next, smali) in smaliUpdates) {
            smaliByJavaPath.remove(old)
            smaliByJavaPath[next] = smali
        }

        val active = activeJavaFile?.absolutePath
        if (active != null && (active == oldPrefix || active.startsWith(oldPrefix + File.separator))) {
            val suffix = active.removePrefix(oldPrefix)
            val candidate = File(newPrefix + suffix)
            activeJavaFile = candidate
        }
    }

    private fun resetForWorkspaceSwitch() {
        smaliByJavaPath.clear()
        historyByPath.clear()
        val currentFiles = workspaceManager.listJavaFiles()
        activeJavaFile = if (currentFiles.isEmpty()) {
            workspaceManager.ensureDefaultJavaFile()
        } else {
            currentFiles.first()
        }
        ensureHistoryForFile(activeJavaFile!!)
        refreshFiles()
    }

    private fun collectTreeEntries(dir: File, depth: Int, out: MutableList<JavaSourceFileUi>) {
        val children = (dir.listFiles() ?: emptyArray()).sortedWith(
            compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
        for (child in children) {
            if (child.isDirectory) {
                out.add(
                    JavaSourceFileUi(
                        path = child.absolutePath,
                        name = child.name,
                        type = EntryType.FOLDER,
                        depth = depth
                    )
                )
                collectTreeEntries(child, depth + 1, out)
            } else if (child.name.endsWith(".java", true)) {
                out.add(
                    JavaSourceFileUi(
                        path = child.absolutePath,
                        name = child.name,
                        type = EntryType.FILE,
                        depth = depth
                    )
                )
            }
        }
    }

    private fun updateSmaliCache(smaliMap: Map<String, String>) {
        val javaFiles = workspaceManager.listJavaFiles()
        for (javaFile in javaFiles) {
            val content = workspaceManager.readText(javaFile)
            val pkg = extractPackageName(content)
            val cls = extractTypeName(content).ifEmpty { javaFile.nameWithoutExtension }
            val key = if (pkg.isEmpty()) {
                "$cls.smali"
            } else {
                pkg.replace('.', '/') + "/$cls.smali"
            }
            val resolved = smaliMap[key]
                ?: smaliMap["$cls.smali"]
                ?: "该文件还没有对应的 Smali，请先转换"
            smaliByJavaPath[javaFile.absolutePath] = resolved
        }
    }

    private fun extractPackageName(code: String): String {
        val regex = Regex("""^\s*package\s+([A-Za-z_][\w.]*)\s*;""", RegexOption.MULTILINE)
        return regex.find(code)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractTypeName(code: String): String {
        val regex = Regex(
            """^\s*(?:public\s+)?(?:abstract\s+|final\s+)?(?:class|interface|enum|record)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
            RegexOption.MULTILINE
        )
        return regex.find(code)?.groupValues?.getOrNull(1) ?: ""
    }
    
    private fun formatSmali(input: String): String {
        val lines = input.replace("\r\n", "\n").split("\n")
        val output = StringBuilder(input.length + 32)
        
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trimEnd()
            
            if (trimmed.isEmpty()) {
                output.append('\n')
                continue
            }
            
            if (trimmed.startsWith(".") || trimmed.startsWith("#") || trimmed.startsWith(":")) {
                output.append(trimmed)
            } else {
                output.append("    ").append(trimmed.trimStart())
            }
            
            if (index < lines.size - 1) {
                output.append('\n')
            }
        }
        
        return output.toString()
    }
    
    private fun formatJava(input: String): String {
        val lines = input.replace("\r\n", "\n").split("\n")
        val output = StringBuilder(input.length + 32)
        var indent = 0
        
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            
            if (trimmed.isEmpty()) {
                output.append('\n')
                continue
            }
            
            if (trimmed.startsWith("}")) {
                indent = maxOf(0, indent - 1)
            }
            
            output.append("    ".repeat(indent)).append(trimmed)
            
            if (trimmed.endsWith("{")) {
                indent++
            }
            
            if (index < lines.size - 1) {
                output.append('\n')
            }
        }
        
        return output.toString()
    }

    private fun normalizeJavaSource(input: String): String {
        return input.replace("\t", "    ")
    }

    private fun countOccurrences(text: String, query: String, ignoreCase: Boolean): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = text.indexOf(query, startIndex = start, ignoreCase = ignoreCase)
            if (index < 0) break
            count++
            start = index + maxOf(1, query.length)
        }
        return count
    }

    private fun replaceAll(text: String, search: String, replace: String, ignoreCase: Boolean): String {
        return if (!ignoreCase) {
            text.replace(search, replace)
        } else {
            val regex = Regex(Regex.escape(search), setOf(RegexOption.IGNORE_CASE))
            text.replace(regex, replace)
        }
    }

    private fun ensureHistoryForFile(file: File): FileHistory {
        val path = file.absolutePath
        historyByPath[path]?.let { return it }

        val history = loadHistory(path) ?: FileHistory(current = workspaceManager.readText(file))
        historyByPath[path] = history
        workspaceManager.writeText(file, history.current)
        persistHistory(path, history)
        return history
    }

    private fun ensureActiveFileValid(): File {
        val current = activeJavaFile
        if (current != null && current.exists() && current.isFile) {
            return current
        }
        val files = workspaceManager.listJavaFiles()
        val resolved = if (files.isEmpty()) {
            workspaceManager.ensureDefaultJavaFile()
        } else {
            files.first()
        }
        activeJavaFile = resolved
        if (!historyByPath.containsKey(resolved.absolutePath)) {
            ensureHistoryForFile(resolved)
        }
        return resolved
    }

    private fun historyFile(path: String): File {
        val key = (path.hashCode().toUInt().toString(16) + "_" + path.length)
        return File(workspaceManager.historyDir, "$key.json")
    }

    private fun loadHistory(path: String): FileHistory? {
        val file = historyFile(path)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(workspaceManager.readText(file))
            if (json.optString("path") != path) return null
            val current = json.optString("current", "")
            val undo = mutableListOf<String>()
            val redo = mutableListOf<String>()
            val undoArr = json.optJSONArray("undo") ?: JSONArray()
            val redoArr = json.optJSONArray("redo") ?: JSONArray()
            for (i in 0 until undoArr.length()) undo.add(undoArr.optString(i, ""))
            for (i in 0 until redoArr.length()) redo.add(redoArr.optString(i, ""))
            trimHistory(undo)
            trimHistory(redo)
            FileHistory(current = current, undo = undo, redo = redo)
        } catch (_: Throwable) {
            null
        }
    }

    private fun persistHistory(path: String, history: FileHistory) {
        trimHistory(history.undo)
        trimHistory(history.redo)
        val file = historyFile(path)
        val json = JSONObject()
        json.put("path", path)
        json.put("current", history.current)
        json.put("undo", JSONArray(history.undo))
        json.put("redo", JSONArray(history.redo))
        workspaceManager.writeText(file, json.toString())
    }

    private fun deleteHistory(path: String) {
        val f = historyFile(path)
        if (f.exists()) f.delete()
    }

    private fun trimHistory(list: MutableList<String>) {
        while (list.size > MAX_HISTORY_PER_FILE) {
            list.removeAt(0)
        }
    }
}
