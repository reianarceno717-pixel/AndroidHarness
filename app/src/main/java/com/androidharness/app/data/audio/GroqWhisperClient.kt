package com.androidharness.app.data.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GroqWhisperClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    data class ValidationResult(val success: Boolean, val error: String? = null)

    suspend fun validateApiKey(apiKey: String, client: OkHttpClient = httpClient): ValidationResult = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext ValidationResult(false, "API key cannot be blank")
        }
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer $trimmed")
                .get()
                .build()

            executeText(client, req).let { resp ->
                if (resp.isSuccessful) {
                    ValidationResult(true)
                } else {
                    val body = resp.body
                    val msg = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: "Verification failed (HTTP ${resp.code})"
                    ValidationResult(false, msg.replace(trimmed, "[redacted]"))
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ValidationResult(false, e.message ?: "Failed to connect to Groq API")
        }
    }

    suspend fun transcribe(
        audioFile: File,
        apiKey: String,
        model: String = "whisper-large-v3",
        client: OkHttpClient = httpClient,
    ): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Groq API key is missing"))
        }
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty"))
        }

        try {
            val fileBody = audioFile.asRequestBody("audio/m4a".toMediaTypeOrNull())
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("temperature", "0")
                .addFormDataPart("response_format", "json")
                .build()

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $trimmedKey")
                .post(requestBody)
                .build()

            executeText(client, req).let { resp ->
                val body = resp.body
                if (!resp.isSuccessful) {
                    val err = runCatching {
                        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: "Groq transcription failed (HTTP ${resp.code})"
                    return@withContext Result.failure(RuntimeException(err.replace(trimmedKey, "[redacted]")))
                }

                val text = runCatching {
                    json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()?.trim()
                if (text == null) Result.failure(IllegalStateException("Groq returned an invalid transcription response."))
                else Result.success(text)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class TextResponse(val code: Int, val body: String) {
        val isSuccessful get() = code in 200..299
    }

    /** Keep cancellation attached until the response body has also finished reading. */
    private suspend fun executeText(client: OkHttpClient, request: Request): TextResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        // Cancellation and OkHttp callbacks can run on different threads. Keep the
        // response reference atomic so cancellation cannot miss a just-arrived body.
        val activeResponse = AtomicReference<Response?>(null)

        continuation.invokeOnCancellation {
            call.cancel()
            activeResponse.getAndSet(null)?.close()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) {
                    runCatching { continuation.resumeWithException(error) }
                }
            }

            override fun onResponse(call: Call, responseValue: Response) {
                activeResponse.set(responseValue)
                if (!continuation.isActive) {
                    activeResponse.getAndSet(null)?.close()
                    return
                }

                try {
                    val text = responseValue.body?.string().orEmpty()
                    if (continuation.isActive) {
                        runCatching { continuation.resume(TextResponse(responseValue.code, text)) }
                    }
                } catch (error: IOException) {
                    if (continuation.isActive) {
                        runCatching { continuation.resumeWithException(error) }
                    }
                } finally {
                    activeResponse.getAndSet(null)?.close()
                }
            }
        })
    }
}
