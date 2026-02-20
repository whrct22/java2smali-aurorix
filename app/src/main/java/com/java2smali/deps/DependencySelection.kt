package com.java2smali.deps

internal object DependencySelection {
    fun shouldIncludeDex(
        classes: Iterable<String>,
        importedClasses: Set<String>,
        importedPackages: Set<String>
    ): Boolean {
        if (importedClasses.isEmpty() && importedPackages.isEmpty()) {
            return false
        }
        val normalizedClassImports = importedClasses.mapTo(linkedSetOf(), ::normalizeClassName)
        return classes.any { fqcn ->
            val normalizedFqcn = normalizeClassName(fqcn)
            normalizedClassImports.contains(normalizedFqcn) || importedPackages.contains(normalizedFqcn.substringBeforeLast('.', ""))
        }
    }

    private fun normalizeClassName(name: String): String {
        return name.replace('$', '.')
    }
}
