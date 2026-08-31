package com.narmanb.pockettoolkit.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.ArrayDeque

internal data class ScannedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val extension: String,
    val parentName: String?
)

internal data class DuplicateGroup(
    val sha256: String,
    val files: List<ScannedFile>
) {
    val fileSize: Long get() = files.firstOrNull()?.size ?: 0L
    val wastedBytes: Long get() = fileSize * (files.size - 1).coerceAtLeast(0)
}

internal data class StorageCategory(
    val name: String,
    val bytes: Long,
    val count: Int
)

internal data class ScanResult(
    val files: List<ScannedFile>,
    val duplicateGroups: List<DuplicateGroup>,
    val categories: List<StorageCategory>,
    val elapsedMillis: Long
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val reclaimableBytes: Long get() = duplicateGroups.sumOf { it.wastedBytes }
}

internal object StorageScanner {
    private val gameExtensions = setOf(
        "3ds", "7z", "a26", "a78", "apk", "bin", "ccd", "chd", "cia", "cue", "cso",
        "d64", "gb", "gba", "gbc", "gcm", "gcz", "gen", "gg", "iso", "md", "n64",
        "nds", "nes", "nkit", "pbp", "pce", "pk3", "rar", "rom", "rvz", "sfc", "smc",
        "sms", "v64", "wad", "wbfs", "wia", "zip", "z64"
    )

    suspend fun scan(context: Context, treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Unable to open the selected folder.")

        val files = mutableListOf<ScannedFile>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = runCatching { current.listFiles().toList() }.getOrDefault(emptyList())
            for (child in children) {
                when {
                    child.isDirectory -> stack.add(child)
                    child.isFile -> {
                        val name = child.name ?: "Unnamed file"
                        val extension = name.substringAfterLast('.', "").lowercase()
                        files += ScannedFile(
                            uri = child.uri,
                            name = name,
                            size = child.length().coerceAtLeast(0L),
                            extension = extension,
                            parentName = current.name
                        )
                    }
                }
            }
        }

        // Hash only plausible game files that have another file of the same size. This keeps
        // duplicate detection exact while avoiding needless reads of every large file.
        val duplicateCandidates = files
            .asSequence()
            .filter { it.size > 0L && it.extension in gameExtensions }
            .groupBy { it.size }
            .values
            .filter { it.size > 1 }
            .flatten()

        val byHash = linkedMapOf<String, MutableList<ScannedFile>>()
        for (file in duplicateCandidates) {
            val hash = sha256(context, file.uri)
            byHash.getOrPut(hash) { mutableListOf() }.add(file)
        }

        val duplicates = byHash
            .filterValues { it.size > 1 }
            .map { (hash, group) -> DuplicateGroup(sha256 = hash, files = group) }
            .sortedByDescending { it.wastedBytes }

        val categories = files
            .groupBy { categoryFor(it.extension) }
            .map { (name, group) -> StorageCategory(name, group.sumOf { it.size }, group.size) }
            .sortedByDescending { it.bytes }

        ScanResult(
            files = files.sortedByDescending { it.size },
            duplicateGroups = duplicates,
            categories = categories,
            elapsedMillis = System.currentTimeMillis() - started
        )
    }

    private fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read $uri" }
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun categoryFor(extension: String): String = when (extension) {
        "iso", "bin", "cue", "chd", "cso", "pbp", "gcm", "gcz", "rvz", "wbfs", "wia", "ccd", "nkit" -> "Disc images"
        "3ds", "cia" -> "Nintendo 3DS"
        "nds" -> "Nintendo DS"
        "gba" -> "Game Boy Advance"
        "gb", "gbc" -> "Game Boy / Color"
        "nes" -> "NES"
        "sfc", "smc" -> "SNES"
        "n64", "z64", "v64" -> "Nintendo 64"
        "md", "gen" -> "Mega Drive / Genesis"
        "sms", "gg" -> "Sega 8-bit"
        "pce" -> "PC Engine"
        "wad", "pk3" -> "Source-port data"
        "zip", "7z", "rar" -> "Archives"
        "apk" -> "Android APKs"
        else -> if (extension.isBlank()) "No extension" else "Other ($extension)"
    }
}
