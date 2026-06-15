package org.koitharu.kotatsu.parsers.util

import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.model.*

/**
 * Extension properties to help with smart-casting across modules.
 * These return a local copy of the property value.
 */
public val MangaListFilter.queryOrNull: String?
    get() = query

public val MangaListFilter.authorOrNull: String?
    get() = author

public inline fun <T> T?.ifNotNull(block: (T) -> Unit) {
    if (this != null) block(this)
}

public fun String?.nullIfEmpty(): String? = if (isNullOrEmpty()) null else this
