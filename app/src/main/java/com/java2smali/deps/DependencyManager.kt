package com.java2smali.deps

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.java2smali.workspace.WorkspaceManager
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.jar.JarFile

/**
 * 依赖管理器
 * 负责导入、浏览、删除第三方依赖（JAR/DEX），
 * 并为编译和 DEX 合并提供 classpath 和 DEX 文件列表。
 */
class DependencyManager(
    private val workspace: WorkspaceManager
) {

    companion object {
        private const val TAG = "DependencyManager"

        // 内置依赖文件名前缀标识
        private const val BUILTIN_PREFIX = "__builtin__"

        // DEX 文件魔数
        private val DEX_MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

        // 最低 API 级别
        private const val MIN_API_LEVEL = 26
    }

    /**
     * 依赖文件预览信息
     */
    data class DependencyFilePreview(
        val fileName: String,
        val isBuiltin: Boolean,
        val classCount: Int = 0
    )

    /**
     * 依赖导入预检结果
     */
    data class PreflightResult(
        val fileName: String,
        val isDex: Boolean,
        val dexParsable: Boolean,
        val d8StandaloneOk: Boolean,
        val warningMessage: String?
    )

    // 依赖目录
    private val depsDir: File
        get() = workspace.depsDir

    /**
     * 列出所有已导入的依赖名称
     */
    fun listDependencyNames(): List<String> {
        val dir = depsDir
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter {
                it.isFile &&
                        (it.name.endsWith(".jar") || it.name.endsWith(".dex")) &&
                        !it.name.endsWith(".converted.dex") &&
                        !it.name.startsWith(".")
            }
            .map { it.name }
            .sorted()
    }

    /**
     * 列出所有依赖文件的预览信息
     */
    fun listDependencyFilePreviews(): List<DependencyFilePreview> {
        val dir = depsDir
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter {
                it.isFile &&
                        (it.name.endsWith(".jar") || it.name.endsWith(".dex")) &&
                        !it.name.endsWith(".converted.dex") &&
                        !it.name.startsWith(".")
            }
            .map { file ->
                DependencyFilePreview(
                    fileName = file.name,
                    isBuiltin = file.name.startsWith(BUILTIN_PREFIX),
                    classCount = countClassesInFile(file)
                )
            }
            .sortedBy { it.fileName.lowercase() }
    }

    /**
     * 列出所有依赖中可用的类（全限定名）
     */
    fun listAvailableClasses(): List<String> {
        val classes = mutableSetOf<String>()
        val dir = depsDir
        if (!dir.exists()) return emptyList()

        for (file in dir.listFiles() ?: emptyArray()) {
            if (!file.isFile) continue
            when {
                file.name.endsWith(".jar") -> classes.addAll(listClassesInJar(file))
                file.name.endsWith(".dex") -> classes.addAll(listClassesInDex(file))
            }
        }
        return classes.sorted()
    }

    /**
     * 列出需要生成 stub 的类名（用于编译时类型解析）
     */
    fun listStubClasses(): List<String> {
        return listAvailableClasses()
    }

    /**
     * 列出指定依赖文件中的所有类
     */
    fun listClassesInFile(fileName: String): List<String> {
        val file = File(depsDir, fileName)
        if (!file.exists()) return emptyList()
        return when {
            file.name.endsWith(".jar") -> listClassesInJar(file).sorted()
            file.name.endsWith(".dex") -> listClassesInDex(file).sorted()
            else -> emptyList()
        }
    }

    /**
     * 列出指定依赖文件中某个类的成员（字段和方法）
     */
    fun listMembersInClass(fileName: String, className: String): List<String> {
        val file = File(depsDir, fileName)
        if (!file.exists()) return emptyList()
        return when {
            file.name.endsWith(".jar") -> listMembersInJarClass(file, className)
            file.name.endsWith(".dex") -> listMembersInDexClass(file, className)
            else -> emptyList()
        }
    }

    /**
     * 获取编译时 classpath 的 JAR 文件列表
     */
    fun classpathJars(): List<File> {
        val dir = depsDir
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".jar") }
            .toList()
    }

    /**
     * 获取编译时需要的 DEX 文件列表（用于 DexClassLoader）
     */
    fun dependencyDexFilesForCompile(): List<File> {
        val dir = depsDir
        if (!dir.exists()) return emptyList()
        return (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".dex") }
            .toList()
    }

    /**
     * 获取需要合并到输出 DEX 中的依赖 DEX 文件
     * 只返回被项目实际 import 引用到的依赖
     */
    fun dependencyDexFilesForMerge(
        importedClasses: Set<String>,
        importedPackages: Set<String>
    ): List<File> {
        if (importedClasses.isEmpty() && importedPackages.isEmpty()) return emptyList()

        val dir = depsDir
        if (!dir.exists()) return emptyList()

        val result = mutableListOf<File>()
        for (file in dir.listFiles() ?: emptyArray()) {
            if (!file.isFile) continue
            if (!file.name.endsWith(".dex") && !file.name.endsWith(".jar")) continue

            // 检查该依赖文件中是否包含被引用的类
            val classes = when {
                file.name.endsWith(".jar") -> listClassesInJar(file)
                file.name.endsWith(".dex") -> listClassesInDex(file)
                else -> emptyList()
            }

            val isReferenced = classes.any { fqcn ->
                importedClasses.contains(fqcn) ||
                        importedPackages.any { pkg -> fqcn.startsWith("$pkg.") }
            }

            if (isReferenced) {
                // 如果是 JAR，需要先转换为 DEX
                val dexFile = ensureDexForFile(file)
                if (dexFile != null) {
                    result.add(dexFile)
                }
            }
        }
        return result
    }

    /**
     * 导入依赖文件（JAR 或 DEX）
     */
    fun importDependency(context: Context, uri: Uri): File {
        return importDependency(context, uri, true)
    }

    /**
     * 导入依赖文件（JAR 或 DEX），可指定是否参与合并
     */
    fun importDependency(context: Context, uri: Uri, includeInMerge: Boolean): File {
        val displayName = resolveFileName(context, uri)
        val targetName = uniqueDepFileName(displayName)
        val target = File(depsDir, targetName)
        depsDir.mkdirs()

        // 复制文件到依赖目录
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
            }
        } ?: throw IllegalStateException("无法读取依赖文件")

        Log.d(TAG, "依赖已导入: ${target.name}")
        return target
    }

    /**
     * 依赖导入预检
     * 检查文件是否为有效的 DEX 或 JAR
     */
    fun preflightDependency(context: Context, uri: Uri): PreflightResult {
        val displayName = resolveFileName(context, uri)

        // 读取文件头部判断类型
        val headerBytes = ByteArray(8)
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.read(headerBytes)
        }

        val isDex = headerBytes.sliceArray(0..3).contentEquals(DEX_MAGIC)

        if (!isDex) {
            // JAR 文件不需要额外预检
            return PreflightResult(
                fileName = displayName,
                isDex = false,
                dexParsable = false,
                d8StandaloneOk = true,
                warningMessage = null
            )
        }

        // DEX 文件预检
        var dexParsable = false
        var d8Ok = false
        var warning: String? = null

        // 尝试解析 DEX
        val tempFile = File(depsDir, ".preflight_temp.dex")
        try {
            depsDir.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            // 测试 dexlib2 解析
            try {
                DexFileFactory.loadDexFile(tempFile, Opcodes.getDefault())
                dexParsable = true
            } catch (e: Exception) {
                warning = "DEX 解析失败: ${e.message}"
            }

            // 测试 D8 是否能处理
            try {
                val testDir = File(depsDir, ".preflight_d8_test")
                testDir.mkdirs()
                val cmd = D8Command.builder()
                    .setMinApiLevel(MIN_API_LEVEL)
                    .setOutput(testDir.toPath(), OutputMode.DexIndexed)
                    .addProgramFiles(tempFile.toPath())
                    .build()
                D8.run(cmd)
                d8Ok = true
                testDir.deleteRecursively()
            } catch (e: Exception) {
                d8Ok = false
                val msg = "D8 预检失败: ${e.message}"
                warning = if (warning == null) msg else "$warning\n$msg"
            }
        } finally {
            tempFile.delete()
        }

        return PreflightResult(
            fileName = displayName,
            isDex = true,
            dexParsable = dexParsable,
            d8StandaloneOk = d8Ok,
            warningMessage = warning
        )
    }

    /**
     * 删除指定依赖文件
     */
    fun deleteDependencyFile(fileName: String) {
        val file = File(depsDir, fileName)
        if (file.exists()) {
            file.delete()
            // 同时删除对应的转换后 DEX 缓存
            val cachedDex = File(depsDir, "${fileName}.converted.dex")
            if (cachedDex.exists()) cachedDex.delete()
        }
        Log.d(TAG, "依赖已删除: $fileName")
    }

    /**
     * 从指定依赖文件中删除某个类
     * 注意：对于 DEX 文件，需要重新生成；对于 JAR 文件，需要移除对应 .class
     */
    fun deleteClassFromFile(fileName: String, className: String) {
        val file = File(depsDir, fileName)
        if (!file.exists()) return

        when {
            file.name.endsWith(".jar") -> removeClassFromJar(file, className)
            file.name.endsWith(".dex") -> removeClassFromDex(file, className)
        }
        // 清除转换缓存
        val cachedDex = File(depsDir, "${fileName}.converted.dex")
        if (cachedDex.exists()) cachedDex.delete()
    }

    /**
     * 清除所有非内置依赖
     */
    fun clearAllDependencies() {
        val dir = depsDir
        if (!dir.exists()) return
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isFile && !file.name.startsWith(BUILTIN_PREFIX)) {
                file.delete()
            }
        }
        Log.d(TAG, "所有依赖已清除")
    }

    /**
     * 判断是否为内置依赖
     */
    fun isBuiltinDependency(fileName: String): Boolean {
        return fileName.startsWith(BUILTIN_PREFIX)
    }

    // ==================== 私有方法 ====================

    /**
     * 确保文件有对应的 DEX 版本（JAR 需要转换）
     */
    private fun ensureDexForFile(file: File): File? {
        if (file.name.endsWith(".dex")) return file

        // JAR 文件需要转换为 DEX
        val cachedDex = File(depsDir, "${file.name}.converted.dex")
        if (cachedDex.exists() && cachedDex.lastModified() >= file.lastModified()) {
            return cachedDex
        }

        return try {
            val tempDir = File(depsDir, ".jar2dex_temp")
            tempDir.mkdirs()
            val cmd = D8Command.builder()
                .setMinApiLevel(MIN_API_LEVEL)
                .setOutput(tempDir.toPath(), OutputMode.DexIndexed)
                .addProgramFiles(file.toPath())
                .build()
            D8.run(cmd)

            val outputDex = File(tempDir, "classes.dex")
            if (outputDex.exists()) {
                outputDex.copyTo(cachedDex, overwrite = true)
                tempDir.deleteRecursively()
                cachedDex
            } else {
                tempDir.deleteRecursively()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "JAR 转 DEX 失败: ${file.name}", e)
            null
        }
    }

    /**
     * 列出 JAR 文件中的所有类（全限定名）
     */
    private fun listClassesInJar(file: File): List<String> {
        val classes = mutableListOf<String>()
        try {
            JarFile(file).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(".class") && !entry.name.contains("module-info")) {
                        val fqcn = entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')
                        classes.add(fqcn)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 JAR 失败: ${file.name}", e)
        }
        return classes
    }

    /**
     * 列出 DEX 文件中的所有类（全限定名）
     */
    private fun listClassesInDex(file: File): List<String> {
        val classes = mutableListOf<String>()
        try {
            val dexFile = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
            for (classDef in dexFile.classes) {
                // DEX 中类名格式为 Lcom/example/Foo; 需要转换
                val fqcn = classDef.type
                    .removePrefix("L")
                    .removeSuffix(";")
                    .replace('/', '.')
                classes.add(fqcn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 DEX 失败: ${file.name}", e)
        }
        return classes
    }

    /**
     * 列出 JAR 中某个类的成员
     */
    private fun listMembersInJarClass(file: File, className: String): List<String> {
        val members = mutableListOf<String>()
        try {
            val entryPath = className.replace('.', '/') + ".class"
            JarFile(file).use { jar ->
                val entry = jar.getEntry(entryPath) ?: return emptyList()
                jar.getInputStream(entry).use { input ->
                    val reader = ClassReader(input)
                    val node = ClassNode()
                    reader.accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG)

                    // 添加字段
                    for (field in node.fields ?: emptyList()) {
                        members.add("${field.name}: ${simplifyDescriptor(field.desc)}")
                    }

                    // 添加方法
                    for (method in node.methods ?: emptyList()) {
                        if (method.name == "<clinit>") continue
                        val params = parseMethodParams(method.desc)
                        val ret = parseReturnType(method.desc)
                        val display = if (method.name == "<init>") {
                            "${className.substringAfterLast('.')}($params)"
                        } else {
                            "${method.name}($params): $ret"
                        }
                        members.add(display)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取类成员失败: $className in ${file.name}", e)
        }
        return members
    }

    /**
     * 列出 DEX 中某个类的成员
     */
    private fun listMembersInDexClass(file: File, className: String): List<String> {
        val members = mutableListOf<String>()
        try {
            val dexFile = DexFileFactory.loadDexFile(file, Opcodes.getDefault())
            val targetType = "L${className.replace('.', '/')};"

            for (classDef in dexFile.classes) {
                if (classDef.type != targetType) continue

                // 字段
                for (field in classDef.fields) {
                    val fieldType = simplifyDexType(field.type)
                    members.add("${field.name}: $fieldType")
                }

                // 方法
                for (method in classDef.methods) {
                    if (method.name == "<clinit>") continue
                    val params = method.parameterTypes.joinToString(", ") { simplifyDexType(it) }
                    val ret = simplifyDexType(method.returnType)
                    val display = if (method.name == "<init>") {
                        "${className.substringAfterLast('.')}($params)"
                    } else {
                        "${method.name}($params): $ret"
                    }
                    members.add(display)
                }
                break
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取 DEX 类成员失败: $className in ${file.name}", e)
        }
        return members
    }

    /**
     * 从 JAR 中移除指定类
     */
    private fun removeClassFromJar(file: File, className: String) {
        val entryPath = className.replace('.', '/') + ".class"
        val tempFile = File(file.parentFile, "${file.name}.tmp")

        try {
            java.util.jar.JarInputStream(FileInputStream(file)).use { jis ->
                val manifest = jis.manifest
                java.util.jar.JarOutputStream(FileOutputStream(tempFile), manifest ?: java.util.jar.Manifest()).use { jos ->
                    var entry = jis.nextJarEntry
                    while (entry != null) {
                        // 跳过要删除的类及其内部类
                        val basePath = entryPath.removeSuffix(".class")
                        if (entry.name != entryPath &&
                            !entry.name.startsWith("$basePath\$")
                        ) {
                            jos.putNextEntry(java.util.jar.JarEntry(entry.name))
                            jis.copyTo(jos)
                            jos.closeEntry()
                        }
                        entry = jis.nextJarEntry
                    }
                }
            }
            // 替换原文件
            file.delete()
            tempFile.renameTo(file)
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "从 JAR 删除类失败: $className", e)
        }
    }

    /**
     * 从 DEX 中移除指定类（重新生成 DEX）
     */
    private fun removeClassFromDex(file: File, className: String) {
        try {
            val targetType = "L${className.replace('.', '/')};"
            val dexFile = DexFileFactory.loadDexFile(file, Opcodes.getDefault())

            // 过滤掉目标类及其内部类
            val baseType = targetType.removeSuffix(";")
            val remaining = dexFile.classes.filter { classDef ->
                classDef.type != targetType &&
                        !classDef.type.startsWith("$baseType\$")
            }

            if (remaining.size == dexFile.classes.size) return // 没有找到要删除的类

            // 使用 dexlib2 重新写入
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            val dexPool = org.jf.dexlib2.writer.pool.DexPool(Opcodes.getDefault())
            for (classDef in remaining) {
                dexPool.internClass(classDef)
            }
            dexPool.writeTo(FileOutputStream(tempFile))

            file.delete()
            tempFile.renameTo(file)
        } catch (e: Exception) {
            Log.w(TAG, "从 DEX 删除类失败: $className", e)
        }
    }

    /**
     * 统计文件中的类数量
     */
    private fun countClassesInFile(file: File): Int {
        return when {
            file.name.endsWith(".jar") -> listClassesInJar(file).size
            file.name.endsWith(".dex") -> listClassesInDex(file).size
            else -> 0
        }
    }

    /**
     * 解析文件名
     */
    private fun resolveFileName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment ?: "dependency"
        name = name.substringAfterLast('/')

        // 确保有正确的扩展名
        if (!name.endsWith(".jar") && !name.endsWith(".dex")) {
            // 尝试从 MIME 类型推断
            val mime = context.contentResolver.getType(uri)
            name += when {
                mime?.contains("java-archive") == true -> ".jar"
                else -> ".dex"
            }
        }
        return name
    }

    /**
     * 生成唯一的依赖文件名
     */
    private fun uniqueDepFileName(baseName: String): String {
        val nameWithoutExt = baseName.substringBeforeLast('.')
        val ext = baseName.substringAfterLast('.', "")
        var index = 0
        while (true) {
            val candidate = if (index == 0) baseName else "$nameWithoutExt($index).$ext"
            if (!File(depsDir, candidate).exists()) return candidate
            index++
        }
    }

    /**
     * 简化 JVM 类型描述符
     */
    private fun simplifyDescriptor(desc: String): String {
        return when {
            desc == "V" -> "void"
            desc == "Z" -> "boolean"
            desc == "B" -> "byte"
            desc == "C" -> "char"
            desc == "S" -> "short"
            desc == "I" -> "int"
            desc == "J" -> "long"
            desc == "F" -> "float"
            desc == "D" -> "double"
            desc.startsWith("[") -> "${simplifyDescriptor(desc.substring(1))}[]"
            desc.startsWith("L") && desc.endsWith(";") -> {
                desc.removePrefix("L").removeSuffix(";").replace('/', '.').substringAfterLast('.')
            }
            else -> desc
        }
    }

    /**
     * 简化 DEX 类型描述符
     */
    private fun simplifyDexType(type: String): String {
        return simplifyDescriptor(type)
    }

    /**
     * 解析方法参数列表
     */
    private fun parseMethodParams(desc: String): String {
        val paramsPart = desc.substringAfter('(').substringBefore(')')
        if (paramsPart.isEmpty()) return ""

        val params = mutableListOf<String>()
        var i = 0
        while (i < paramsPart.length) {
            when (paramsPart[i]) {
                'V', 'Z', 'B', 'C', 'S', 'I', 'J', 'F', 'D' -> {
                    params.add(simplifyDescriptor(paramsPart[i].toString()))
                    i++
                }
                '[' -> {
                    var arrayPrefix = ""
                    while (i < paramsPart.length && paramsPart[i] == '[') {
                        arrayPrefix += "["
                        i++
                    }
                    if (i < paramsPart.length) {
                        if (paramsPart[i] == 'L') {
                            val end = paramsPart.indexOf(';', i)
                            if (end >= 0) {
                                params.add(simplifyDescriptor(arrayPrefix + paramsPart.substring(i, end + 1)))
                                i = end + 1
                            } else {
                                i++
                            }
                        } else {
                            params.add(simplifyDescriptor(arrayPrefix + paramsPart[i]))
                            i++
                        }
                    }
                }
                'L' -> {
                    val end = paramsPart.indexOf(';', i)
                    if (end >= 0) {
                        params.add(simplifyDescriptor(paramsPart.substring(i, end + 1)))
                        i = end + 1
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return params.joinToString(", ")
    }

    /**
     * 解析方法返回类型
     */
    private fun parseReturnType(desc: String): String {
        val retPart = desc.substringAfter(')')
        return simplifyDescriptor(retPart)
    }
}
