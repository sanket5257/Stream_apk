package com.streamforge.app.packs

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the graphics-pack asset format.
 *
 * Packs are JSON, which is the whole point — a new scoreboard is a file, not Kotlin. The cost
 * of that is that a typo produces no compiler error: [PackCatalog] skips any pack it can't
 * parse, so a broken graphic just silently doesn't appear in the app. These tests are what
 * turn that silent disappearance into a failed build.
 *
 * Runs as a plain JVM test (no Robolectric): the assets are read straight off disk, relative
 * to the module directory, using the same Json configuration [PackCatalog] uses at runtime.
 */
class PackDefinitionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val packsDir = File("src/main/assets/packs")

    private fun packFiles(): List<File> =
        packsDir.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }.orEmpty()

    @Test
    fun `pack directory is present and populated`() {
        assertTrue("Missing ${packsDir.absolutePath}", packsDir.isDirectory)
        assertTrue("No pack definitions found", packFiles().isNotEmpty())
    }

    @Test
    fun `every pack parses into the model`() {
        packFiles().forEach { file ->
            try {
                json.decodeFromString<GraphicsPack>(file.readText())
            } catch (t: Throwable) {
                throw AssertionError("${file.name} does not parse as a GraphicsPack", t)
            }
        }
    }

    @Test
    fun `pack ids are unique`() {
        val ids = packFiles().map { json.decodeFromString<GraphicsPack>(it.readText()).id }
        assertEquals("Duplicate pack ids: ${ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys}",
            ids.size, ids.toSet().size)
    }

    @Test
    fun `packs are internally consistent`() {
        packFiles().forEach { file ->
            val pack = json.decodeFromString<GraphicsPack>(file.readText())
            val fieldKeys = pack.fields.map { it.key }.toSet()
            val where = "${file.name} (${pack.id})"

            assertTrue("$where: canvas must have positive dimensions",
                pack.canvas.w > 0 && pack.canvas.h > 0)
            assertTrue("$where: defaultWidth must be a 0..1 fraction of the frame",
                pack.defaultWidth > 0f && pack.defaultWidth <= 1f)

            // Every {placeholder} in a text element must name a real field, or it renders as
            // an empty string on air and the graphic quietly loses a value.
            pack.elements.forEach { element ->
                PLACEHOLDER.findAll(element.text).forEach { match ->
                    val key = match.groupValues[1]
                    assertTrue("$where: text references unknown field '$key'", key in fieldKeys)
                }
                if (element.showIf.isNotBlank()) {
                    assertTrue("$where: showIf references unknown field '${element.showIf}'",
                        element.showIf in fieldKeys)
                }
                if (element.kind == PackElementKind.IMAGE) {
                    assertTrue("$where: IMAGE element has no imageField",
                        element.imageField.isNotBlank())
                    assertTrue("$where: imageField '${element.imageField}' is not a field",
                        element.imageField in fieldKeys)
                    assertTrue(
                        "$where: imageField '${element.imageField}' must be of type IMAGE",
                        pack.fields.first { it.key == element.imageField }.type == PackFieldType.IMAGE,
                    )
                }
                // Elements are positioned in canvas space; one that starts outside it is a
                // layout mistake that would be invisible until someone looked at the stream.
                assertTrue("$where: element starts outside the canvas (${element.x}, ${element.y})",
                    element.x >= 0f && element.y >= 0f &&
                        element.x <= pack.canvas.w && element.y <= pack.canvas.h)
            }

            // Live-control buttons must drive fields that exist, or the button does nothing.
            pack.actions.forEach { action -> assertActionValid(action, fieldKeys, where) }
        }
    }

    @Test
    fun `numeric fields have sane bounds`() {
        packFiles().forEach { file ->
            val pack = json.decodeFromString<GraphicsPack>(file.readText())
            pack.fields.filter { it.type == PackFieldType.NUMBER }.forEach { field ->
                assertTrue("${pack.id}.${field.key}: min must be below max", field.min < field.max)
                val default = field.default.toIntOrNull()
                assertTrue(
                    "${pack.id}.${field.key}: default '${field.default}' is not a number in range",
                    default != null && default in field.min..field.max,
                )
            }
        }
    }

    @Test
    fun `at least one pack is free`() {
        // A paywalled catalogue with nothing usable gives a new user no reason to stay past
        // the first launch.
        val tiers = packFiles().map { json.decodeFromString<GraphicsPack>(it.readText()).tier }
        assertFalse("No FREE pack ships — new users would see a fully locked catalogue",
            tiers.none { it == PackTier.FREE })
    }

    private fun assertActionValid(action: PackAction, fieldKeys: Set<String>, where: String) {
        assertTrue("$where: action '${action.label}' targets unknown field '${action.field}'",
            action.field in fieldKeys)
        action.also.forEach { assertActionValid(it, fieldKeys, where) }
    }

    private companion object {
        val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)}")
    }
}
