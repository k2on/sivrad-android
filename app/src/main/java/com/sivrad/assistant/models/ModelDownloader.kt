package com.sivrad.assistant.models

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext

/**
 * Downloads model files one at a time into
 * a `.part` file that resumes with an HTTP Range request after an
 * interruption, verifies the SHA-256, and only then moves it into place — so
 * a file at its final path is always a complete, verified one.
 *
 * App-scoped rather than tied to the setup screen, so rotating or leaving
 * the screen does not restart a 2.5 GB download.
 */
class ModelDownloader(private val catalog: ModelCatalog, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data class Running(
            val file: String,
            val index: Int,
            val count: Int,
            val bytes: Long,
            val total: Long?,
            val verifying: Boolean,
        ) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Downloads whichever of [files] are not on disk yet. One batch at a time. */
    fun start(files: List<ModelFile>, onComplete: () -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            try {
                val todo = files.filterNot { catalog.file(it).isFile }
                todo.forEachIndexed { i, m -> fetch(m, i, todo.size) }
                _state.value = State.Done
                onComplete()
            } catch (e: CancellationException) {
                _state.value = State.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun fetch(m: ModelFile, index: Int, count: Int) {
        val dest = catalog.file(m)
        dest.parentFile!!.mkdirs()
        val part = File(dest.path + ".part")
        val name = dest.name

        var have = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(m.url)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(request).execute().use { resp ->
            when {
                resp.code == 416 -> Unit // .part is already complete; verify below.
                resp.code == 206 -> Unit
                resp.isSuccessful -> have = 0 // server ignored Range: start over.
                else -> throw IOException("HTTP ${resp.code} for $name")
            }
            if (resp.code != 416) {
                val body = resp.body ?: throw IOException("empty response for $name")
                val total = m.sizeBytes ?: body.contentLength().takeIf { it >= 0 }?.plus(have)
                FileOutputStream(part, have > 0).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        var done = have
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 1 shl 20 || done == total) {
                                lastReport = done
                                _state.value = State.Running(name, index, count, done, total, verifying = false)
                            }
                        }
                    }
                }
            }
        }

        m.sizeBytes?.let { expected ->
            if (part.length() != expected) {
                part.delete()
                throw IOException("$name: expected $expected bytes, got ${part.length()}")
            }
        }
        if (m.sha256.isNotBlank()) {
            _state.value = State.Running(name, index, count, part.length(), part.length(), verifying = true)
            val actual = sha256(part)
            if (!actual.equals(m.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("$name: checksum mismatch (got $actual)")
            }
        }
        if (!part.renameTo(dest)) throw IOException("could not move $name into place")
    }

    private suspend fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ModelDownloader"
    }
}
