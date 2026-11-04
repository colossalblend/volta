package com.ars3nb.volta.desktop.ui.components

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The reading column: phone-width cards stay readable on a wide window. */
val ContentMaxWidth = 720.dp

/**
 * A page of the app: one centred column at most [ContentMaxWidth] wide, scrolling with a
 * thin scrollbar at the window's edge, on the page colour.
 */
@Composable
fun ScrollPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    spacing: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 32.dp),
    content: LazyListScope.() -> Unit
) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            state = state,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth + 48.dp)
                .fillMaxWidth()
                .fillMaxHeight(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = 6.dp, horizontal = 3.dp)
        )
    }
}

/** A page's title with its buttons, where the Android app has its top bar. */
@Composable
fun PageHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}
