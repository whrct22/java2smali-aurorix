package com.java2smali

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.java2smali.deps.DependencyManager
import com.java2smali.databinding.ActivityMainBinding
import com.java2smali.editor.DependencyAwareJavaLanguage
import com.java2smali.ui.DependencyClassAdapter
import com.java2smali.ui.DependencyFileAdapter
import com.java2smali.ui.JavaFileAdapter
import com.java2smali.ui.MainUiState
import com.java2smali.ui.MainViewModel
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var editor: CodeEditor
    private lateinit var fileAdapter: JavaFileAdapter
    private val viewModel: MainViewModel by viewModels()

    private var isSmaliMode = false
    private var pendingImportType: ImportType? = null
    private var selectedFilePath: String? = null
    private var isSidebarCollapsed = false
    private var sidebarExpandedWidthPx = 0
    private var allFileItems: List<com.java2smali.ui.JavaSourceFileUi> = emptyList()
    private var fileFilterQuery: String = ""
    private var isWordWrapEnabled = false
    private var suppressHistoryRecording = false
    private var pendingImportTargetFolder: String? = null

    private val uiPrefs by lazy { getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }

    private enum class ImportType {
        JAVA_FILE,
        DEPENDENCY
    }

    companion object {
        private const val KEY_SEARCH_IGNORE_CASE = "search_ignore_case"
        private const val KEY_SEARCH_WORKSPACE = "search_workspace"
        private const val KEY_WORD_WRAP = "word_wrap"
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleFileImport(it) }
    }

    private val exportDexLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { exportDexToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        viewModel.init(this)
        isWordWrapEnabled = uiPrefs.getBoolean(KEY_WORD_WRAP, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEditor()
        setupFileList()
        setupListeners()
        observeState()

        val initialCode = viewModel.readActiveJavaCode()
        setEditorTextSilently(initialCode)
        sidebarExpandedWidthPx = binding.fileSidebar.layoutParams.width
        collapseSidebarImmediately()
        updateWrapMenuItem()
        updateConvertButtonState()
    }

    private fun setupEditor() {
        editor = binding.codeEditor
        editor.apply {
            typefaceText = Typeface.MONOSPACE
            setTextSize(14f)
            isLineNumberEnabled = true
            isBlockLineEnabled = true
            isHighlightCurrentBlock = true
            tabWidth = 4
            setEditorLanguage(createJavaLanguage())
            setEditable(true)
            setVerticalScrollBarEnabled(true)
            setHorizontalScrollBarEnabled(true)
            setWordwrap(false)
            setDividerWidth(0f)
        }
        editor.setWordwrap(isWordWrapEnabled)

        val scheme = EditorColorScheme()
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, getColor(R.color.editor_bg))
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, getColor(R.color.editor_bg))
        scheme.setColor(EditorColorScheme.LINE_NUMBER, getColor(R.color.editor_line_number))
        scheme.setColor(EditorColorScheme.CURRENT_LINE, getColor(R.color.editor_current_line))
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, getColor(R.color.editor_selection))
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, getColor(R.color.editor_selection))
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, getColor(R.color.on_background))
        scheme.setColor(EditorColorScheme.KEYWORD, getColor(R.color.syntax_keyword))
        scheme.setColor(EditorColorScheme.COMMENT, getColor(R.color.syntax_comment))
        scheme.setColor(EditorColorScheme.LITERAL, getColor(R.color.syntax_string))
        scheme.setColor(EditorColorScheme.FUNCTION_NAME, getColor(R.color.syntax_method))
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, getColor(R.color.bracket_highlight_fg))
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, getColor(R.color.bracket_highlight_bg))
        scheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, getColor(R.color.bracket_highlight_underline))
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, getColor(R.color.search_match_bg))
        editor.setColorScheme(scheme)

        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ ->
            updateCursorStats()
        }
        editor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            if (!suppressHistoryRecording && !isSmaliMode) {
                viewModel.recordActiveCode(editor.text.toString())
            }
            updateCursorStats()
        }
        updateCursorStats()
    }

    private fun setupFileList() {
        fileAdapter = JavaFileAdapter(
            onClick = { item ->
                when (item.type) {
                    com.java2smali.ui.EntryType.FILE -> {
                        lifecycleScope.launch {
                            if (!isSmaliMode) {
                                viewModel.saveJavaCode(editor.text.toString().replace("\t", "    "))
                            }
                            val code = viewModel.setActiveFile(item.path)
                            selectedFilePath = item.path
                            if (isSmaliMode) {
                                val smali = viewModel.getSmaliForFile(item.path)
                                binding.chipJava.isChecked = false
                                binding.chipSmali.isChecked = true
                                binding.toolbar.subtitle = getString(R.string.smali_mode)
                                editor.setEditorLanguage(createJavaLanguage())
                                editor.setEditable(false)
                                setEditorTextSilently(smali)
                            } else {
                                binding.chipJava.isChecked = true
                                binding.chipSmali.isChecked = false
                                binding.toolbar.subtitle = getString(R.string.java_mode)
                                editor.setEditorLanguage(createJavaLanguage())
                                editor.setEditable(true)
                                setEditorTextSilently(code)
                            }
                        }
                    }

                    com.java2smali.ui.EntryType.WORKSPACE -> {
                        showSwitchWorkspaceDialog()
                    }

                    com.java2smali.ui.EntryType.FOLDER -> {
                        selectedFilePath = item.path
                    }
                }
            },
            onLongClick = { item ->
                showEntryActionDialog(item)
            }
        )
        binding.rvFiles.layoutManager = LinearLayoutManager(this)
        binding.rvFiles.adapter = fileAdapter
    }

    private fun setupListeners() {
        binding.btnUndo.setOnClickListener {
            if (!isSmaliMode) {
                setEditorTextSilently(viewModel.undoActive())
            }
        }
        binding.btnRedo.setOnClickListener {
            if (!isSmaliMode) {
                setEditorTextSilently(viewModel.redoActive())
            }
        }
        binding.btnFormat.setOnClickListener { formatCurrentCode() }
        binding.btnCopyView.setOnClickListener { copyToClipboard(editor.text.toString()) }
        binding.btnConvert.setOnClickListener { runConversion() }

        binding.chipJava.setOnClickListener {
            if (isSmaliMode) {
                switchToJavaMode()
            }
        }
        binding.chipSmali.setOnClickListener {
            if (!isSmaliMode) {
                runConversion()
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            handleMenuItemClick(item)
        }
        binding.toolbar.setNavigationOnClickListener { toggleSidebar() }
        configureIndentActionLongPress()

        binding.btnNewFile.setOnClickListener { showCreateFileDialog() }
        binding.btnImportJavaFile.setOnClickListener {
            pendingImportType = ImportType.JAVA_FILE
            pendingImportTargetFolder = selectedFolderForNewEntries()
            importFileLauncher.launch(arrayOf("text/x-java-source", "text/plain", "*/*"))
        }
        binding.btnRenameFile.setOnClickListener { showCreateFolderDialog() }
        binding.btnDeleteFile.setOnClickListener { showCreateWorkspaceDialog() }
        binding.editFileFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                fileFilterQuery = s?.toString().orEmpty().trim()
                applyFileFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                updateUi(state)
            }
        }
    }

    private fun updateUi(state: MainUiState) {
        allFileItems = state.files
        applyFileFilter(state.activeFilePath)
        selectedFilePath = state.activeFilePath
        if (!isSmaliMode) {
            binding.toolbar.subtitle = getString(R.string.workspace_now, state.activeWorkspaceName)
        }

        if (state.error != null) {
            showError(state.error)
            viewModel.clearError()
        }

        if (isSmaliMode && state.activeSmaliText != null && !state.isLoading) {
            switchToSmaliMode(state.activeSmaliText)
        } else if (!isSmaliMode && state.smaliResult != null && !state.isLoading) {
            switchToSmaliMode(state.smaliResult)
        }
    }

    private fun handleMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_indent -> {
                indentSelection()
                true
            }

            R.id.action_comment -> {
                toggleCommentSelection()
                true
            }

            R.id.action_search -> {
                showSearchReplaceDialog()
                true
            }

            R.id.action_wrap -> {
                toggleWordWrap()
                true
            }

            R.id.action_import_dep -> {
                pendingImportType = ImportType.DEPENDENCY
                importFileLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                true
            }

            R.id.action_export_dex -> {
                exportDex()
                true
            }

            R.id.action_preview_dep -> {
                showDependencyPreviewDialog()
                true
            }

            else -> false
        }
    }

    private fun showDependencyPreviewDialog() {
        var allFiles = viewModel.listDependencyFiles()
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dependency_list, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editDependencySearch)
        val txtCount = dialogView.findViewById<TextView>(R.id.txtDependencyCount)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDependencyList)
        recycler.layoutManager = LinearLayoutManager(this)

        var applyFilter: (String) -> Unit = {}
        var previewDialog: androidx.appcompat.app.AlertDialog? = null

        val adapter = DependencyFileAdapter(
            onClick = { file ->
                previewDialog?.dismiss()
                showDependencyClassDialog(file.fileName, reopenPreviewOnClose = true)
            },
            onLongClick = { file ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(file.fileName)
                    .setItems(
                        if (file.isBuiltin) {
                            arrayOf(getString(R.string.copy_name))
                        } else {
                            arrayOf(getString(R.string.copy_name), getString(R.string.delete_dependency_file))
                        }
                    ) { _, which ->
                        when (which) {
                            0 -> copyToClipboard(file.fileName)
                            1 -> {
                                MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.delete_dependency_file))
                                    .setMessage(getString(R.string.confirm_delete_dependency_file, file.fileName))
                                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                                        viewModel.deleteDependencyFile(file.fileName)
                                        allFiles = viewModel.listDependencyFiles()
                                        applyFilter(editSearch.text?.toString().orEmpty().trim())
                                    }
                                    .setNegativeButton(getString(android.R.string.cancel), null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        recycler.adapter = adapter

        applyFilter = { query ->
            val filtered = if (query.isBlank()) allFiles else allFiles.filter {
                it.fileName.contains(query, ignoreCase = true)
            }
            adapter.submit(filtered)
            txtCount.text = getString(R.string.dependency_count, filtered.size)
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        applyFilter("")

        previewDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dependency_preview))
            .setView(dialogView)
            .setNeutralButton(getString(R.string.clear_dependencies)) { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.clear_dependencies))
                    .setMessage(getString(R.string.confirm_clear_dependencies))
                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                        viewModel.clearDependencies()
                        allFiles = viewModel.listDependencyFiles()
                        applyFilter(editSearch.text?.toString().orEmpty().trim())
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            }
            .setPositiveButton(getString(android.R.string.ok), null)
            .show()
    }

    private fun showDependencyClassDialog(fileName: String, reopenPreviewOnClose: Boolean = false) {
        var allClasses = viewModel.listClassesInDependency(fileName)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dependency_list, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editDependencySearch)
        val txtCount = dialogView.findViewById<TextView>(R.id.txtDependencyCount)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDependencyList)
        recycler.layoutManager = LinearLayoutManager(this)

        var applyFilter: (String) -> Unit = {}
        var classDialog: androidx.appcompat.app.AlertDialog? = null
        var navigatingDeeper = false

        val adapter = DependencyClassAdapter(
            onClick = { className ->
                navigatingDeeper = true
                classDialog?.dismiss()
                showDependencyMemberDialog(fileName, className) {
                    showDependencyClassDialog(fileName, reopenPreviewOnClose = true)
                }
            },
            onLongClick = { className ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(className)
                    .setItems(
                        if (viewModel.isBuiltinDependency(fileName)) {
                            arrayOf(getString(R.string.copy_name))
                        } else {
                            arrayOf(getString(R.string.copy_name), getString(R.string.delete_dependency_class))
                        }
                    ) { _, which ->
                        when (which) {
                            0 -> copyToClipboard(className)
                            1 -> {
                                MaterialAlertDialogBuilder(this)
                                    .setTitle(getString(R.string.delete_dependency_class))
                                    .setMessage(getString(R.string.confirm_delete_dependency_class, fileName, className))
                                    .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                                        viewModel.deleteDependencyClass(fileName, className)
                                        allClasses = viewModel.listClassesInDependency(fileName)
                                        applyFilter(editSearch.text?.toString().orEmpty().trim())
                                    }
                                    .setNegativeButton(getString(android.R.string.cancel), null)
                                    .show()
                            }
                        }
                    }
                    .show()
            }
        )
        recycler.adapter = adapter

        applyFilter = { query ->
            val filtered = if (query.isBlank()) allClasses else allClasses.filter {
                it.contains(query, ignoreCase = true)
            }
            adapter.submit(filtered)
            txtCount.text = getString(R.string.dependency_count, filtered.size)
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        applyFilter("")

        classDialog = MaterialAlertDialogBuilder(this)
            .setTitle(fileName)
            .setView(dialogView)
            .setPositiveButton(getString(android.R.string.ok), null)
            .show()

        if (reopenPreviewOnClose) {
            classDialog.setOnDismissListener {
                if (!navigatingDeeper) {
                    showDependencyPreviewDialog()
                }
            }
        }
    }

    private fun showDependencyMemberDialog(fileName: String, className: String, onClose: () -> Unit) {
        val members = viewModel.listMembersInDependencyClass(fileName, className)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_dependency_list, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editDependencySearch)
        val txtCount = dialogView.findViewById<TextView>(R.id.txtDependencyCount)
        val recycler = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDependencyList)
        recycler.layoutManager = LinearLayoutManager(this)

        val adapter = DependencyClassAdapter(onClick = {}, onLongClick = { entry ->
            copyToClipboard(entry)
        })
        recycler.adapter = adapter

        val applyFilter: (String) -> Unit = { query ->
            val filtered = if (query.isBlank()) members else members.filter {
                it.contains(query, ignoreCase = true)
            }
            adapter.submit(filtered)
            txtCount.text = getString(R.string.dependency_count, filtered.size)
        }

        editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(s?.toString().orEmpty().trim())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        applyFilter("")

        MaterialAlertDialogBuilder(this)
            .setTitle(className)
            .setView(dialogView)
            .setPositiveButton(getString(android.R.string.ok), null)
            .setOnDismissListener { onClose() }
            .show()
    }

    private fun toggleSidebar() {
        val panel = binding.fileSidebar
        val lp = panel.layoutParams
        val startWidth = if (panel.visibility == View.VISIBLE) lp.width else 0
        val targetCollapse = !isSidebarCollapsed
        val endWidth = if (targetCollapse) 0 else sidebarExpandedWidthPx

        if (!targetCollapse) {
            panel.visibility = View.VISIBLE
            panel.alpha = 0f
        }

        ValueAnimator.ofInt(startWidth, endWidth).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val w = animator.animatedValue as Int
                lp.width = w
                panel.layoutParams = lp
                panel.alpha = if (sidebarExpandedWidthPx == 0) 1f else (w.toFloat() / sidebarExpandedWidthPx).coerceIn(0f, 1f)
            }
            doOnEnd {
                if (targetCollapse) {
                    panel.visibility = View.GONE
                } else {
                    panel.alpha = 1f
                }
                isSidebarCollapsed = targetCollapse
            }
        }.start()
    }

    private fun handleFileImport(uri: Uri) {
        when (pendingImportType) {
            ImportType.JAVA_FILE -> importJavaFile(uri)
            ImportType.DEPENDENCY -> importDependency(uri)
            null -> Unit
        }
        pendingImportType = null
        pendingImportTargetFolder = null
    }

    private fun importJavaFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val file = viewModel.importJavaFile(this@MainActivity, uri, pendingImportTargetFolder)
                selectedFilePath = file.path
                val code = viewModel.readActiveJavaCode()
                isSmaliMode = false
                binding.chipJava.isChecked = true
                binding.chipSmali.isChecked = false
                binding.toolbar.subtitle = getString(R.string.java_mode)
                editor.setEditorLanguage(createJavaLanguage())
                editor.setEditable(true)
                setEditorTextSilently(code)
                showToast(getString(R.string.import_success))
            } catch (e: Exception) {
                showError("导入失败: ${e.message}")
            }
        }
    }

    private fun importDependency(uri: Uri) {
        lifecycleScope.launch {
            try {
                val preflight = withContext(Dispatchers.IO) {
                    viewModel.preflightDependency(this@MainActivity, uri)
                }

                if (!preflight.isDex) {
                    val result = withContext(Dispatchers.IO) {
                        viewModel.importDependency(this@MainActivity, uri)
                    }
                    showToast("依赖已导入: ${result.name}")
                    return@launch
                }

                val warning = preflight.warningMessage?.let { "\n\n$it" } ?: ""
                val message = buildString {
                    append("文件: ${preflight.fileName}\n")
                    append("DEX 解析: ${if (preflight.dexParsable) "通过" else "失败"}\n")
                    append("D8 预检: ${if (preflight.d8StandaloneOk) "通过" else "失败"}")
                    append(warning)
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(getString(R.string.preflight_title))
                    .setMessage(message)
                    .setPositiveButton(getString(R.string.preflight_import_continue)) { _, _ ->
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                viewModel.importDependency(this@MainActivity, uri)
                            }
                            showToast("依赖已导入: ${result.name}")
                        }
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            } catch (e: Exception) {
                showError("导入失败: ${e.message}")
            }
        }
    }

    private fun showCreateFileDialog() {
        val input = EditText(this)
        input.hint = "Aurorix"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_file))
            .setView(input)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val base = input.text?.toString()?.trim().orEmpty().ifEmpty { "Aurorix" }
                val created = viewModel.createJavaFile(base, selectedFolderForNewEntries())
                selectedFilePath = created.path
                isSmaliMode = false
                editor.setEditorLanguage(createJavaLanguage())
                editor.setEditable(true)
                setEditorTextSilently(viewModel.readActiveJavaCode())
                binding.chipJava.isChecked = true
                binding.chipSmali.isChecked = false
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this)
        input.hint = "com/example"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_folder))
            .setView(input)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val rel = input.text?.toString()?.trim().orEmpty()
                if (rel.isNotEmpty()) {
                    viewModel.createFolder(rel)
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showCreateWorkspaceDialog() {
        val input = EditText(this)
        input.hint = "workspace"
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.new_workspace))
            .setView(input)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty().ifEmpty { "workspace" }
                val created = viewModel.createWorkspace(name)
                binding.toolbar.subtitle = getString(R.string.workspace_now, created)
                selectedFilePath = viewModel.getActiveFile()?.path
                setEditorTextSilently(viewModel.readActiveJavaCode())
                switchToJavaMode()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showSwitchWorkspaceDialog() {
        val names = viewModel.listWorkspaceNames()
        if (names.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.switch_workspace))
            .setItems(names.toTypedArray()) { _, which ->
                val name = names[which]
                viewModel.switchWorkspace(name)
                binding.toolbar.subtitle = getString(R.string.workspace_now, name)
                selectedFilePath = viewModel.getActiveFile()?.path
                setEditorTextSilently(viewModel.readActiveJavaCode())
                switchToJavaMode()
            }
            .setPositiveButton(getString(R.string.clear_all_workspaces)) { _, _ ->
                showClearAllWorkspacesConfirmDialog()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(ContextCompat.getColor(this, R.color.primary))
    }

    private fun showEntryActionDialog(item: com.java2smali.ui.JavaSourceFileUi) {
        val actions = arrayOf(
            getString(R.string.rename_file),
            getString(R.string.delete_file),
            getString(R.string.move_entry),
            getString(R.string.copy_entry),
            getString(R.string.copy_relative_path)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> showRenameEntryDialog(item)
                    1 -> confirmDeleteEntry(item)
                    2 -> showMoveOrCopyDialog(item, copy = false)
                    3 -> showMoveOrCopyDialog(item, copy = true)
                    4 -> copyToClipboard(viewModel.relativePathFor(item.path))
                }
            }
            .show()
    }

    private fun showClearAllWorkspacesConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.clear_all_workspaces))
            .setMessage(getString(R.string.confirm_clear_all_workspaces))
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                viewModel.clearAllWorkspaces()
                val now = viewModel.uiState.value.activeWorkspaceName
                binding.toolbar.subtitle = getString(R.string.workspace_now, now)
                selectedFilePath = viewModel.getActiveFile()?.path
                setEditorTextSilently(viewModel.readActiveJavaCode())
                switchToJavaMode()
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showRenameEntryDialog(item: com.java2smali.ui.JavaSourceFileUi) {
        val input = EditText(this)
        input.hint = getString(R.string.input_file_name)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.rename_file))
            .setView(input)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                val base = input.text?.toString()?.trim().orEmpty()
                if (base.isEmpty()) return@setPositiveButton
                when (item.type) {
                    com.java2smali.ui.EntryType.FILE -> {
                        if (selectedFilePath != item.path) {
                            viewModel.setActiveFile(item.path)
                        }
                        val renamed = viewModel.renameActiveJavaFile(base)
                        selectedFilePath = renamed.path
                        setEditorTextSilently(viewModel.readActiveJavaCode())
                        switchToJavaMode()
                    }

                    com.java2smali.ui.EntryType.FOLDER -> {
                        viewModel.renameFolder(item.path, base)
                    }

                    com.java2smali.ui.EntryType.WORKSPACE -> {
                        val renamed = viewModel.renameWorkspace(item.path, base)
                        binding.toolbar.subtitle = getString(R.string.workspace_now, renamed)
                        selectedFilePath = viewModel.getActiveFile()?.path
                        setEditorTextSilently(viewModel.readActiveJavaCode())
                        switchToJavaMode()
                    }
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun confirmDeleteEntry(item: com.java2smali.ui.JavaSourceFileUi) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_file))
            .setMessage(getString(R.string.confirm_delete))
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                when (item.type) {
                    com.java2smali.ui.EntryType.FILE -> {
                        viewModel.deleteFile(item.path)
                        selectedFilePath = viewModel.getActiveFile()?.path
                        setEditorTextSilently(viewModel.readActiveJavaCode())
                        switchToJavaMode()
                    }

                    com.java2smali.ui.EntryType.FOLDER -> {
                        viewModel.deleteFolder(item.path)
                        selectedFilePath = viewModel.getActiveFile()?.path
                        setEditorTextSilently(viewModel.readActiveJavaCode())
                        switchToJavaMode()
                    }

                    com.java2smali.ui.EntryType.WORKSPACE -> {
                        viewModel.deleteWorkspace(item.path)
                        val now = viewModel.uiState.value.activeWorkspaceName
                        binding.toolbar.subtitle = getString(R.string.workspace_now, now)
                        selectedFilePath = viewModel.getActiveFile()?.path
                        setEditorTextSilently(viewModel.readActiveJavaCode())
                        switchToJavaMode()
                    }
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showMoveOrCopyDialog(item: com.java2smali.ui.JavaSourceFileUi, copy: Boolean) {
        if (item.type == com.java2smali.ui.EntryType.WORKSPACE) {
            showError(getString(R.string.workspace_move_not_supported))
            return
        }
        val folders = viewModel.listFolderRelativePaths()
        val labels = folders.map {
            if (it.isEmpty()) getString(R.string.root_folder) else it
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (copy) getString(R.string.copy_entry) else getString(R.string.move_entry))
            .setItems(labels.toTypedArray()) { _, which ->
                val targetRel = folders[which]
                val target = viewModel.moveOrCopyEntry(item.path, targetRel, copy)
                if (item.type == com.java2smali.ui.EntryType.FILE && !copy) {
                    selectedFilePath = target
                    setEditorTextSilently(viewModel.readActiveJavaCode())
                    switchToJavaMode()
                }
            }
            .show()
    }

    private fun selectedFolderForNewEntries(): String {
        val path = selectedFilePath ?: return ""
        return viewModel.relativeFolderFor(path)
    }

    private fun configureIndentActionLongPress() {
        val item = binding.toolbar.menu.findItem(R.id.action_indent) ?: return
        val button = AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_indent)
            background = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.list_selector_background)
            contentDescription = getString(R.string.indent)
            val size = (40 * resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
            setOnClickListener { indentSelection() }
            setOnLongClickListener {
                unindentSelection()
                true
            }
        }
        item.actionView = button
    }

    private fun runConversion() {
        if (isSmaliMode) return
        var javaCode = editor.text.toString()
        javaCode = javaCode.replace("\t", "    ")
        if (javaCode.isBlank()) {
            showError("请输入 Java 代码")
            return
        }
        viewModel.saveJavaCode(javaCode)
        if (!isSmaliMode) {
            setEditorTextSilently(javaCode)
        }
        lifecycleScope.launch {
            viewModel.compileToSmali(javaCode)
        }
    }

    private fun switchToJavaMode() {
        isSmaliMode = false
        updateConvertButtonState()
        binding.chipJava.isChecked = true
        binding.chipSmali.isChecked = false
        binding.toolbar.subtitle = getString(R.string.java_mode)
        editor.setEditorLanguage(createJavaLanguage())
        editor.setEditable(true)
        setEditorTextSilently(viewModel.readActiveJavaCode())
    }

    private fun switchToSmaliMode(smaliCode: String) {
        isSmaliMode = true
        updateConvertButtonState()
        binding.chipJava.isChecked = false
        binding.chipSmali.isChecked = true
        binding.toolbar.subtitle = getString(R.string.smali_mode)
        editor.setEditorLanguage(createJavaLanguage())
        editor.setEditable(false)
        setEditorTextSilently(smaliCode)
    }

    private fun formatCurrentCode() {
        val formatted = viewModel.formatCode(editor.text.toString().replace("\t", "    "), isSmaliMode)
        setEditorTextSilently(formatted)
        if (!isSmaliMode) {
            viewModel.saveJavaCode(formatted)
        }
        showToast("代码已格式化")
    }

    private fun indentSelection() {
        if (isSmaliMode) return
        val content = editor.text
        val c = editor.cursor
        content.beginBatchEdit()
        if (!c.isSelected) {
            content.insert(c.leftLine, c.leftColumn, "    ")
        } else {
            val startLine = minOf(c.leftLine, c.rightLine)
            val endLine = maxOf(c.leftLine, c.rightLine)
            for (line in startLine..endLine) {
                content.insert(line, 0, "    ")
            }
        }
        content.endBatchEdit()
        viewModel.saveJavaCode(content.toString())
    }

    private fun unindentSelection() {
        if (isSmaliMode) return
        val content = editor.text
        val c = editor.cursor
        val startLine = minOf(c.leftLine, c.rightLine)
        val endLine = maxOf(c.leftLine, c.rightLine)
        content.beginBatchEdit()
        for (line in startLine..endLine) {
            val lineText = content.getLineString(line)
            val removeCount = when {
                lineText.startsWith("    ") -> 4
                lineText.startsWith("\t") -> 1
                lineText.startsWith(" ") -> lineText.takeWhile { it == ' ' }.length.coerceAtMost(4)
                else -> 0
            }
            if (removeCount > 0) {
                content.delete(line, 0, line, removeCount)
            }
        }
        content.endBatchEdit()
        viewModel.saveJavaCode(content.toString())
    }

    private fun toggleCommentSelection() {
        if (isSmaliMode) return
        val content = editor.text
        val c = editor.cursor
        val startLine = minOf(c.leftLine, c.rightLine)
        val endLine = maxOf(c.leftLine, c.rightLine)

        var allCommented = true
        for (line in startLine..endLine) {
            val text = content.getLineString(line)
            if (text.isBlank()) continue
            val trimmed = text.trimStart()
            if (!trimmed.startsWith("//")) {
                allCommented = false
                break
            }
        }

        content.beginBatchEdit()
        for (line in startLine..endLine) {
            val text = content.getLineString(line)
            if (text.isBlank()) continue
            val indent = text.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (allCommented) {
                if (text.substring(indent).startsWith("//")) {
                    content.delete(line, indent, line, indent + 2)
                }
            } else {
                content.insert(line, indent, "//")
            }
        }
        content.endBatchEdit()
        viewModel.saveJavaCode(content.toString())
    }

    private fun toggleWordWrap() {
        isWordWrapEnabled = !isWordWrapEnabled
        editor.setWordwrap(isWordWrapEnabled)
        uiPrefs.edit().putBoolean(KEY_WORD_WRAP, isWordWrapEnabled).apply()
        updateWrapMenuItem()
    }

    private fun exportDex() {
        exportDexLauncher.launch("classes.dex")
    }

    private fun exportDexToUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val dexFile = viewModel.getDexFile()
                    ?: throw IllegalStateException(getString(R.string.no_dex))
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(dexFile).use { input ->
                        input.copyTo(output)
                    }
                }
                showToast(getString(R.string.export_success))
            } catch (e: Exception) {
                showError("导出失败: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun updateCursorStats() {
        val c = editor.cursor
        val line = c.leftLine + 1
        val col = c.leftColumn + 1
        if (c.isSelected) {
            val chars = kotlin.math.abs(c.right - c.left)
            val lines = c.rightLine - c.leftLine + 1
            binding.txtCursorPos.text = "$line:$col  [${lines}L/${chars}C]"
        } else {
            binding.txtCursorPos.text = "$line:$col"
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("name", text))
        showToast(getString(R.string.copy_success))
    }

    private fun createJavaLanguage(): JavaLanguage {
        return DependencyAwareJavaLanguage { viewModel.listDependencyClasses() }
    }

    private fun applyFileFilter(activePath: String? = selectedFilePath) {
        val q = fileFilterQuery
        val filtered = if (q.isBlank()) {
            allFileItems
        } else {
            allFileItems.filter {
                it.type == com.java2smali.ui.EntryType.WORKSPACE || it.name.contains(q, ignoreCase = true)
            }
        }
        fileAdapter.submit(filtered, activePath)
    }

    private fun showSearchReplaceDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_search_replace, null)
        val editSearch = dialogView.findViewById<EditText>(R.id.editSearchQuery)
        val editReplace = dialogView.findViewById<EditText>(R.id.editReplaceText)
        val checkIgnore = dialogView.findViewById<CheckBox>(R.id.checkIgnoreCase)
        val checkWorkspace = dialogView.findViewById<CheckBox>(R.id.checkWorkspace)
        checkIgnore.isChecked = uiPrefs.getBoolean(KEY_SEARCH_IGNORE_CASE, true)
        checkWorkspace.isChecked = uiPrefs.getBoolean(KEY_SEARCH_WORKSPACE, false)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.search_replace))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.search_query)) { _, _ ->
                val query = editSearch.text?.toString().orEmpty()
                val ignoreCase = checkIgnore.isChecked
                val workspace = checkWorkspace.isChecked
                uiPrefs.edit()
                    .putBoolean(KEY_SEARCH_IGNORE_CASE, ignoreCase)
                    .putBoolean(KEY_SEARCH_WORKSPACE, workspace)
                    .apply()
                val count = viewModel.searchOccurrences(query, ignoreCase, workspace)
                highlightMatches(query, ignoreCase)
                showToast(getString(R.string.search_result, count))
            }
            .setNeutralButton(getString(R.string.replace_with)) { _, _ ->
                val query = editSearch.text?.toString().orEmpty()
                val rep = editReplace.text?.toString().orEmpty()
                val ignoreCase = checkIgnore.isChecked
                val workspace = checkWorkspace.isChecked
                uiPrefs.edit()
                    .putBoolean(KEY_SEARCH_IGNORE_CASE, ignoreCase)
                    .putBoolean(KEY_SEARCH_WORKSPACE, workspace)
                    .apply()
                val count = viewModel.replaceOccurrences(query, rep, ignoreCase, workspace)
                if (!isSmaliMode) {
                    setEditorTextSilently(viewModel.readActiveJavaCode())
                }
                highlightMatches(query, ignoreCase)
                showToast(getString(R.string.replace_result, count))
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isSidebarCollapsed && ev.action == MotionEvent.ACTION_DOWN) {
            val rect = Rect()
            binding.fileSidebar.getGlobalVisibleRect(rect)
            if (!rect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                toggleSidebar()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        editor.release()
    }

    private fun collapseSidebarImmediately() {
        val panel = binding.fileSidebar
        val lp = panel.layoutParams
        lp.width = 0
        panel.layoutParams = lp
        panel.visibility = View.GONE
        panel.alpha = 0f
        isSidebarCollapsed = true
    }

    private fun updateWrapMenuItem() {
        val item = binding.toolbar.menu.findItem(R.id.action_wrap) ?: return
        item.isCheckable = true
        item.isChecked = isWordWrapEnabled
        item.title = getString(R.string.wrap_toggle)
    }

    private fun highlightMatches(query: String, ignoreCase: Boolean) {
        if (query.isBlank()) {
            editor.searcher.stopSearch()
            return
        }
        editor.searcher.search(query, EditorSearcher.SearchOptions(ignoreCase, false))
    }

    private fun setEditorTextSilently(text: String) {
        suppressHistoryRecording = true
        try {
            editor.setText(text)
        } finally {
            suppressHistoryRecording = false
            updateCursorStats()
        }
    }

    private fun updateConvertButtonState() {
        binding.btnConvert.isEnabled = !isSmaliMode
        binding.btnConvert.alpha = if (isSmaliMode) 0.4f else 1f
    }
}
