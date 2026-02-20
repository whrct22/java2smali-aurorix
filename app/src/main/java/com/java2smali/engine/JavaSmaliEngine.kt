package com.java2smali.engine

import android.util.Log
import dalvik.system.DexClassLoader
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.java2smali.deps.DependencyManager
import com.java2smali.workspace.WorkspaceManager
import org.codehaus.commons.compiler.util.StringUtil
import org.codehaus.janino.ClassLoaderIClassLoader
import org.codehaus.janino.Compiler
import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Core engine for Java to Smali conversion.
 * Pipeline: Java Source → ECJ (compile) → .class files → D8 (dex) → classes.dex → baksmali → .smali files
 */
class JavaSmaliEngine(
    private val workspace: WorkspaceManager,
    private val dependencyManager: DependencyManager
) {
    companion object {
        private const val TAG = "JavaSmaliEngine"
        private const val MIN_API_LEVEL = 26
    }
    
    private var primarySmaliFile: File? = null
    private var compiledStubClasses: Set<String> = emptySet()
    
    /**
     * Result of the conversion process
     */
    data class Result(
        val dexFile: File,
        val mergedDexFile: File?,
        val smaliDir: File,
        val smaliFiles: List<File>
    )
    
    fun compileWorkspaceToSmali(): Result {
        workspace.clearGenerated()

        val sourceFiles = workspace.listJavaFiles()
        if (sourceFiles.isEmpty()) {
            throw Exception("没有可编译的 Java 文件")
        }

        compileJava(sourceFiles)
        buildDexOutputs(sourceFiles)
        disassembleDex()

        val smaliFiles = collectSmaliFiles(workspace.smaliDir)
        primarySmaliFile = findPrimarySmaliFile(smaliFiles, sourceFiles)

        return Result(
            dexFile = workspace.dexFile,
            mergedDexFile = workspace.mergedDexFile.takeIf { it.exists() },
            smaliDir = workspace.smaliDir,
            smaliFiles = smaliFiles
        )
    }

    fun compileJavaToSmali(javaCode: String): Result {
        val file = workspace.ensureDefaultJavaFile()
        writeFile(file, javaCode)
        return compileWorkspaceToSmali()
    }
    
    fun readMainSmali(): String {
        val target = primarySmaliFile ?: return ""
        if (!target.exists()) return ""
        return readFile(target)
    }
    
    fun readAllSmali(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        collectSmaliFiles(workspace.smaliDir).forEach { file ->
            val relative = file.relativeTo(workspace.smaliDir).path.replace('\\', '/')
            val content = readFile(file)
            result[relative] = content
            result.putIfAbsent(file.name, content)
        }
        return result
    }
    
    private fun compileJava(sourceFiles: List<File>) {
        val classesDir = workspace.classesDir
        val preparedSourceFiles = prepareSourcesForCompile(sourceFiles)
        val stubGeneration = generateDependencyStubs()
        compiledStubClasses = stubGeneration.classNames
        val stubFiles = stubGeneration.files
        val compiler = Compiler()
        val sourceRoots = if (stubFiles.isEmpty()) {
            arrayOf(workspace.srcDir, File(workspace.root, "compile_src"))
        } else {
            arrayOf(workspace.srcDir, File(workspace.root, "stubs"), File(workspace.root, "compile_src"))
        }
        compiler.setSourcePath(sourceRoots)
        compiler.setClassPath(dependencyManager.classpathJars().toTypedArray())
        compiler.setBootClassPath(bootClassPathFiles())
        compiler.setIClassLoader(ClassLoaderIClassLoader(createCompileClassLoader()))
        compiler.setDestinationDirectory(classesDir, false)
        compiler.setDebugSource(true)
        compiler.setDebugLines(true)
        compiler.setDebugVars(false)
        compiler.setVerbose(false)
        try {
            compiler.compile((preparedSourceFiles + stubFiles).toTypedArray())
        } catch (e: Exception) {
            throw Exception("Java 编译失败: ${e.message}", e)
        }
    }

    private fun prepareSourcesForCompile(sourceFiles: List<File>): List<File> {
        val compileRoot = File(workspace.root, "compile_src")
        if (compileRoot.exists()) compileRoot.deleteRecursively()
        compileRoot.mkdirs()

        val classes = dependencyManager.listAvailableClasses()
        val simpleMap = mutableMapOf<String, MutableList<String>>()
        for (fqcn in classes) {
            val simple = fqcn.substringAfterLast('.')
            simpleMap.getOrPut(simple) { mutableListOf() }.add(fqcn)
        }

        val importRegex = Regex("(?m)^\\s*import\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;\\s*$")
        val out = mutableListOf<File>()
        for (src in sourceFiles) {
            val content = readFile(src)
            val importRewritten = importRegex.replace(content) { match ->
                val simple = match.groupValues[1]
                val candidates = simpleMap[simple].orEmpty().distinct()
                if (candidates.isEmpty()) {
                    match.value
                } else {
                    val best = candidates
                        .sortedWith(compareBy<String> { if (it.contains('.')) 0 else 1 }.thenBy { it.length })
                        .first()
                    if (best.contains('.')) "import $best;" else ""
                }
            }

            val relative = src.relativeTo(workspace.srcDir)
            val expectedPackage = relative.parent?.replace(File.separatorChar, '.') ?: ""
            val rewritten = rewritePackageByPath(importRewritten, expectedPackage)
            val dst = File(compileRoot, relative.path)
            writeFile(dst, rewritten)
            out.add(dst)
        }
        return out
    }

    private fun rewritePackageByPath(source: String, expectedPackage: String): String {
        val pkgRegex = Regex("(?m)^\\s*package\\s+[A-Za-z_][\\w.]*\\s*;\\s*(?:\\r?\\n)?")
        if (expectedPackage.isBlank()) {
            return pkgRegex.replaceFirst(source, "")
        }
        if (pkgRegex.containsMatchIn(source)) {
            return pkgRegex.replaceFirst(source, "package $expectedPackage;\n")
        }
        return "package $expectedPackage;\n\n$source"
    }

    private fun generateDependencyStubs(): StubGeneration {
        val classes = dependencyManager.listStubClasses()
        if (classes.isEmpty()) return StubGeneration(emptyList(), emptySet())

        val stubsRoot = File(workspace.root, "stubs")
        if (stubsRoot.exists()) stubsRoot.deleteRecursively()
        stubsRoot.mkdirs()

        val files = mutableListOf<File>()
        val emitted = mutableSetOf<String>()
        for (fqcn in classes) {
            if (fqcn.contains('$')) continue
            if (!emitted.add(fqcn)) continue
            val pkg = fqcn.substringBeforeLast('.', "")
            val simple = fqcn.substringAfterLast('.')
            if (!simple.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue

            val baseDir = if (pkg.isBlank()) stubsRoot else File(stubsRoot, pkg.replace('.', '/'))
            baseDir.mkdirs()
            val f = File(baseDir, "$simple.java")
            val text = if (pkg.isBlank()) {
                "public class $simple {}\n"
            } else {
                "package $pkg;\n\npublic class $simple {}\n"
            }
            writeFile(f, text)
            files.add(f)
        }
        return StubGeneration(files, emitted)
    }
    
    private fun buildDexOutputs(sourceFiles: List<File>) {
        val classesDir = workspace.classesDir

        val classFiles = mutableListOf<File>()
        collectClassFiles(classesDir, classFiles)

        if (classFiles.isEmpty()) {
            throw Exception("未生成 .class 文件")
        }

        val builder = D8Command.builder()
            .setMinApiLevel(MIN_API_LEVEL)
            .setOutput(workspace.dexDir.toPath(), OutputMode.DexIndexed)

        for (classFile in classFiles) {
            val fqcn = classFile.relativeTo(classesDir).path
                .removeSuffix(".class")
                .replace(File.separatorChar, '.')
            if (!compiledStubClasses.contains(fqcn)) {
                builder.addProgramFiles(classFile.toPath())
            }
        }

        for (jarFile in dependencyManager.classpathJars()) {
            builder.addClasspathFiles(jarFile.toPath())
        }

        D8.run(builder.build())

        if (!workspace.dexFile.exists()) {
            throw Exception("D8 未生成 DEX 文件")
        }

        buildMergedDexIfNeeded(sourceFiles)
        Log.d(TAG, "Own DEX generated at ${workspace.dexFile.absolutePath}")
    }

    private fun buildMergedDexIfNeeded(sourceFiles: List<File>) {
        val imports = collectImportedTypes(sourceFiles)
        val depDex = dependencyManager.dependencyDexFilesForMerge(imports.classes, imports.packages)
        if (depDex.isEmpty()) {
            if (workspace.mergedDexFile.exists()) {
                workspace.mergedDexFile.delete()
            }
            return
        }

        val mergedDir = File(workspace.dexDir, "merged_tmp")
        if (mergedDir.exists()) {
            mergedDir.deleteRecursively()
        }
        mergedDir.mkdirs()

        val mergeBuilder = D8Command.builder()
            .setMinApiLevel(MIN_API_LEVEL)
            .setOutput(mergedDir.toPath(), OutputMode.DexIndexed)
            .addProgramFiles(workspace.dexFile.toPath())

        for (dex in depDex) {
            mergeBuilder.addProgramFiles(dex.toPath())
        }

        try {
            D8.run(mergeBuilder.build())

            val mergedClassesDex = File(mergedDir, "classes.dex")
            if (!mergedClassesDex.exists()) {
                throw Exception("合并依赖 DEX 失败")
            }
            copyFile(mergedClassesDex, workspace.mergedDexFile)
        } catch (t: Throwable) {
            if (workspace.mergedDexFile.exists()) {
                workspace.mergedDexFile.delete()
            }
            Log.w(TAG, "依赖 DEX 合并失败，已回退为仅项目 DEX", t)
        } finally {
            mergedDir.deleteRecursively()
        }
    }

    private fun collectImportedTypes(sourceFiles: List<File>): ImportSet {
        return ImportCollector.collectFromFiles(sourceFiles, ::readFile)
    }
    
    private fun disassembleDex() {
        val dexFile = DexFileFactory.loadDexFile(workspace.dexFile, Opcodes.getDefault())
        val options = BaksmaliOptions()
        
        val success = Baksmali.disassembleDexFile(
            dexFile,
            workspace.smaliDir,
            maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
            options
        )
        
        if (!success) {
            throw IOException("baksmali 反汇编失败")
        }
    }
    
    private fun collectClassFiles(dir: File, out: MutableList<File>) {
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectClassFiles(file, out)
            } else if (file.name.endsWith(".class")) {
                out.add(file)
            }
        }
    }
    
    private fun collectSmaliFiles(dir: File): List<File> {
        val result = mutableListOf<File>()
        collectSmali(dir, result)
        return result
    }
    
    private fun collectSmali(file: File, out: MutableList<File>) {
        val children = file.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectSmali(child, out)
            } else if (child.name.endsWith(".smali")) {
                out.add(child)
            }
        }
    }
    
    private fun findPrimarySmaliFile(files: List<File>, sourceFiles: List<File>): File? {
        if (files.isEmpty()) return null
        val preferred = sourceFiles
            .map { it.nameWithoutExtension }
            .toSet()
        for (file in files) {
            val base = file.nameWithoutExtension.substringBefore('$')
            if (preferred.contains(base)) {
                return file
            }
        }
        for (file in files) {
            if (!file.name.contains("$") && file.length() > 0) {
                return file
            }
        }
        return files.first()
    }
    
    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(content.toByteArray(StandardCharsets.UTF_8))
            fos.flush()
        }
    }
    
    private fun readFile(file: File): String {
        FileInputStream(file).use { fis ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } > 0) {
                out.write(buffer, 0, read)
            }
            return out.toString(StandardCharsets.UTF_8.name())
        }
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
    }

    private fun bootClassPathFiles(): Array<File> {
        val candidates = listOf(
            System.getProperty("java.boot.class.path"),
            System.getProperty("sun.boot.class.path"),
            System.getenv("BOOTCLASSPATH")
        )
        for (value in candidates) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) {
                return StringUtil.parsePath(v)
            }
        }
        return emptyArray()
    }

    private fun createCompileClassLoader(): ClassLoader {
        val baseLoader = JavaSmaliEngine::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
        val deps = dependencyManager.dependencyDexFilesForCompile()
        if (deps.isEmpty()) {
            return baseLoader
        }
        val optimizedDir = File(workspace.root, "odex")
        optimizedDir.mkdirs()
        val dexPath = deps.joinToString(File.pathSeparator) { it.absolutePath }
        return DexClassLoader(
            dexPath,
            optimizedDir.absolutePath,
            null,
            baseLoader
        )
    }

    private data class StubGeneration(
        val files: List<File>,
        val classNames: Set<String>
    )
}
