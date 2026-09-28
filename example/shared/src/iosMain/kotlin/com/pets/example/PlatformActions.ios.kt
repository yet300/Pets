package com.pets.example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIApplication
import platform.darwin.NSObject
import platform.UniformTypeIdentifiers.UTTypeData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

private var activePickerDelegate: PetPickerDelegate? = null

@OptIn(ExperimentalForeignApi::class)
private class PetPickerDelegate(
    private val type: PetFileType,
    private val onPicked: (PetFileType, PickedFile?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentAtURL: NSURL) {
        val access = didPickDocumentAtURL.startAccessingSecurityScopedResource()
        try {
            val data = NSData.dataWithContentsOfURL(didPickDocumentAtURL)
            val bytes = data?.let { source ->
                ByteArray(source.length.toInt()).also { target ->
                    if (target.isNotEmpty()) target.usePinned { memcpy(it.addressOf(0), source.bytes, source.length) }
                }
            }
            onPicked(type, bytes?.let { PickedFile(didPickDocumentAtURL.lastPathComponent ?: "Selected file", it) })
        } finally {
            if (access) didPickDocumentAtURL.stopAccessingSecurityScopedResource()
            activePickerDelegate = null
        }
    }
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        activePickerDelegate = null
    }
}

@Composable
actual fun rememberPetFilePicker(onPicked: (PetFileType, PickedFile?) -> Unit): (PetFileType) -> Unit =
    remember(onPicked) {
        { type ->
            val controller = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
                asCopy = true,
            )
            val delegate = PetPickerDelegate(type, onPicked)
            activePickerDelegate = delegate
            controller.delegate = delegate
            UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
                controller, animated = true, completion = null,
            )
        }
    }

@Composable
actual fun rememberOverlayPermissionRequest(): () -> Unit = remember { {} }
