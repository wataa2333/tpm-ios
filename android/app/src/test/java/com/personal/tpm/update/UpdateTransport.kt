package com.personal.tpm.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Isolated from Session: update requests never carry business tokens or cookies. */
class UpdateTransport(private val open: (String) -> HttpURLConnection = { URL(secureUrl(it)).openConnection() as HttpURLConnection }) {
    private fun connect(url: String): HttpURLConnection = open(url).apply {
        connectTimeout = 15_000; readTimeout = 20_000
        instanceFollowRedirects = false // COS URLs are direct; do not silently downgrade HTTPS.
        useCaches = false
        setRequestProperty("Accept-Encoding", "identity")
        setRequestProperty("Cache-Control", "no-cache")
    }

    suspend fun manifest(url: String): String = retry {
        val connection = connect(url)
        try {
            if (connection.responseCode != 200) throw IOException("检查更新失败：HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val data = java.io.ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (output.size() <= 256 * 1024) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer, 0, minOf(buffer.size, 256 * 1024 + 1 - output.size()))
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                require(data.size <= 256 * 1024) { "更新清单过大" }
                String(data, Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    /** The SHA identifies immutable contents, so partial files remain usable across process restarts. */
    suspend fun download(file: UpdateFile, directory: File, progress: (Long, Long, Int) -> Unit): File {
        directory.mkdirs()
        val done = File(directory, file.sha256 + ".bin")
        val part = File(directory, file.sha256 + ".part")
        val ctx = currentCoroutineContext()
        if (done.exists()) {
            if (done.length() == file.size && sha256(done) { ctx.ensureActive() } == file.sha256) return done
            done.delete()
        }
        retry { attempt ->
            ctx.ensureActive()
            if (part.length() > file.size) part.delete()
            if (part.length() != file.size) {
                val connection = connect(file.url)
                var offset = part.length()
                if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
                try {
                    val status = connection.responseCode
                    if (status == 206) {
                        val expected = "bytes $offset-${file.size - 1}/${file.size}"
                        if (connection.getHeaderField("Content-Range") != expected) {
                            part.delete(); throw IOException("服务器断点范围不匹配，重新下载")
                        }
                    } else if (status == 200) {
                        offset = 0 // Server ignored Range: truncate, never append a second APK.
                    } else if (status == 416) {
                        part.delete(); throw IOException("下载断点失效，重新下载")
                    } else throw IOException("下载失败：HTTP $status")
                    val encoding = connection.getHeaderField("Content-Encoding")
                    require(encoding == null || encoding.equals("identity", true)) { "下载内容编码不匹配" }
                    val length = connection.getHeaderFieldLong("Content-Length", -1)
                    require(length < 0 || length == file.size - offset) { "下载内容长度不匹配" }
                    connection.inputStream.use { input ->
                        RandomAccessFile(part, "rw").use { output ->
                            output.setLength(offset); output.seek(offset)
                            var received = offset
                            progress(received, file.size, attempt)
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ctx.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                require(n.toLong() <= file.size - received) { "下载文件超出声明大小" }
                                output.write(buffer, 0, n); received += n
                                progress(received, file.size, attempt)
                            }
                            output.fd.sync()
                            if (received != file.size) throw IOException("连接提前结束，保留断点重试")
                        }
                    }
                } finally { connection.disconnect() }
            }
            if (sha256(part) { ctx.ensureActive() } != file.sha256) {
                part.delete(); throw IOException("SHA-256 校验失败，重新下载")
            }
            check(part.renameTo(done)) { "无法保存更新文件" }
        }
        return done
    }

    private suspend fun <T> retry(block: suspend (Int) -> T): T {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try { return block(attempt + 1) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { if (attempt == 2) throw e }
            if (attempt < 2) delay(1000L shl attempt)
        }
        error("不可达")
    }
}
