package com.ars3nb.volta.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.LangRu
import com.ars3nb.volta.desktop.i18n.Strings

/** The words of the language in use; every screen reads its text from here. */
val LocalStrings = staticCompositionLocalOf<Strings> { LangRu }

/** The language in use, for things Strings doesn't carry (country names, number formats). */
val LocalLang = staticCompositionLocalOf { Lang.RU }
