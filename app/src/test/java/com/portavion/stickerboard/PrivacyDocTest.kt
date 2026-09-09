package com.portavion.stickerboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyDocTest {
    @Test
    fun generatedFilesMatchTheDocument() {
        val html = stickerboardPolicy.toHtml()
        val checklist = stickerboardPolicy.toConsoleChecklist(
            Footprint.observe(manifestFile(), javaClass.classLoader!!)
        )
        val htmlFile = File(repoRoot(), "docs/privacy/index.html")
        val checklistFile = File(repoRoot(), "docs/privacy/play-data-safety.md")
        if (System.getProperty("privacy.write") == "1") {
            File(repoRoot(), "docs/privacy").mkdirs()
            htmlFile.writeText(html)
            checklistFile.writeText(checklist)
        }
        assertEquals(
            "stale generated privacy files: rerun with ./gradlew :app:testDebugUnitTest -Pprivacy.write and commit",
            html,
            htmlFile.readText(),
        )
        assertEquals(
            "stale generated privacy files: rerun with ./gradlew :app:testDebugUnitTest -Pprivacy.write and commit",
            checklist,
            checklistFile.readText(),
        )
        assertTrue(html.contains("portalier.g@gmail.com"))
        assertFalse(html.contains("guillaume@podaris.com"))
        assertFalse(checklist.contains("guillaume@podaris.com"))
        assertFalse(htmlFile.readText().contains("guillaume@podaris.com"))
        assertFalse(checklistFile.readText().contains("guillaume@podaris.com"))
    }

    @Test
    fun webCopyIsHttpsAndAppearsInTheNoticeItself() {
        assertTrue(stickerboardPolicy.webCopy.startsWith("https://"))
        assertTrue(stickerboardPolicy.toHtml().contains(stickerboardPolicy.webCopy))
    }
}

internal fun repoRoot(): File {
    var dir = File(".").canonicalFile
    repeat(8) {
        if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) return dir
        dir = dir.parentFile ?: error("repo root not found from ${File(".").canonicalFile}")
    }
    error("repo root not found from ${File(".").canonicalFile}")
}

internal fun manifestFile(): File {
    val file = File(repoRoot(), "app/src/main/AndroidManifest.xml")
    require(file.isFile) { "missing $file" }
    return file
}
