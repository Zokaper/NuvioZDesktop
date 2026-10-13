package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * YouTube playback needs yt-dlp, and yt-dlp needs a JavaScript runtime (Deno) for YouTube's
 * challenges since late 2025 - without it the high-resolution formats disappear (Stage 0, S1).
 * Neither ships in the MSI: they update far more often than Nuvio Z does. They are found
 * (env override, then the app's own folder, then PATH) or downloaded on request.
 */
internal object ReactionTools {
    private val log = Logger.withTag("ReactionTools")

    data class Paths(val ytDlp: File, val deno: File)

    val toolsDir: File by lazy { DesktopStorage.resolveAppDataDir().resolve("reaction").resolve("tools").toFile() }

    fun locate(): Paths? {
        val ytDlp = find("NUVIO_YTDLP_PATH", "yt-dlp.exe") ?: return null
        val deno = find("NUVIO_DENO_PATH", "deno.exe") ?: return null
        return Paths(ytDlp, deno)
    }

    private fun find(envName: String, fileName: String): File? {
        System.getenv(envName)?.let(::File)?.takeIf { it.isFile }?.let { return it }
        File(toolsDir, fileName).takeIf { it.isFile }?.let { return it }
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .asSequence()
            .map { File(it, fileName) }
            .firstOrNull { it.isFile }
    }

    /** mpv options that let the stage play a YouTube (or any yt-dlp supported) URL. */
    fun mpvOptions(paths: Paths): List<String> = listOf(
        "ytdl=yes",
        "script-opts=ytdl_hook-ytdl_path=${paths.ytDlp.absolutePath}",
        // Highest available, so tiny reactor tiles (80-tile compilations) keep as many pixels as exist.
        "ytdl-format=bestvideo[height<=?2160]+bestaudio/best",
        "ytdl-raw-options=js-runtimes=deno:${paths.deno.absolutePath}",
    )

    /** Downloads both tools into [toolsDir]. Blocking; call off the UI thread. */
    fun install(onProgress: (String) -> Unit): Paths {
        toolsDir.mkdirs()
        onProgress("Downloading yt-dlp…")
        download(YT_DLP_URL, File(toolsDir, "yt-dlp.exe"))
        onProgress("Downloading Deno…")
        val zip = File(toolsDir, "deno.zip")
        download(DENO_URL, zip)
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }.firstOrNull { it.name.equals("deno.exe", ignoreCase = true) }
                ?: error("deno.exe not found in the Deno archive")
            val target = File(toolsDir, "deno.exe")
            val partial = File(toolsDir, "deno.exe.part")
            partial.outputStream().use { input.copyTo(it) }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        zip.delete()
        return locate() ?: error("Tools were downloaded but could not be found in $toolsDir")
    }

    private fun download(url: String, target: File) {
        log.i { "downloading $url" }
        var location = url
        repeat(6) {
            val connection = URI(location).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code in 300..399) {
                location = connection.getHeaderField("Location") ?: error("redirect without a location from $location")
                connection.disconnect()
                return@repeat
            }
            check(code == 200) { "HTTP $code for $location" }
            val partial = File(target.parentFile, target.name + ".part")
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }
        error("too many redirects for $url")
    }

    private const val YT_DLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
    private const val DENO_URL = "https://github.com/denoland/deno/releases/latest/download/deno-x86_64-pc-windows-msvc.zip"
}
