package com.mangashelf.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mangashelf.reader.data.GalleryPrefs

/**
 * 顶栏的工作栏切换：漫画 ⇄ 图片。
 *
 * 两个工作栏的数据完全隔离（不同的表、不同的目录、不同的偏好），
 * 所以这里只负责"跳到另一边"，不做任何数据合并。
 */
@Composable
fun WorkspaceSwitch(
    current: String,
    onComic: () -> Unit,
    onGallery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(50)
            )
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WorkspaceTab(
            label = "漫画",
            selected = current == GalleryPrefs.WORKSPACE_COMIC,
            onClick = onComic
        )
        WorkspaceTab(
            label = "图片",
            selected = current == GalleryPrefs.WORKSPACE_GALLERY,
            onClick = onGallery
        )
    }
}

@Composable
private fun WorkspaceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(enabled = !selected) { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(text = label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}
