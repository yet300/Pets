package com.yet.pets.io.internal.zip

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError

/**
 * Virtual package-path policy for ZIP entries. Names are UTF-8 decoded with
 * U+FFFD substitution (identical on JVM and Native targets); the policy runs
 * on the decoded string, so malformed bytes cannot smuggle separators or
 * traversal segments. Legacy CP437 names are NOT supported and fail closed
 * (lookup miss, duplicate collision, or rejection). Backslashes are literal
 * characters, never separators; entries never touch a real filesystem.
 */
internal fun normalizeZipEntryPath(rawName: String): String {
    val stripped = if (rawName.endsWith('/')) rawName.dropLast(1) else rawName
    if (stripped.isEmpty()) {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains an empty entry name"))
    }
    if (stripped.startsWith('/')) {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains absolute entry: $rawName"))
    }
    val segments = stripped.split('/')
    for (segment in segments) {
        if (segment.isEmpty() || segment == "." || segment == "..") {
            throw AbortWith(PetLoadError.InvalidArchive("archive contains unsafe entry: $rawName"))
        }
    }
    val first = segments.first()
    if (first.length >= 2 && first[0].isLetter() && first[1] == ':') {
        throw AbortWith(PetLoadError.InvalidArchive("archive contains drive-prefixed entry: $rawName"))
    }
    return segments.joinToString("/")
}

/**
 * Joins a manifest-relative [relPath] onto a package [prefix] (`""` for root
 * packages, `"dirname"` for nested ones), resolving `.` lexically. A `..`
 * that would escape the package prefix fails closed.
 */
internal fun joinPackagePath(prefix: String, relPath: String): String {
    val out = ArrayList<String>()
    if (prefix.isNotEmpty()) out.addAll(prefix.split('/'))
    for (segment in relPath.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (out.isEmpty()) {
                    throw AbortWith(
                        PetLoadError.InvalidSpritesheetPath(relPath, "spritesheet path escapes the package: $relPath"),
                    )
                }
                out.removeAt(out.size - 1)
            }
            else -> out.add(segment)
        }
    }
    if (out.isEmpty()) {
        throw AbortWith(
            PetLoadError.InvalidSpritesheetPath(relPath, "spritesheet path resolves to the package root: $relPath"),
        )
    }
    return out.joinToString("/")
}
