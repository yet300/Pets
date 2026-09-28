package com.pets.example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberPetFilePicker(onPicked: (PetFileType, PickedFile?) -> Unit): (PetFileType) -> Unit =
    remember(onPicked) {
        { type ->
            SwingUtilities.invokeLater {
                val chooser = JFileChooser().apply {
                    fileFilter = if (type == PetFileType.Manifest)
                        FileNameExtensionFilter("Pets KMP JSON", "json")
                    else FileNameExtensionFilter("Spritesheet image", "png", "webp", "jpg", "jpeg", "gif")
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val file = chooser.selectedFile
                    onPicked(type, runCatching { PickedFile(file.name, file.readBytes()) }.getOrNull())
                }
            }
        }
    }

@Composable
actual fun rememberOverlayPermissionRequest(): () -> Unit = remember { {} }
