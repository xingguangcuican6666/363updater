package com.github.fanziyun.updater.screen

internal interface UpdatePromptParent<T> {
    val updateParent: T?
}

internal object UpdateScreenNavigation {
    fun <T> resultParent(parentScreen: T?): T? {
        val prompt = parentScreen as? UpdatePromptParent<*>
        if (prompt == null) return parentScreen
        @Suppress("UNCHECKED_CAST")
        return prompt.updateParent as T?
    }
}
