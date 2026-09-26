package com.yet.pets.io.internal.fs

import okio.FileSystem

/**
 * Host filesystem handle. Split as expect/actual because `FileSystem.SYSTEM`
 * lives in Okio's platform intermediate source sets: referencing it directly
 * from `commonMain` breaks common metadata compilation (and any consumer
 * metadata jar), even though every platform compilation resolves it.
 */
internal expect fun platformFileSystem(): FileSystem
