package net.agl.react.fs

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val broken: Boolean,
    val size: Long,
    val lastModified: Long,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val items: List<FileEntry>? = null,
)

data class FileContent(
    val path: String,
    val content: String,
    val truncated: Boolean,
)

@RestController
@RequestMapping("/api/fs")
class FileSystemController(
    // All paths are resolved relative to this and may never escape it.
    @Value("\${fs.root}") rootPath: String,
) {

    private val rootDir: Path = Paths.get(rootPath).toRealPath()

    private val maxReadBytes = 1_000_000

    @GetMapping("/get")
    fun get(@RequestParam(defaultValue = "") path: String): FileEntry {
        val target = resolveSafe(path)
        val entry = toEntryOrNull(target)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such file or directory: $path")
        if (!entry.isDirectory) {
            return entry
        }
        val children = Files.newDirectoryStream(target).use { stream ->
            stream.mapNotNull(::toEntryOrNull).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
        return entry.copy(items = children)
    }

    @GetMapping("/read")
    fun read(@RequestParam path: String): FileContent {
        val target = resolveSafe(path)
        if (!Files.isRegularFile(target)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a regular file: $path")
        }
        val size = Files.size(target)
        val bytes = Files.newInputStream(target).use { it.readNBytes(maxReadBytes) }
        return FileContent(relativePath(target), String(bytes, Charsets.UTF_8), size > bytes.size)
    }

    // Entries unreadable even without following the link (e.g. permission denied) are skipped
    // rather than failing the whole listing; broken symlinks are still reported, flagged as such.
    private fun toEntryOrNull(p: Path): FileEntry? {
        val linkAttrs = try {
            Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: java.io.IOException) {
            return null
        }
        val isSymlink = linkAttrs.isSymbolicLink
        val broken = isSymlink && !Files.exists(p)
        val targetAttrs = if (isSymlink && !broken) {
            try {
                Files.readAttributes(p, BasicFileAttributes::class.java)
            } catch (e: java.io.IOException) {
                null
            }
        } else {
            null
        }
        val attrs = targetAttrs ?: linkAttrs
        return FileEntry(
            name = p.fileName.toString(),
            path = relativePath(p),
            isDirectory = attrs.isDirectory,
            isSymlink = isSymlink,
            broken = broken,
            size = attrs.size(),
            lastModified = attrs.lastModifiedTime().toMillis(),
        )
    }

    private fun relativePath(p: Path): String =
        if (p == rootDir) "" else rootDir.relativize(p).toString()

    /**
     * Resolves a user-supplied relative path against [rootDir], rejecting anything
     * (via "..", absolute paths, or symlinks) that would resolve outside of it.
     */
    private fun resolveSafe(rawPath: String): Path {
        val cleaned = rawPath.trim().removePrefix("/")
        val candidate = rootDir.resolve(cleaned).normalize()
        if (!candidate.startsWith(rootDir)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Path escapes root directory")
        }
        if (!Files.exists(candidate)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such file or directory: $rawPath")
        }
        val real = candidate.toRealPath()
        if (!real.startsWith(rootDir)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Path escapes root directory")
        }
        return real
    }
}
