package com.java2smali.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class WorkspaceManager(context: Context) {

    private val rootParentDir: File = File(context.filesDir, "workspaces")
    private val activeWorkspaceFlag: File = File(rootParentDir, ".active_workspace")
    private var activeWorkspace: String = loadActiveWorkspaceName()

    val root: File
        get() {
            val dir = File(rootParentDir, activeWorkspace)
            dir.mkdirs()
            return dir
        }

    val srcDir: File
        get() {
            val dir = File(root, "src")
            dir.mkdirs()
            return dir
        }

    val classesDir: File
        get() {
            val dir = File(root, "classes")
            dir.mkdirs()
            return dir
        }

    val dexDir: File
        get() {
            val dir = File(root, "dex")
            dir.mkdirs()
            return dir
        }

    val smaliDir: File
        get() {
            val dir = File(root, "smali")
            dir.mkdirs()
            return dir
        }

    val depsDir: File
        get() {
            val dir = File(root, "deps")
            dir.mkdirs()
            return dir
        }

    val historyDir: File
        get() {
            val dir = File(root, "history")
            dir.mkdirs()
            return dir
        }

    val dexFile: File
        get() = File(dexDir, "classes.dex")

    val mergedDexFile: File
        get() = File(dexDir, "classes-all.dex")

    fun currentWorkspaceName(): String = activeWorkspace

    fun listWorkspaceNames(): List<String> {
        rootParentDir.mkdirs()
        val names = (rootParentDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .map { it.name }
            .sortedBy { it.lowercase() }
        if (names.isEmpty()) {
            ensureWorkspaceExists("default")
            return listOf("default")
        }
        return names
    }

    fun createWorkspace(name: String): String {
        val safe = sanitizeWorkspaceName(name).ifEmpty { "workspace" }
        val finalName = uniqueWorkspaceName(safe)
        ensureWorkspaceExists(finalName)
        return finalName
    }

    fun switchWorkspace(name: String) {
        val safe = sanitizeWorkspaceName(name)
        if (safe.isEmpty()) throw IllegalStateException("工作区名称无效")
        ensureWorkspaceExists(safe)
        activeWorkspace = safe
        saveActiveWorkspaceName(safe)
    }

    fun renameWorkspace(oldName: String, newName: String): String {
        val oldSafe = sanitizeWorkspaceName(oldName)
        val newSafeBase = sanitizeWorkspaceName(newName).ifEmpty { "workspace" }
        if (oldSafe.isEmpty()) throw IllegalStateException("工作区不存在")
        val oldDir = File(rootParentDir, oldSafe)
        if (!oldDir.exists() || !oldDir.isDirectory) throw IllegalStateException("工作区不存在")
        val newSafe = if (oldSafe.equals(newSafeBase, ignoreCase = true)) oldSafe else uniqueWorkspaceName(newSafeBase)
        val newDir = File(rootParentDir, newSafe)
        if (oldDir.absolutePath != newDir.absolutePath && !oldDir.renameTo(newDir)) {
            throw IllegalStateException("重命名工作区失败")
        }
        if (activeWorkspace == oldSafe) {
            activeWorkspace = newSafe
            saveActiveWorkspaceName(newSafe)
        }
        return newSafe
    }

    fun deleteWorkspace(name: String) {
        val safe = sanitizeWorkspaceName(name)
        if (safe.isEmpty()) return
        val dir = File(rootParentDir, safe)
        deleteRecursive(dir)

        val remaining = listWorkspaceNames()
        if (remaining.isEmpty()) {
            activeWorkspace = "default"
            ensureWorkspaceExists(activeWorkspace)
        } else if (activeWorkspace == safe) {
            activeWorkspace = remaining.first()
        }
        saveActiveWorkspaceName(activeWorkspace)
    }

    fun clearAllWorkspaces() {
        val dirs = (rootParentDir.listFiles() ?: emptyArray()).filter { it.isDirectory }
        for (dir in dirs) {
            deleteRecursive(dir)
        }
        activeWorkspace = "default"
        ensureWorkspaceExists(activeWorkspace)
        saveActiveWorkspaceName(activeWorkspace)
    }

    fun listJavaFiles(): List<File> {
        val out = mutableListOf<File>()
        collectJavaFiles(srcDir, out)
        return out.sortedBy { it.absolutePath }
    }

    fun listSourceFolders(): List<File> {
        val out = mutableListOf<File>()
        collectSourceFolders(srcDir, out)
        return out.sortedBy { it.absolutePath }
    }

    fun ensureDefaultJavaFile(): File {
        val existing = listJavaFiles()
        if (existing.isNotEmpty()) {
            return existing.first()
        }
        val file = uniqueJavaFile("Aurorix", null)
        writeText(
            file,
            """
            public class Aurorix {
                public static void Aurorix(String[] args) {
                    System.out.println("Hello, Aurorix");
                }
            }
            """.trimIndent()
        )
        return file
    }

    fun createFolder(relativePath: String): File {
        val normalized = normalizeRelativeSourcePath(relativePath)
        if (normalized.isEmpty()) throw IllegalStateException("文件夹名称无效")
        val folder = File(srcDir, normalized)
        folder.mkdirs()
        return folder
    }

    fun createJavaFile(baseName: String): File = createJavaFile(baseName, null)

    fun createJavaFile(baseName: String, parentRelativeFolder: String?): File {
        val safe = sanitizeName(baseName).ifEmpty { "NewFile" }
        val file = uniqueJavaFile(safe, parentRelativeFolder)
        writeText(file, "public class ${file.nameWithoutExtension} {\n}\n")
        return file
    }

    fun importJavaFile(resolver: ContentResolver, uri: Uri): File = importJavaFile(resolver, uri, null)

    fun importJavaFile(resolver: ContentResolver, uri: Uri, parentRelativeFolder: String?): File {
        val displayName = (uri.lastPathSegment ?: "Imported.java").substringAfterLast('/')
        val base = sanitizeName(displayName.removeSuffix(".java")).ifEmpty { "Imported" }
        val target = uniqueJavaFile(base, parentRelativeFolder)
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取 Java 文件")
        return target
    }

    fun renameJavaFile(file: File, newBaseName: String): File {
        if (!file.exists()) throw IllegalStateException("文件不存在")
        val normalized = newBaseName.trim().removeSuffix(".java")
        val safe = sanitizeName(normalized).ifEmpty { "Renamed" }
        val target = File(file.parentFile, "$safe.java")
        val finalTarget = if (target.exists() && target.absolutePath != file.absolutePath) {
            uniqueJavaFile(safe, relativeFolderOf(file.parentFile))
        } else {
            target
        }
        if (!file.renameTo(finalTarget)) {
            throw IllegalStateException("重命名失败")
        }
        return finalTarget
    }

    fun renameFolder(folder: File, newName: String): File {
        if (!folder.exists() || !folder.isDirectory) throw IllegalStateException("文件夹不存在")
        requireSourcePath(folder)
        val safe = sanitizeName(newName).ifEmpty { "folder" }
        val target = File(folder.parentFile, safe)
        var finalTarget = target
        var index = 1
        while (finalTarget.exists() && finalTarget.absolutePath != folder.absolutePath) {
            finalTarget = File(folder.parentFile, safe + index)
            index++
        }
        if (!folder.renameTo(finalTarget)) {
            throw IllegalStateException("重命名文件夹失败")
        }
        return finalTarget
    }

    fun deleteJavaFile(file: File) {
        if (!file.exists()) return
        file.delete()
    }

    fun deleteFolder(folder: File) {
        if (!folder.exists() || !folder.isDirectory) return
        requireSourcePath(folder)
        deleteRecursive(folder)
    }

    fun moveEntryToFolder(source: File, targetFolderRelativePath: String, copy: Boolean): File {
        if (!source.exists()) throw IllegalStateException("源路径不存在")
        requireSourcePath(source)
        val targetFolder = if (targetFolderRelativePath.isBlank()) {
            srcDir
        } else {
            val normalized = normalizeRelativeSourcePath(targetFolderRelativePath)
            File(srcDir, normalized)
        }
        targetFolder.mkdirs()
        requireSourcePath(targetFolder)

        var target = File(targetFolder, source.name)
        var idx = 1
        while (target.exists()) {
            val base = source.nameWithoutExtension
            val ext = source.extension
            target = if (ext.isEmpty()) {
                File(targetFolder, "$base$idx")
            } else {
                File(targetFolder, "$base$idx.$ext")
            }
            idx++
        }

        if (source.isDirectory) {
            val sourceCanonical = source.canonicalFile
            val targetCanonical = target.canonicalFile
            if (targetCanonical.path.startsWith(sourceCanonical.path + File.separator)) {
                throw IllegalStateException("目标目录不能位于源目录内部")
            }
        }

        if (copy) {
            copyRecursively(source, target)
            return target
        }

        if (!source.renameTo(target)) {
            copyRecursively(source, target)
            deleteRecursive(source)
        }
        return target
    }

    fun readText(file: File): String {
        FileInputStream(file).use { fis ->
            return fis.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    fun writeText(file: File, content: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
        }
    }

    fun javaSourceFileFor(javaCode: String): File {
        val packageName = extractPackageName(javaCode)
        val typeName = extractTypeName(javaCode)

        var base = srcDir
        if (packageName.isNotEmpty()) {
            base = File(base, packageName.replace('.', File.separatorChar))
            base.mkdirs()
        }
        return File(base, "$typeName.java")
    }

    fun clearGenerated() {
        deleteRecursive(classesDir)
        deleteRecursive(dexDir)
        deleteRecursive(smaliDir)
        classesDir.mkdirs()
        dexDir.mkdirs()
        smaliDir.mkdirs()
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
        return regex.find(code)?.groupValues?.getOrNull(1) ?: "Aurorix"
    }

    private fun collectJavaFiles(dir: File, out: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectJavaFiles(file, out)
            } else if (file.name.endsWith(".java")) {
                out.add(file)
            }
        }
    }

    private fun collectSourceFolders(dir: File, out: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                out.add(file)
                collectSourceFolders(file, out)
            }
        }
    }

    private fun uniqueJavaFile(baseName: String, parentRelativeFolder: String?): File {
        val baseDir = resolveSourceDir(parentRelativeFolder)
        var index = 0
        while (true) {
            val suffix = if (index == 0) "" else index.toString()
            val file = File(baseDir, "$baseName$suffix.java")
            if (!file.exists()) {
                return file
            }
            index++
        }
    }

    private fun resolveSourceDir(parentRelativeFolder: String?): File {
        if (parentRelativeFolder.isNullOrBlank()) {
            return srcDir
        }
        val normalized = normalizeRelativeSourcePath(parentRelativeFolder)
        val dir = File(srcDir, normalized)
        dir.mkdirs()
        return dir
    }

    private fun relativeFolderOf(folder: File?): String? {
        if (folder == null) return null
        return try {
            val rel = folder.relativeTo(srcDir).path.replace('\\', '/')
            if (rel == ".") "" else rel
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun normalizeRelativeSourcePath(path: String): String {
        val cleaned = path.replace('\\', '/').trim('/').trim()
        if (cleaned.isEmpty()) return ""
        val parts = cleaned.split('/').map { sanitizeName(it) }.filter { it.isNotEmpty() }
        return parts.joinToString("/")
    }

    private fun sanitizeWorkspaceName(name: String): String {
        return name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
    }

    private fun uniqueWorkspaceName(base: String): String {
        var idx = 0
        while (true) {
            val candidate = if (idx == 0) base else base + idx
            if (!File(rootParentDir, candidate).exists()) {
                return candidate
            }
            idx++
        }
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9_]"), "")
    }

    private fun ensureWorkspaceExists(name: String) {
        val dir = File(rootParentDir, name)
        dir.mkdirs()
        File(dir, "src").mkdirs()
        File(dir, "classes").mkdirs()
        File(dir, "dex").mkdirs()
        File(dir, "smali").mkdirs()
        File(dir, "deps").mkdirs()
        File(dir, "history").mkdirs()
    }

    private fun loadActiveWorkspaceName(): String {
        rootParentDir.mkdirs()
        val fromFile = if (activeWorkspaceFlag.exists()) {
            activeWorkspaceFlag.readText().trim()
        } else {
            ""
        }
        val safe = sanitizeWorkspaceName(fromFile)
        val chosen = if (safe.isNotEmpty()) safe else "default"
        ensureWorkspaceExists(chosen)
        saveActiveWorkspaceName(chosen)
        return chosen
    }

    private fun saveActiveWorkspaceName(name: String) {
        activeWorkspaceFlag.writeText(name)
    }

    private fun requireSourcePath(file: File) {
        val srcCanonical = srcDir.canonicalFile
        val fileCanonical = file.canonicalFile
        if (!fileCanonical.path.startsWith(srcCanonical.path)) {
            throw IllegalStateException("路径不在当前工作区源码目录")
        }
    }

    private fun copyRecursively(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            val children = source.listFiles() ?: return
            for (child in children) {
                copyRecursively(child, File(target, child.name))
            }
            return
        }
        target.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    private fun deleteRecursive(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursive(it) }
        }
        file.delete()
    }
}
