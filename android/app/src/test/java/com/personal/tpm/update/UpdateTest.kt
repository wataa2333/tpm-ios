package com.personal.tpm.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateTest {
    @get:Rule val temp = TemporaryFolder()
    private fun manifest(): JSONObject = JSONObject("""{"schemaVersion":1,"versionCode":58,"versionName":"0.58.0","packageName":"com.personal.tpm","minSdk":29,"apk":{"url":"releases/58/app.apk","size":100,"sha256":"${"a".repeat(64)}"},"patches":[]}""")
    @Test fun manifestResolvesHttpsAndRejectsUntrustedFormats() {
        assertEquals("https://example.com/releases/58/app.apk", UpdateManifest.parse(manifest().toString(), "https://example.com/version.json").apk.url)
        assertThrows(IllegalArgumentException::class.java) { secureUrl("http://example.com/v.json") }
        assertThrows(IllegalArgumentException::class.java) { secureUrl("https://user:pass@example.com/v.json") }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(manifest().put("schemaVersion", 2).toString(), "https://example.com/version.json") }
        val broken = manifest().apply { getJSONObject("apk").put("sha256", "bad") }
        assertThrows(IllegalArgumentException::class.java) { UpdateManifest.parse(broken.toString(), "https://example.com/version.json") }
        val future = manifest().put("patches", org.json.JSONArray("""[{"algorithm":"future-format"}]"""))
        assertEquals("https://example.com/releases/58/app.apk", UpdateManifest.parse(future.toString(), "https://example.com/version.json").apk.url)
    }
    @Test fun releaseNotesSurviveChecksAndLegacyPatchesAreIgnored() {
        val json = manifest().put("notes", "修复界面\n优化加载动画").put("patches", "invalid legacy field")
        val release = UpdateManifest.parse(json.toString(), "https://example.com/version.json")
        assertEquals("修复界面\n优化加载动画", release.notes)
        assertEquals(58L, release.versionCode)
        assertEquals("", UpdateManifest.parse(manifest().toString(), "https://example.com/version.json").notes)
    }

    private class Response(val status: Int, val bytes: ByteArray, val range: String? = null) : HttpURLConnection(URL("https://example.com/app.apk")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getHeaderField(name: String?): String? = if (name == "Content-Range") range else null
    }
    private fun fixture(): Pair<UpdateFile, ByteArray> {
        val bytes = "0123456789".toByteArray()
        val file = temp.newFile().apply { writeBytes(bytes) }
        return UpdateFile("https://example.com/app.apk", 10, sha256(file)) to bytes
    }
    @Test fun downloadResumesAcrossInstancesAndHandlesIgnoredRange() = runTest {
        for (honorsRange in listOf(true, false)) {
            val (file, data) = fixture()
            val dir = temp.newFolder()
            File(dir, file.sha256 + ".part").writeBytes(data.take(4).toByteArray())
            val response = if (honorsRange) Response(206, data.drop(4).toByteArray(), "bytes 4-9/10") else Response(200, data)
            val done = UpdateTransport { response }.download(file, dir) { _, _, _ -> }
            assertEquals("bytes=4-", response.getRequestProperty("Range"))
            assertArrayEquals(data, done.readBytes())
        }
    }
    @Test fun interruptedDownloadRetriesFromSavedOffset() = runTest {
        val (file, data) = fixture()
        var attempt = 0
        val requests = mutableListOf<Response>()
        val done = UpdateTransport {
            (if (attempt++ == 0) Response(200, data.take(4).toByteArray()) else Response(206, data.drop(4).toByteArray(), "bytes 4-9/10")).also { requests += it }
        }.download(file, temp.newFolder()) { _, _, _ -> }
        assertEquals(2, attempt)
        assertEquals("bytes=4-", requests.last().getRequestProperty("Range"))
        assertArrayEquals(data, done.readBytes())
    }
    @Test fun checksumFailureRestartsAndRetriesAreBounded() = runTest {
        val (file, data) = fixture()
        var count = 0
        val dir = temp.newFolder()
        val output = UpdateTransport { Response(200, if (count++ == 0) "corruption".toByteArray() else data) }.download(file, dir) { _, _, _ -> }
        assertEquals(2, count)
        assertArrayEquals(data, output.readBytes())
        count = 0
        try { UpdateTransport { count++; Response(503, byteArrayOf()) }.download(file, temp.newFolder()) { _, _, _ -> }; fail() }
        catch (_: java.io.IOException) { assertEquals(3, count) }
    }
    @Test fun badRangeRestartsRatherThanAppendingWrongBytes() = runTest {
        val (file, data) = fixture()
        val dir = temp.newFolder()
        File(dir, file.sha256 + ".part").writeBytes(data.take(4).toByteArray())
        var count = 0
        val output = UpdateTransport { if (count++ == 0) Response(206, data, "bytes 0-9/10") else Response(200, data) }.download(file, dir) { _, _, _ -> }
        assertEquals(2, count)
        assertArrayEquals(data, output.readBytes())
    }
    @Test fun cancellationDoesNotRetryAndCachedHashIsRechecked() = runTest {
        val (file, data) = fixture()
        val dir = temp.newFolder()
        var calls = 0
        try {
            UpdateTransport { calls++; throw CancellationException() }.download(file, dir) { _, _, _ -> }
            fail()
        } catch (_: CancellationException) { assertEquals(1, calls) }
        val cached = File(dir, file.sha256 + ".bin").apply { writeText("corruption") }
        val output = UpdateTransport { Response(200, data) }.download(file, dir) { _, _, _ -> }
        assertEquals(cached, output)
        assertArrayEquals(data, output.readBytes())
        UpdateTransport { error("must reuse verified cache") }.download(file, dir) { _, _, _ -> }
    }
}
