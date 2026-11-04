package com.ars3nb.volta.core.locale

import com.ars3nb.volta.desktop.i18n.Res as Texts

/**
 * The Android app's UiText on Windows: a message made outside the screen (the balancer,
 * a restore) as a text key and its arguments, read in the window's language when shown.
 * An argument may itself be a [UiText]. [Raw] carries text that is already final.
 */
sealed interface UiText {

    data class Res(val id: String, val args: List<Any> = emptyList()) : UiText

    /** A count: [count] picks the plural form; [args] fill it (the count by default). */
    data class Plural(val id: String, val count: Int, val args: List<Any> = listOf(count)) : UiText

    data class Raw(val text: String) : UiText

    /** Several texts in a row, [separator] between them. */
    data class Concat(val parts: List<UiText>, val separator: UiText = Raw("")) : UiText

    fun resolve(tag: String = Texts.currentTag): String = when (this) {
        is Res -> if (args.isEmpty()) Texts.string(id, tag = tag) else Texts.string(id, *resolved(tag, args), tag = tag)
        is Plural -> Texts.plural(id, count, *resolved(tag, args), tag = tag)
        is Raw -> text
        is Concat -> parts.joinToString(separator.resolve(tag)) { it.resolve(tag) }
    }

    /** This text, then [other]. */
    operator fun plus(other: UiText): UiText = Concat(listOf(this, other))

    companion object {
        fun of(id: String, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(id: String, count: Int): UiText = Plural(id, count)

        fun count(id: String, count: Int): UiText = Plural(id, count)

        private fun resolved(tag: String, args: List<Any>): Array<Any> =
            args.map { if (it is UiText) it.resolve(tag) else it }.toTypedArray()
    }
}

/** What to show for a failure: its own message, or [fallback]. */
fun Throwable.uiText(fallback: UiText): UiText =
    message?.trim()?.takeIf { it.isNotEmpty() }?.let(UiText::Raw) ?: fallback
