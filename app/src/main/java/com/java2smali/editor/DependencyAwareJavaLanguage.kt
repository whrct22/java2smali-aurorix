package com.java2smali.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference

class DependencyAwareJavaLanguage(
    private val dependencyProvider: () -> List<String>
) : JavaLanguage() {

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        super.requireAutoComplete(content, position, publisher, extraArguments)

        val prefix = extractPrefix(content, position)
        if (prefix.isEmpty()) return

        val lowerPrefix = prefix.lowercase()
        val suggestions = linkedSetOf<String>()
        for (fqcn in dependencyProvider()) {
            val simple = fqcn.substringAfterLast('.')
            if (simple.lowercase().startsWith(lowerPrefix) || fqcn.lowercase().startsWith(lowerPrefix)) {
                suggestions.add(fqcn)
            }
            if (suggestions.size >= 64) break
        }

        for (fqcn in suggestions) {
            val simple = fqcn.substringAfterLast('.')
            val item = SimpleCompletionItem(simple, prefix.length, simple)
            item.desc(fqcn)
            publisher.addItem(item)
        }
    }

    private fun extractPrefix(content: ContentReference, position: CharPosition): String {
        val line = content.getLine(position.line).toString()
        val col = position.column.coerceIn(0, line.length)
        var i = col - 1
        while (i >= 0) {
            val c = line[i]
            val ok = c == '_' || c == '$' || c == '.' || c.isLetterOrDigit()
            if (!ok) break
            i--
        }
        return line.substring(i + 1, col).substringAfterLast('.')
    }
}
