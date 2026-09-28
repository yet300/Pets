package com.yet.pets.io.internal.fs

import com.yet.pets.io.AbortWith
import com.yet.pets.io.PetLoadError
import okio.FileSystem
import okio.Path

/**
 * Lexical policy for manifest-relative asset paths. Mirrors the upstream
 * `resolve_spritesheet_path` confinement (relative child only; no `..`, no
 * absolute, no drive prefix) plus a cross-platform hardening: backslashes are
 * rejected so the same manifest resolves identically on every host.
 *
 * Returns the cleaned relative path, or throws [AbortWith] carrying
 * [PetLoadError.InvalidSpritesheetPath].
 */
internal fun checkManifestRelativePath(rawPath: String): String {
    if (rawPath.startsWith('/')) {
        throw AbortWith(PetLoadError.InvalidSpritesheetPath(rawPath, "spritesheet path must be relative: $rawPath"))
    }
    if ('\\' in rawPath) {
        throw AbortWith(
            PetLoadError.InvalidSpritesheetPath(rawPath, "spritesheet path must not contain backslashes: $rawPath"),
        )
    }
    val segments = rawPath.split('/')
    for (segment in segments) {
        if (segment == "..") {
            throw AbortWith(
                PetLoadError.InvalidSpritesheetPath(rawPath, "spritesheet path must stay inside the package: $rawPath"),
            )
        }
    }
    val first = segments.firstOrNull() ?: ""
    if (first.length >= 2 && first[0].isLetter() && first[1] == ':') {
        throw AbortWith(
            PetLoadError.InvalidSpritesheetPath(rawPath, "spritesheet path must not have a drive prefix: $rawPath"),
        )
    }
    return rawPath
}

/**
 * Joins manifest-relative [relPath] onto a package [prefix] (`""` for root
 * packages, `"dirname"` for nested ones), resolving `.` lexically. A `..`
 * that would escape the package prefix fails with [PetLoadError.InvalidSpritesheetPath].
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

/**
 * Segment-aware containment: [target] must be a strict descendant of [root].
 * String-prefix comparison alone would wrongly accept `/pet/foobar` under
 * `/pet/foo`; segment comparison rejects it. Both paths must be canonical
 * (symlinks resolved) before calling.
 */
internal fun isStrictDescendant(root: Path, target: Path): Boolean {
    val rootSegments = root.segments
    val targetSegments = target.segments
    if (targetSegments.size <= rootSegments.size) return false
    return targetSegments.subList(0, rootSegments.size) == rootSegments
}

/**
 * Resolves [relPath] (already lexically checked) under [canonicalRoot] and
 * canonicalizes the result, enforcing containment.
 *
 * Symlink policy: links resolving inside the root load normally; links escaping
 * the root fail with [PetLoadError.PathEscape]; dangling links fail with
 * [PetLoadError.DanglingSymlink] when the leaf name is present in its parent
 * directory (best-effort lstat equivalent on Okio's API), else
 * [PetLoadError.MissingSpritesheet].
 *
 * TOCTOU note: canonicalization and opening are not atomic. This protects
 * normal traversal/symlink escapes but is not a sandbox against a hostile
 * concurrently-mutated filesystem. Callers open/read immediately after this
 * returns and enforce read caps while streaming.
 */
internal fun resolveContainedChild(
    fileSystem: FileSystem,
    canonicalRoot: Path,
    relPath: String,
): Path {
    val unresolved = canonicalRoot / relPath
    val canonicalTarget: Path = try {
        fileSystem.canonicalize(unresolved)
    } catch (e: Exception) {
        throw classifyUnresolvableTarget(fileSystem, unresolved)
    }
    if (!isStrictDescendant(canonicalRoot, canonicalTarget)) {
        throw AbortWith(PetLoadError.PathEscape(unresolved.toString()))
    }
    // Some filesystems resolve without existence checks: verify the target
    // stats. A symlink-proven leaf reports dangling; anything else is missing
    // (including a file deleted between resolution and read).
    val targetMetadata = try {
        fileSystem.metadataOrNull(canonicalTarget)
    } catch (_: Exception) {
        null
    }
    if (targetMetadata == null) {
        val linkTarget = try {
            fileSystem.metadataOrNull(unresolved)?.symlinkTarget
        } catch (_: Exception) {
            null
        }
        if (linkTarget != null) {
            throw AbortWith(PetLoadError.DanglingSymlink(unresolved.toString()))
        }
        throw AbortWith(PetLoadError.MissingSpritesheet(unresolved.toString()))
    }
    return canonicalTarget
}

private fun classifyUnresolvableTarget(
    fileSystem: FileSystem,
    unresolved: Path,
): AbortWith {
    // Precise link detection first: several hosts stat the link itself, so a
    // non-null metadata does not prove resolvability.
    val linkTarget = try {
        fileSystem.metadataOrNull(unresolved)?.symlinkTarget
    } catch (_: Exception) {
        null
    }
    if (linkTarget != null) {
        return AbortWith(PetLoadError.DanglingSymlink(unresolved.toString()))
    }
    // Best-effort dangling-symlink detection: the leaf name exists in its parent
    // listing but has no metadata (its target is missing).
    val parent = unresolved.parent
    if (parent != null) {
        try {
            val leafNames = fileSystem.list(parent).map { it.name }
            if (unresolved.name in leafNames && fileSystem.metadataOrNull(unresolved) == null) {
                return AbortWith(PetLoadError.DanglingSymlink(unresolved.toString()))
            }
        } catch (_: Exception) {
            // Fall through to MissingSpritesheet.
        }
    }
    return AbortWith(PetLoadError.MissingSpritesheet(unresolved.toString()))
}
