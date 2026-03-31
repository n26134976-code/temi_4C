package com.example.temiapp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class HospitalAsrClient {

    data class AsrResponse(
        val transcript: String,
        val rawBody: String
    )

    fun transcribe(audioFile: File): AsrResponse {
        require(audioFile.exists()) { "找不到音檔: ${audioFile.absolutePath}" }

        val boundary = "----TemiAsr${UUID.randomUUID()}"
        val url = buildUrl()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            useCaches = false
            connectTimeout = HospitalAsrConfig.CONNECT_TIMEOUT_MILLIS
            readTimeout = HospitalAsrConfig.READ_TIMEOUT_MILLIS
            setRequestProperty("X-IBM-Client-Id", HospitalAsrConfig.CLIENT_ID)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        try {
            DataOutputStream(connection.outputStream).use { output ->
                writeFilePart(output, boundary, audioFile)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }

            val code = connection.responseCode
            val body = readStream(
                if (code in 200..299) connection.inputStream else connection.errorStream
            )

            if (code !in 200..299) {
                throw IllegalStateException("ASR API 失敗，HTTP $code，body=$body")
            }

            return AsrResponse(
                transcript = parseTranscript(body),
                rawBody = body
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(): String {
        return Uri.parse(HospitalAsrConfig.BASE_URL)
            .buildUpon()
            .appendQueryParameter("prompt", HospitalAsrConfig.PROMPT)
            .appendQueryParameter("lang", HospitalAsrConfig.LANG)
            .appendQueryParameter("format", HospitalAsrConfig.FORMAT)
            .appendQueryParameter("Source", HospitalAsrConfig.SOURCE)
            .appendQueryParameter("SystemName", HospitalAsrConfig.SYSTEM_NAME)
            .appendQueryParameter("Userid", HospitalAsrConfig.USER_ID)
            .build()
            .toString()
    }

    private fun writeFilePart(
        output: DataOutputStream,
        boundary: String,
        audioFile: File
    ) {
        val mimeType = when (audioFile.extension.lowercase()) {
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }

        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"files\"; filename=\"${audioFile.name}\"\r\n"
        )
        output.writeBytes("Content-Type: $mimeType\r\n\r\n")

        BufferedInputStream(audioFile.inputStream()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
            }
        }

        output.writeBytes("\r\n")
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream)).use { reader ->
            buildString {
                var line: String?
                while (true) {
                    line = reader.readLine() ?: break
                    append(line)
                    append('\n')
                }
            }.trim()
        }
    }

    private fun parseTranscript(body: String): String {
        val root = JSONObject(body)
        val results = root.optJSONArray("results") ?: JSONArray()
        if (results.length() == 0) return ""

        val transcripts = buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val transcript = item.optString("transcript").trim()
                if (transcript.isNotEmpty()) {
                    add(cleanTranscript(transcript))
                }
            }
        }

        return transcripts.joinToString(" ").trim()
    }

    private fun cleanTranscript(transcript: String): String {
        return transcript
            .replace(Regex("""\[[0-9:.\-\s>]+]"""), " ")
            .replace("\\n", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
