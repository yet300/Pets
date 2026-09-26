package com.yet.pets.io.internal.fs

import okio.FileSystem

internal actual fun platformFileSystem(): FileSystem = FileSystem.SYSTEM
