package com.pets.example

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPetFilePicker(onPicked: (PetFileType, PickedFile?) -> Unit): (PetFileType) -> Unit {
    val context = LocalContext.current
    var currentType by remember { mutableStateOf(PetFileType.Manifest) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            onPicked(currentType, bytes?.let { PickedFile(uri.lastPathSegment ?: "Selected file", it) })
        }
    }
    return { type ->
        currentType = type
        launcher.launch(if (type == PetFileType.Manifest) "application/json" else "image/*")
    }
}

@Composable
actual fun rememberOverlayPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
