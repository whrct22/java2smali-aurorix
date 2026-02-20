package com.java2smali.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCollectorTest {

    @Test
    fun collectsRegularAndWildcardImports() {
        val source = """
            import java.util.List;
            import okhttp3.*;

            class Main {}
        """.trimIndent()

        val imports = ImportCollector.collectFromSourceTexts(listOf(source))

        assertTrue(imports.classes.contains("java.util.List"))
        assertTrue(imports.packages.contains("okhttp3"))
    }

    @Test
    fun resolvesStaticImportsToOwningClass() {
        val source = """
            import static java.util.Collections.emptyList;
            import static java.lang.Math.*;

            class Main {}
        """.trimIndent()

        val imports = ImportCollector.collectFromSourceTexts(listOf(source))

        assertTrue(imports.classes.contains("java.util.Collections"))
        assertTrue(imports.classes.contains("java.lang.Math"))
    }

    @Test
    fun deduplicatesImportsAcrossFiles() {
        val sourceA = """
            import java.util.Map;

            class A {}
        """.trimIndent()
        val sourceB = """
            import java.util.Map;
            import retrofit2.*;

            class B {}
        """.trimIndent()

        val imports = ImportCollector.collectFromSourceTexts(listOf(sourceA, sourceB))

        assertEquals(setOf("java.util.Map"), imports.classes)
        assertEquals(setOf("retrofit2"), imports.packages)
    }

    @Test
    fun ignoresImportsInsideCommentsAndStrings() {
        val source = """
            // import fake.one.Ignore;
            /*
              import fake.two.Ignore;
            */
            class Main {
                String text = "import fake.three.Ignore;";
            }
            import java.util.Set;
        """.trimIndent()

        val imports = ImportCollector.collectFromSourceTexts(listOf(source))

        assertEquals(setOf("java.util.Set"), imports.classes)
        assertTrue(imports.packages.isEmpty())
    }

    @Test
    fun keepsNestedClassImports() {
        val source = """
            import okhttp3.Request.Builder;

            class Main {}
        """.trimIndent()

        val imports = ImportCollector.collectFromSourceTexts(listOf(source))

        assertEquals(setOf("okhttp3.Request.Builder"), imports.classes)
        assertTrue(imports.packages.isEmpty())
    }
}
