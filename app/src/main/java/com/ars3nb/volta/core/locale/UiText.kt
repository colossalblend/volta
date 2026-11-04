package com.ars3nb.volta.core.locale

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * A message made outside the screen (the view model, the repository) that the screen
 * turns into words: a string resource and its arguments, read in the screen's language
 * at the moment it is shown. An argument may itself be a [UiText] ("Updated: 3 servers").
 * [Raw] carries text that is already final: a provider's, or another part's message.
 */
sealed interface UiText {

    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A count: [count] picks the plural form; [args] fill it (the count by default). */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    data class Raw(val text: String) : UiText

    /** Several texts in a row, [separator] between them ("2 subscriptions, 1 server"). */
    data class Concat(val parts: List<UiText>, val separator: UiText = Raw("")) : UiText

    fun resolve(context: Context): String = resolve(context.resources)

    fun resolve(res: Resources): String = when (this) {
        is Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *resolved(res, args))
        is Plural -> res.getQuantityString(id, count, *resolved(res, args))
        is Raw -> text
        is Concat -> parts.joinToString(separator.resolve(res)) { it.resolve(res) }
    }

    /** This text, then [other]. */
    operator fun plus(other: UiText): UiText = Concat(listOf(this, other))

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int): UiText = Plural(id, count)

        /** [count] in its plural form, the count itself as the only argument. */
        fun count(@PluralsRes id: Int, count: Int): UiText = Plural(id, count)

        private fun resolved(res: Resources, args: List<Any>): Array<Any> =
            args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray()
    }
}

/** The text in the language the screen is drawn in. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}

/**
 * An error whose words are a [UiText], so a screen can show it in its own language.
 * Its [message] is the same text read from [context], for everyone else: a log line, a
 * toast from a tile, the service's error.
 */
class UiTextException(val text: UiText, context: Context) : IllegalStateException(text.resolve(context))

/** What to show for a failure: its [UiText], its own message, or [fallback]. */
fun Throwable.uiText(fallback: UiText): UiText =
    (this as? UiTextException)?.text
        ?: message?.trim()?.takeIf { it.isNotEmpty() }?.let(UiText::Raw)
        ?: fallback
