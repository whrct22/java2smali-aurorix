package com.java2smali.engine

import java.io.File

internal data class ImportSet(
    val classes: Set<String>,
    val packages: Set<String>
)

internal object ImportCollector {
    private val importRegex = Regex(
        "(?m)^\\s*import\\s+(static\\s+)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)(\\.\\*)?\\s*;"
    )

    fun collectFromSourceTexts(sourceTexts: Iterable<String>): ImportSet {
        val classImports = linkedSetOf<String>()
        val packageImports = linkedSetOf<String>()

        for (code in sourceTexts) {
            val sanitizedCode = stripCommentsAndStrings(code)
            for (m in importRegex.findAll(sanitizedCode)) {
                val isStatic = m.groupValues[1].isNotBlank()
                val value = m.groupValues[2]
                val isWildcard = m.groupValues.getOrNull(3) == ".*"

                when {
                    isStatic && isWildcard -> classImports.add(value)
                    isStatic -> classImports.add(value.substringBeforeLast('.', ""))
                    isWildcard -> packageImports.add(value)
                    else -> classImports.add(value)
                }
            }
        }

        return ImportSet(classes = classImports, packages = packageImports)
    }

    private fun stripCommentsAndStrings(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inChar = false

        while (i < code.length) {
            val c = code[i]
            val next = code.getOrNull(i + 1)

            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false
                    out.append('\n')
                } else {
                    out.append(' ')
                }
                i++
                continue
            }

            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    out.append("  ")
                    i += 2
                } else {
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
                continue
            }

            if (inString) {
                if (c == '\\' && next != null) {
                    out.append("  ")
                    i += 2
                } else {
                    if (c == '"') inString = false
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
                continue
            }

            if (inChar) {
                if (c == '\\' && next != null) {
                    out.append("  ")
                    i += 2
                } else {
                    if (c == '\'') inChar = false
                    out.append(if (c == '\n') '\n' else ' ')
                    i++
                }
                continue
            }

            when {
                c == '/' && next == '/' -> {
                    inLineComment = true
                    out.append("  ")
                    i += 2
                }

                c == '/' && next == '*' -> {
                    inBlockComment = true
                    out.append("  ")
                    i += 2
                }

                c == '"' -> {
                    inString = true
                    out.append(' ')
                    i++
                }

                c == '\'' -> {
                    inChar = true
                    out.append(' ')
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }

        return out.toString()
    }

    fun collectFromFiles(sourceFiles: List<File>, reader: (File) -> String): ImportSet {
        return collectFromSourceTexts(sourceFiles.map(reader))
    }
}
