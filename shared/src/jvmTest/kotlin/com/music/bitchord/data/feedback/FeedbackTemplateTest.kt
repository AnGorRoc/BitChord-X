package com.music.bitchord.data.feedback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app must fill replies exactly as the server and the dashboard preview describe them. */
class FeedbackTemplateTest {
    private val vectors by lazy {
        // Tests run from the module directory; the vectors live with the server that owns them.
        val file = File("../backend/feedback/testdata/template_vectors.json")
        assertTrue(file.isFile, "shared vectors not found at ${file.absolutePath}")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @Test
    fun rendersLikeTheServer() {
        val cases = vectors.getValue("render").jsonArray
        assertTrue(cases.size > 10)
        for (c in cases) {
            val o = c.jsonObject
            val vars = o.getValue("vars").jsonObject.mapValues { it.value.jsonPrimitive.content }
            val template = o.getValue("template").jsonPrimitive.content
            assertEquals(o.getValue("out").jsonPrimitive.content, FeedbackTemplate.render(template, vars), "for $template with $vars")
        }
    }

    @Test
    fun lintsLikeTheServer() {
        for (c in vectors.getValue("lint").jsonArray) {
            val o = c.jsonObject
            val template = o.getValue("template").jsonPrimitive.content
            val want = o.getValue("problems").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(want, FeedbackTemplate.lint(template), "for $template")
        }
    }
}
