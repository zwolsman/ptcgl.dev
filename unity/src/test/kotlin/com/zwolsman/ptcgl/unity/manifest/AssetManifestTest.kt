package com.zwolsman.ptcgl.unity.manifest

import com.zwolsman.ptcgl.unity.bundle.UnityBundle
import com.zwolsman.ptcgl.unity.serialized.SerializedFileParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fixture-backed tests for the full Unity manifest pipeline:
 *   bundle bytes → UnityBundle.parse → SerializedFileParser.parse → AssetManifestExtractor.extract
 *
 * Generate the fixture by running VerifyManifestRunner:
 *   SPRING_PROFILES_ACTIVE=local RAINIER_PTCS_TOKEN=<token> \
 *     ./gradlew :app:bootRun --args='--verify-manifest'
 *
 * The first bucket downloaded is typically "10101_0000" (base catalogue, ~40k entries).
 */
class AssetManifestTest {

    private fun findFixture(): Path? =
        Files.list(Path.of("src/test/resources/fixtures"))
            .filter { it.fileName.toString().endsWith(".bundle") }
            .findFirst()
            .orElse(null)

    @Test
    fun `bundle parses into exactly one file`() {
        val fixture = findFixture() ?: return
        val files = UnityBundle.parse(Files.readAllBytes(fixture))
        assertTrue(files.isNotEmpty(), "Bundle should contain at least one embedded file")
    }

    @Test
    fun `manifest entries are non-empty`() {
        val fixture = findFixture() ?: return
        val files = UnityBundle.parse(Files.readAllBytes(fixture))
        val sf = SerializedFileParser.parse(files.first().data)
        val entries = AssetManifestExtractor.extract(sf)
        assertTrue(entries.isNotEmpty(), "AssetManifest should contain at least one entry")
    }

    @Test
    fun `manifest entries have non-blank asset names`() {
        val fixture = findFixture() ?: return
        val files = UnityBundle.parse(Files.readAllBytes(fixture))
        val sf = SerializedFileParser.parse(files.first().data)
        val entries = AssetManifestExtractor.extract(sf)
        assertFalse(entries.any { it.assetName.isBlank() }, "Every entry must have a non-blank assetName")
    }

    @Test
    fun `manifest entries have non-zero crc`() {
        val fixture = findFixture() ?: return
        val files = UnityBundle.parse(Files.readAllBytes(fixture))
        val sf = SerializedFileParser.parse(files.first().data)
        val entries = AssetManifestExtractor.extract(sf)
        assertTrue(entries.all { it.crc != 0L }, "All entries should have a non-zero CRC")
    }

    @Test
    fun `serialized file version is 22 (Unity 6)`() {
        val fixture = findFixture() ?: return
        val files = UnityBundle.parse(Files.readAllBytes(fixture))
        val sf = SerializedFileParser.parse(files.first().data)
        assertEquals(22, sf.version, "Expected SerializedFile version 22 for Unity 6 bundles")
    }
}
