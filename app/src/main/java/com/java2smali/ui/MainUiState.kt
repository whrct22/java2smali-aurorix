package com.java2smali.ui

data class JavaSourceFileUi(
    val path: String,
    val name: String,
    val type: EntryType = EntryType.FILE,
    val depth: Int = 0
)

enum class EntryType {
    WORKSPACE,
    FOLDER,
    FILE
}

data class MainUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val smaliResult: String? = null,
    val activeSmaliText: String? = null,
    val files: List<JavaSourceFileUi> = emptyList(),
    val activeFilePath: String? = null,
    val dependencies: List<String> = emptyList(),
    val activeWorkspaceName: String = "default"
)
