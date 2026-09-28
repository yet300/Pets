package com.pets.example

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yet.pets.compose.Pet
import com.yet.pets.compose.PetAtlasState
import com.yet.pets.compose.rememberPetPlayerState
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome
import com.yet.pets.host.PetHost
import com.yet.pets.host.PetHostMode
import com.yet.pets.host.PetSystemOverlayAvailability
import com.yet.pets.host.rememberPetHostState
import com.yet.pets.host.rememberPetSystemOverlayAvailability
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pets_kmp.example.shared.generated.resources.Res

enum class PetFileType { Manifest, Spritesheet }
data class PickedFile(val name: String, val bytes: ByteArray)

@Composable
expect fun rememberPetFilePicker(onPicked: (PetFileType, PickedFile?) -> Unit): (PetFileType) -> Unit

@Composable
expect fun rememberOverlayPermissionRequest(): () -> Unit

@OptIn(ExperimentalResourceApi::class)
@Composable
fun App() {
    var gallery by remember { mutableStateOf<PetGalleryState?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        try {
            gallery = PetGalleryState(
                Res.readBytes("files/kodee/pet.pets-kmp.json"),
                Res.readBytes("files/kodee/spritesheet.webp"),
            )
        } catch (_: Exception) { loadError = true }
    }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val state = gallery
            when {
                state != null -> GalleryScreen(state)
                loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { loadError = false; retry++ }) { Text("Retry Kodee") }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(gallery: PetGalleryState) {
    val pager = rememberPagerState(pageCount = { gallery.pageCount })
    val scope = rememberCoroutineScope()
    var showImport by remember { mutableStateOf(false) }
    val overlayState = rememberPetHostState(initiallyVisible = false)
    val availability = rememberPetSystemOverlayAvailability()
    val requestPermission = rememberOverlayPermissionRequest()
    LaunchedEffect(pager.currentPage, gallery.pageCount) { gallery.selectPage(pager.currentPage) }
    LaunchedEffect(gallery.selectedPetIndex, gallery.selectedAnimation) {
        overlayState.play(gallery.selectedAnimation)
    }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val petSize = (maxHeight * 0.38f).coerceIn(150.dp, 250.dp)
        Column(
            Modifier.fillMaxSize().widthIn(max = 600.dp).align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Pets", Modifier.fillMaxWidth().padding(start = 28.dp, top = 20.dp),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
            )
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                if (page < gallery.pets.size) {
                    PetPage(
                        gallery.pets[page],
                        if (page == gallery.selectedPetIndex) gallery.selectedAnimation else null,
                        petSize,
                    )
                } else {
                    Box(
                        Modifier.fillMaxSize().clickable { showImport = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(120.dp).background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(36.dp),
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+", fontSize = 64.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text("Add pet", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(gallery.pageCount) { index ->
                    Box(
                        Modifier.size(if (pager.currentPage == index) 8.dp else 6.dp)
                            .background(
                                if (pager.currentPage == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            ),
                    )
                }
            }
            if (pager.currentPage < gallery.pets.size) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                ) {
                    items(gallery.selectedPet.definition.animationKeys, key = { it.value }) { key ->
                        FilterChip(
                            selected = gallery.selectedAnimation == key,
                            onClick = { gallery.play(key) },
                            label = {
                                Text(key.value.replace('_', ' ').replace('-', ' ')
                                    .replaceFirstChar { it.uppercase() })
                            },
                        )
                    }
                }
                if (availability != PetSystemOverlayAvailability.Unsupported) {
                    FilledTonalButton(
                        onClick = {
                            if (overlayState.isVisible) overlayState.hide()
                            else if (availability == PetSystemOverlayAvailability.PermissionRequired) requestPermission()
                            else overlayState.show()
                        },
                        modifier = Modifier.padding(bottom = 24.dp).heightIn(min = 48.dp),
                    ) {
                        Text(when {
                            overlayState.isVisible -> "Close pet"
                            availability == PetSystemOverlayAvailability.PermissionRequired -> "Allow floating pet"
                            else -> "Open pet"
                        })
                    }
                } else Spacer(Modifier.height(24.dp))
            } else Spacer(Modifier.height(90.dp))
        }
    }
    if (overlayState.isVisible && availability != PetSystemOverlayAvailability.Unsupported) {
        val pet = gallery.overlayPet
        PetHost(pet.definition, pet.spritesheetBytes, overlayState, PetHostMode.SystemOverlay)
    }
    if (showImport) ImportDialog(gallery, onClose = { showImport = false }, onAdded = {
        showImport = false
        scope.launch { pager.animateScrollToPage(gallery.selectedPetIndex) }
    })
}

@Composable
private fun PetPage(pet: ExamplePet, animation: PetAnimationKey?, size: Dp, modifier: Modifier = Modifier.fillMaxSize()) {
    val player = rememberPetPlayerState(pet.definition, pet.spritesheetBytes)
    LaunchedEffect(player, animation) { if (animation != null) player.play(animation) }
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Pet(player, Modifier.fillMaxWidth())
            if (player.atlasState is PetAtlasState.Loading) CircularProgressIndicator(Modifier.size(24.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(pet.definition.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        if (player.atlasState is PetAtlasState.Failed) {
            Text("Could not show pet", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ImportDialog(gallery: PetGalleryState, onClose: () -> Unit, onAdded: () -> Unit) {
    var manifest by remember { mutableStateOf<PickedFile?>(null) }
    var sheet by remember { mutableStateOf<PickedFile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick = rememberPetFilePicker { type, file ->
        if (file != null) {
            if (type == PetFileType.Manifest) manifest = file else sheet = file
            error = null
        }
    }
    val preview = remember(manifest, sheet) {
        if (manifest != null && sheet != null) {
            PetsKmpPackageParser.parse(manifest!!.bytes, sheet!!.bytes) as? PetsKmpParseOutcome.Success
        } else null
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add pet") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pick(PetFileType.Manifest) }, Modifier.fillMaxWidth()) {
                    Text(manifest?.name ?: "Choose pet JSON")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pick(PetFileType.Spritesheet) }, Modifier.fillMaxWidth()) {
                    Text(sheet?.name ?: "Choose spritesheet")
                }
                if (preview != null && sheet != null) {
                    Spacer(Modifier.height(12.dp))
                    PetPage(ExamplePet(preview.definition, sheet!!.bytes), null, 120.dp, Modifier.height(180.dp))
                } else if (manifest != null && sheet != null) {
                    Text("Invalid pet package", color = MaterialTheme.colorScheme.error)
                }
                if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (gallery.importPet(manifest!!.bytes, sheet!!.bytes)) onAdded()
                    else error = gallery.importError
                },
                enabled = preview != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}