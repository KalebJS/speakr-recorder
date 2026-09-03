package dev.kalebjs.speakr.recorder

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal Speakr REST API v1 client (Bearer token auth). */
class SpeakrApi(private val serverUrl: String, private val token: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun req(url: String): Request.Builder =
        Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")

    /** Validate credentials; returns the user's display name. */
    @Throws(IOException::class, SpeakrException::class)
    fun me(): String {
        val resp = client.newCall(req("$serverUrl/api/v1/users/me").build()).execute()
        resp.use {
            val body = it.body?.string() ?: ""
            if (it.code == 401) throw SpeakrException("Invalid or expired token")
            if (!it.isSuccessful) throw SpeakrException("Server error ${it.code}")
            val obj = json.parseToJsonElement(body).jsonObjectSafe()
            val name = obj?.get("name")?.toString()?.trim('"')
                ?: obj?.get("username")?.toString()?.trim('"')
                ?: "OK"
            return name
        }
    }

    @Throws(IOException::class, SpeakrException::class)
    fun tags(): List<Tag> {
        val resp = client.newCall(req("$serverUrl/api/v1/tags").build()).execute()
        resp.use {
            val body = it.body?.string() ?: ""
            if (it.code == 401) throw SpeakrException("Invalid or expired token")
            if (!it.isSuccessful) throw SpeakrException("Server error ${it.code}")
            // Speakr wraps the list: {"tags": [...]}. Accept both shapes.
            val obj = json.parseToJsonElement(body).jsonObjectSafe()
            val arr = obj?.get("tags")?.toString() ?: body
            return json.decodeFromString(arr)
        }
    }

    /** Upload a recording with optional tag ids. Returns true on 2xx. */
    @Throws(IOException::class, SpeakrException::class)
    fun upload(file: File, tagIds: List<Long>): Boolean {
        val mime = when {
            file.name.endsWith(".m4a") || file.name.endsWith(".mp4") -> "audio/mp4"
            file.name.endsWith(".mp3") -> "audio/mpeg"
            file.name.endsWith(".ogg") -> "audio/ogg"
            else -> "application/octet-stream"
        }.toMediaTypeOrNull()

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody(mime)
            )
            .addFormDataPart("file_last_modified", file.lastModified().toString())
        tagIds.forEachIndexed { i, id -> body.addFormDataPart("tag_ids[$i]", id.toString()) }

        val resp = client.newCall(
            req("$serverUrl/api/v1/recordings/upload").post(body.build()).build()
        ).execute()
        resp.use {
            val errBody = it.body?.string() ?: ""
            if (it.code == 401) throw SpeakrException("Invalid or expired token")
            if (!it.isSuccessful) throw SpeakrException("Server error ${it.code}: ${errBody.take(200)}")
            return true
        }
    }

    companion object {
        fun baseUrlOk(url: String): Boolean =
            url.startsWith("https://") || url.startsWith("http://")
    }
}

class SpeakrException(message: String) : IOException(message)

private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): kotlinx.serialization.json.JsonObject? =
    this as? kotlinx.serialization.json.JsonObject