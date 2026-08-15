package com.github.fanziyun.updater.util

import net.minecraft.client.gui.components.AbstractWidget

object ButtonPlacement {
    const val BUTTON_HEIGHT = 20

    fun belowExistingColumn(children: List<*>, screenHeight: Int, left: Int, right: Int): Int? {
        val widgets = children.filterIsInstance<AbstractWidget>()
            .filter { it.visible && it.x < right && it.x + it.width > left }
        if (widgets.isEmpty()) return null
        val below = widgets.maxOf { it.y + it.height } + 4
        if (below + BUTTON_HEIGHT <= screenHeight - 2) return below
        return (widgets.minOf { it.y } - BUTTON_HEIGHT - 4).coerceAtLeast(2)
    }
}
