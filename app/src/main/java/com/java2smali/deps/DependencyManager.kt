package com.java2smali.deps

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.java2smali.workspace.WorkspaceManager
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes as AsmOpcodes
import org.objectweb.asm.Type
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.writer.io.FileDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.jf.dexlib2.AccessFlags
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.LinkedHashSet
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages external dependencies (JAR/AAR files).
 * Handles importing, extracting classes.jar from AAR, and providing classpath.
 */
class DependencyManager(private val workspace: WorkspaceManager) {
    private val javaRuntimeClassCache: List<String> by lazy { loadJavaRuntimeClasses() }

    init {
        val legacy = File(workspace.depsDir, ".merge_policy.properties")
        if (legacy.exists()) {
            legacy.delete()
        }
    }

    data class DependencyFilePreview(
        val fileName: String,
        val type: String,
        val classCount: Int,
        val includeInMerge: Boolean,
        val isBuiltin: Boolean
    )

    data class PreflightResult(
        val fileName: String,
        val type: String,
        val isDex: Boolean,
        val dexParsable: Boolean,
        val d8StandaloneOk: Boolean,
        val warningMessage: String?,
        val recommendedIncludeInMerge: Boolean
    )

    fun importDependency(context: Context, uri: Uri, includeForMergeIgnored: Boolean = true): File {
        val name = extractFileName(uri)
        val lower = name.lowercase(Locale.US)

        if (!lower.endsWith(".jar") && !lower.endsWith(".aar") && !lower.endsWith(".dex")) {
            throw IOException("仅支持 .jar / .aar / .dex 文件")
        }
        
        val rawFile = File(workspace.depsDir, name)
        copyUriToFile(context.contentResolver, uri, rawFile)

        val result = if (lower.endsWith(".aar")) {
            extractClassesJar(rawFile)
        } else {
            rawFile
        }

        return result
    }

    fun preflightDependency(context: Context, uri: Uri): PreflightResult {
        val name = extractFileName(uri)
        val lower = name.lowercase(Locale.US)
        val type = when {
            lower.endsWith(".dex") -> "dex"
            lower.endsWith(".jar") -> "jar"
            lower.endsWith(".aar") -> "aar"
            else -> "file"
        }
        if (!lower.endsWith(".dex")) {
            return PreflightResult(
                fileName = name,
                type = type,
                isDex = false,
                dexParsable = true,
                d8StandaloneOk = true,
                warningMessage = null,
                recommendedIncludeInMerge = true
            )
        }

        val temp = File(workspace.root, "preflight-${System.currentTimeMillis()}-$name")
        copyUriToFile(context.contentResolver, uri, temp)

        var parsable = false
        var standaloneOk = false
        var warning: String? = null

        try {
            DexFileFactory.loadDexFile(temp, Opcodes.getDefault())
            parsable = true
        } catch (t: Throwable) {
            warning = "DEX 解析失败: ${t.message}"
        }

        if (parsable) {
            val outDir = File(workspace.root, "preflight_out")
            if (outDir.exists()) outDir.deleteRecursively()
            outDir.mkdirs()
            try {
                val cmd = D8Command.builder()
                    .setMinApiLevel(26)
                    .setOutput(outDir.toPath(), OutputMode.DexIndexed)
                    .addProgramFiles(temp.toPath())
                    .build()
                D8.run(cmd)
                standaloneOk = true
            } catch (t: Throwable) {
                warning = "D8 预检失败: ${t.message}"
            } finally {
                outDir.deleteRecursively()
            }
        }

        temp.delete()
        val recommend = false
        return PreflightResult(
            fileName = name,
            type = type,
            isDex = true,
            dexParsable = parsable,
            d8StandaloneOk = standaloneOk,
            warningMessage = warning,
            recommendedIncludeInMerge = recommend
        )
    }
    
    fun classpathJars(): List<File> {
        val result = mutableListOf<File>()
        val files = workspace.depsDir.listFiles() ?: return result
        
        for (file in files) {
            if (file.name.lowercase(Locale.US).endsWith(".jar")) {
                result.add(file)
            }
        }
        return result
    }

    fun dependencyDexFilesForCompile(): List<File> {
        val result = mutableListOf<File>()
        val files = workspace.depsDir.listFiles() ?: return result
        for (file in files) {
            if (file.name.lowercase(Locale.US).endsWith(".dex")) {
                result.add(file)
            }
        }
        return result
    }

    fun dependencyDexFilesForMerge(): List<File> {
        return emptyList()
    }
    
    fun buildClasspath(): String {
        return classpathJars().joinToString(File.pathSeparator) { it.absolutePath }
    }

    fun listDependencyNames(): List<String> {
        return (workspace.depsDir.listFiles() ?: emptyArray())
            .map { it.name }
            .sorted()
    }

    fun listDependencyFiles(): List<File> {
        return (workspace.depsDir.listFiles() ?: emptyArray())
            .filter { it.isFile }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    fun listAvailableClasses(): List<String> {
        return listCompletionClasses()
    }

    fun listCompletionClasses(): List<String> {
        val out = linkedSetOf<String>()
        out.addAll(javaRuntimeClassCache)
        for (file in listDependencyFiles()) {
            val lower = file.name.lowercase(Locale.US)
            when {
                lower.endsWith(".jar") -> collectJarClasses(file, out)
                lower.endsWith(".dex") -> collectDexClasses(file, out)
            }
        }
        return out.toList().sorted()
    }

    fun listStubClasses(): List<String> {
        val out = linkedSetOf<String>()
        for (file in listDependencyFiles()) {
            val lower = file.name.lowercase(Locale.US)
            when {
                lower.endsWith(".jar") -> collectJarClasses(file, out)
                lower.endsWith(".dex") -> collectDexClasses(file, out)
            }
        }
        return out.toList().sorted()
    }

    fun listDependencyFilePreviews(): List<DependencyFilePreview> {
        return listDependencyFiles().map { file ->
            val type = when {
                file.name.endsWith(".jar", true) -> "jar"
                file.name.endsWith(".dex", true) -> "dex"
                file.name.endsWith(".aar", true) -> "aar"
                else -> "file"
            }
            DependencyFilePreview(
                fileName = file.name,
                type = type,
                classCount = listClassesInFile(file.name).size,
                includeInMerge = false,
                isBuiltin = false
            )
        }
    }

    fun listClassesInFile(fileName: String): List<String> {
        val file = File(workspace.depsDir, fileName)
        if (!file.exists()) return emptyList()
        val out = linkedSetOf<String>()
        when {
            file.name.endsWith(".jar", true) -> collectJarClasses(file, out)
            file.name.endsWith(".dex", true) -> collectDexClasses(file, out)
        }
        return out.toList().sorted()
    }

    fun listMembersInClass(fileName: String, className: String): List<String> {
        val file = File(workspace.depsDir, fileName)
        if (!file.exists()) return listOf("未找到依赖文件")
        return when {
            file.name.endsWith(".dex", true) -> listDexMembers(file, className)
            file.name.endsWith(".jar", true) -> listJarMembers(file, className)
            else -> listOf("当前类型不支持成员浏览")
        }
    }

    fun deleteDependencyFile(fileName: String) {
        val file = File(workspace.depsDir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }

    fun deleteClassFromFile(fileName: String, className: String) {
        val file = File(workspace.depsDir, fileName)
        if (!file.exists()) return
        when {
            file.name.endsWith(".jar", true) -> removeClassFromJar(file, className)
            file.name.endsWith(".dex", true) -> removeClassFromDex(file, className)
        }
    }

    fun clearAllDependencies() {
        val files = workspace.depsDir.listFiles() ?: return
        for (file in files) {
            if (file.isFile) file.delete()
        }
    }

    fun isBuiltinDependency(fileNameIgnored: String): Boolean {
        return false
    }

    fun dependencyDexFilesForMerge(
        importedClassesIgnored: Set<String>,
        importedPackagesIgnored: Set<String>
    ): List<File> {
        return emptyList()
    }
    
    private fun extractFileName(uri: Uri): String {
        val segment = uri.lastPathSegment ?: return "dependency.jar"
        val cut = segment.lastIndexOf('/')
        return if (cut >= 0) segment.substring(cut + 1) else segment
    }
    
    private fun copyUriToFile(resolver: ContentResolver, uri: Uri, target: File) {
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } ?: throw IOException("无法打开依赖文件流")
    }

    private fun collectJarClasses(jarFile: File, out: MutableSet<String>) {
        try {
            ZipFile(jarFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".class") && !entry.name.startsWith("META-INF/")) {
                        val className = entry.name
                            .removeSuffix(".class")
                            .replace('/', '.')
                        out.add(className)
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun collectDexClasses(dexFile: File, out: MutableSet<String>) {
        try {
            val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
            for (clazz in dex.classes) {
                out.add(descriptorToClassName(clazz.type))
            }
        } catch (_: Throwable) {
        }
    }

    private fun listDexMembers(dexFile: File, className: String): List<String> {
        return try {
            val targetDesc = "L" + className.replace('.', '/') + ";"
            val dex = DexFileFactory.loadDexFile(dexFile, org.jf.dexlib2.Opcodes.getDefault())
            val clazz = dex.classes.firstOrNull { it.type == targetDesc }
                ?: return listOf("未找到类: $className")
            val out = mutableListOf<String>()
            out.add("[C] $className")

            clazz.fields.sortedBy { it.name }.forEach { field ->
                val acc = AccessFlags.formatAccessFlagsForField(field.accessFlags).trim()
                val type = descriptorToJavaType(field.type)
                val prefix = if (acc.isEmpty()) "" else "$acc "
                out.add("[F] ${prefix}${type} ${field.name}")
            }

            clazz.methods.sortedBy { it.name }.forEach { method ->
                val acc = AccessFlags.formatAccessFlagsForMethod(method.accessFlags).trim()
                val ret = descriptorToJavaType(method.returnType)
                val params = method.parameterTypes.joinToString(", ") { descriptorToJavaType(it) }
                val prefix = if (acc.isEmpty()) "" else "$acc "
                out.add("[M] ${prefix}${ret} ${method.name}(${params})")
            }
            out
        } catch (t: Throwable) {
            listOf("成员解析失败: ${t.message}")
        }
    }

    private fun listJarMembers(jarFile: File, className: String): List<String> {
        val entryName = className.replace('.', '/') + ".class"
        return try {
            ZipFile(jarFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return listOf("未找到类: $className")
                zip.getInputStream(entry).use { input ->
                    val out = mutableListOf<String>()
                    val reader = ClassReader(input.readBytes())
                    reader.accept(object : ClassVisitor(AsmOpcodes.ASM9) {
                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String?,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?
                        ) {
                            out.add("[C] " + (name?.replace('/', '.') ?: className))
                        }

                        override fun visitField(
                            access: Int,
                            name: String?,
                            descriptor: String?,
                            signature: String?,
                            value: Any?
                        ): FieldVisitor? {
                            val acc = formatAsmAccess(access)
                            val type = descriptor?.let { Type.getType(it).className } ?: "unknown"
                            val prefix = if (acc.isEmpty()) "" else "$acc "
                            out.add("[F] ${prefix}${type} ${name ?: "field"}")
                            return null
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String?,
                            descriptor: String?,
                            signature: String?,
                            exceptions: Array<out String>?
                        ): MethodVisitor? {
                            if (name == null || descriptor == null) return null
                            val acc = formatAsmAccess(access)
                            val methodType = Type.getMethodType(descriptor)
                            val ret = methodType.returnType.className
                            val params = methodType.argumentTypes.joinToString(", ") { it.className }
                            val prefix = if (acc.isEmpty()) "" else "$acc "
                            out.add("[M] ${prefix}${ret} ${name}(${params})")
                            return null
                        }
                    }, 0)
                    out
                }
            }
        } catch (t: Throwable) {
            listOf("成员解析失败: ${t.message}")
        }
    }

    private fun formatAsmAccess(access: Int): String {
        val result = mutableListOf<String>()
        if ((access and AsmOpcodes.ACC_PUBLIC) != 0) result.add("public")
        if ((access and AsmOpcodes.ACC_PRIVATE) != 0) result.add("private")
        if ((access and AsmOpcodes.ACC_PROTECTED) != 0) result.add("protected")
        if ((access and AsmOpcodes.ACC_STATIC) != 0) result.add("static")
        if ((access and AsmOpcodes.ACC_FINAL) != 0) result.add("final")
        if ((access and AsmOpcodes.ACC_ABSTRACT) != 0) result.add("abstract")
        if ((access and AsmOpcodes.ACC_SYNCHRONIZED) != 0) result.add("synchronized")
        return result.joinToString(" ")
    }

    private fun descriptorToJavaType(desc: String): String {
        return try {
            Type.getType(desc).className
        } catch (_: Throwable) {
            desc
        }
    }

    private fun removeClassFromJar(jarFile: File, className: String) {
        val classPath = className.replace('.', '/') + ".class"
        val innerPrefix = classPath.removeSuffix(".class") + "$"
        val temp = File(jarFile.parentFile, jarFile.name + ".tmp")

        ZipInputStream(FileInputStream(jarFile)).use { zis ->
            ZipOutputStream(FileOutputStream(temp)).use { zos ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val remove = entry.name == classPath || entry.name.startsWith(innerPrefix)
                    if (!remove) {
                        val newEntry = java.util.zip.ZipEntry(entry.name)
                        zos.putNextEntry(newEntry)
                        zis.copyTo(zos)
                        zos.closeEntry()
                    }
                    zis.closeEntry()
                }
            }
        }

        if (jarFile.exists()) {
            jarFile.delete()
        }
        temp.renameTo(jarFile)
    }

    private fun removeClassFromDex(dexFile: File, className: String) {
        val descriptor = "L" + className.replace('.', '/') + ";"
        val innerPrefix = descriptor.removeSuffix(";") + "$"
        val temp = File(dexFile.parentFile, dexFile.name + ".tmp")

        val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.getDefault())
        val pool = DexPool(Opcodes.getDefault())
        for (clazz in dex.classes) {
            val type = clazz.type
            val remove = type == descriptor || type.startsWith(innerPrefix)
            if (!remove) {
                pool.internClass(clazz)
            }
        }

        val store = FileDataStore(temp)
        pool.writeTo(store)
        store.close()

        if (dexFile.exists()) {
            dexFile.delete()
        }
        temp.renameTo(dexFile)
    }

    private fun descriptorToClassName(desc: String): String {
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length - 1).replace('/', '.')
        }
        return desc
    }

    private fun loadJavaRuntimeClasses(): List<String> {
        val names = LinkedHashSet<String>()
        val candidates = linkedSetOf<String>()
        val boot = System.getProperty("java.boot.class.path")
        val sunBoot = System.getProperty("sun.boot.class.path")
        val envBoot = System.getenv("BOOTCLASSPATH")
        for (raw in listOf(boot, sunBoot, envBoot)) {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) continue
            value.split(File.pathSeparator)
                .map { it.trim() }
                .filter { it.endsWith(".jar", true) }
                .forEach { candidates.add(it) }
        }

        for (jarPath in candidates) {
            val file = File(jarPath)
            if (!file.exists() || !file.isFile) continue
            try {
                ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith(".class")) continue
                        if (entry.name.startsWith("META-INF/")) continue
                        val fqcn = entry.name.removeSuffix(".class").replace('/', '.')
                        if (fqcn.startsWith("java.") || fqcn.startsWith("javax.") || fqcn.startsWith("org.w3c.") || fqcn.startsWith("org.xml.")) {
                            names.add(fqcn)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (names.isEmpty()) {
            names.addAll(
                listOf(
                    "java.lang.Object",
                    "java.lang.String",
                    "java.lang.System",
                    "java.lang.Math",
                    "java.util.List",
                    "java.util.Map",
                    "java.util.ArrayList",
                    "java.util.HashMap",
                    "java.io.File",
                    "java.net.URL",
                    "java.net.HttpURLConnection"
                )
            )
        }

        return names.toList().sorted()
    }
    
    private fun extractClassesJar(aarFile: File): File {
        val outputFile = File(workspace.depsDir, aarFile.name.replace(".aar", "-classes.jar"))
        
        ZipFile(aarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.name == "classes.jar") {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } > 0) {
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                        }
                    }
                    return outputFile
                }
            }
        }
        throw IOException("AAR 文件中未找到 classes.jar")
    }
}
