package com.ars3nb.volta.desktop.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.ars3nb.volta.desktop.ui.LocalLang

/*
 * Android's stringResource, pluralStringResource and stringArrayResource for the screens
 * ported from the Android app: the same keys (R.string.…), the same words, in the
 * language the window shows (a change of language redraws them).
 */

@Composable
@ReadOnlyComposable
fun stringResource(id: String, vararg args: Any): String = Res.string(id, *args, tag = LocalLang.current.tag)

@Composable
@ReadOnlyComposable
fun pluralStringResource(id: String, count: Int, vararg args: Any): String =
    Res.plural(id, count, *args, tag = LocalLang.current.tag)

@Composable
@ReadOnlyComposable
fun stringArrayResource(id: String): Array<String> = Res.array(id, tag = LocalLang.current.tag).toTypedArray()
