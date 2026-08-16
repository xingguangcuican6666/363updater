package com.github.fanziyun.updater

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationTest {
    @Test
    fun `all translations match the English key and placeholder sets`() {
        val english = load("en_us")
        LOCALES.forEach { locale ->
            val localized = load(locale)
            assertEquals(english.keySet(), localized.keySet(), "$locale has a different translation key set")
            english.keySet().forEach { key ->
                val reference = english.get(key)
                val translation = localized.get(key)
                assertTrue(reference.isJsonPrimitive && reference.asJsonPrimitive.isString, "en_us:$key is not text")
                assertTrue(translation.isJsonPrimitive && translation.asJsonPrimitive.isString, "$locale:$key is not text")
                assertTrue(translation.asString.isNotBlank(), "$locale:$key is blank")
                assertEquals(
                    placeholders(reference.asString),
                    placeholders(translation.asString),
                    "$locale:$key has incompatible placeholders",
                )
            }
        }
    }

    private fun load(locale: String): JsonObject {
        val path = "/assets/updater363/lang/$locale.json"
        val text = LocalizationTest::class.java.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Missing language resource: $path")
        return JsonParser.parseString(text).asJsonObject
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toList()

    private companion object {
        val LOCALES = listOf("de_de", "en_us", "es_es", "fr_fr", "ja_jp", "ko_kr", "pt_br", "ru_ru", "zh_cn", "zh_tw")
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?s")
    }
}
