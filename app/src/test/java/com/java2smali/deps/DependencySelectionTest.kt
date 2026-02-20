package com.java2smali.deps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencySelectionTest {

    @Test
    fun includesDexWhenExactImportedClassExists() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("okhttp3.OkHttpClient", "okhttp3.Request"),
            importedClasses = setOf("okhttp3.Request"),
            importedPackages = emptySet()
        )

        assertTrue(include)
    }

    @Test
    fun includesDexWhenImportedPackageMatchesClassPackage() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("retrofit2.Retrofit", "retrofit2.Call"),
            importedClasses = emptySet(),
            importedPackages = setOf("retrofit2")
        )

        assertTrue(include)
    }

    @Test
    fun includesDexWhenNestedClassUsesDollarNotationInDex() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("okhttp3.Request${'$'}Builder"),
            importedClasses = setOf("okhttp3.Request.Builder"),
            importedPackages = emptySet()
        )

        assertTrue(include)
    }

    @Test
    fun excludesDexWhenOnlySubpackageMatchesImportedPackage() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("retrofit2.http.GET"),
            importedClasses = emptySet(),
            importedPackages = setOf("retrofit2")
        )

        assertFalse(include)
    }

    @Test
    fun excludesDexWhenNoImportMatches() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("org.json.JSONObject", "org.json.JSONArray"),
            importedClasses = setOf("okhttp3.Request"),
            importedPackages = setOf("retrofit2")
        )

        assertFalse(include)
    }

    @Test
    fun excludesDexWhenImportSetIsEmpty() {
        val include = DependencySelection.shouldIncludeDex(
            classes = listOf("org.json.JSONObject"),
            importedClasses = emptySet(),
            importedPackages = emptySet()
        )

        assertFalse(include)
    }
}
