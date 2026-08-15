package com.github.fanziyun.updater.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateScreenNavigationTest {
    @Test
    fun `successful update returns past the obsolete prompt`() {
        val titleScreen = TestTitle
        val prompt = TestPrompt(titleScreen)

        val result: TestPage? = UpdateScreenNavigation.resultParent(prompt)
        assertEquals(titleScreen, result)
    }

    @Test
    fun `prompt with no original parent closes after a successful update`() {
        val prompt = TestPrompt(null)

        val result: TestPage? = UpdateScreenNavigation.resultParent(prompt)
        assertEquals(null, result)
    }

    @Test
    fun `non-prompt parent is retained`() {
        val result: TestPage? = UpdateScreenNavigation.resultParent(TestTitle)
        assertEquals(TestTitle, result)
    }

    private sealed interface TestPage
    private data object TestTitle : TestPage
    private class TestPrompt(override val updateParent: TestPage?) : TestPage, UpdatePromptParent<TestPage>
}
