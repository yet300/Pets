package com.pets.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yet.pets.core.PetAnimationKey
import com.yet.pets.core.PetDefinition
import com.yet.pets.core.PetsKmpPackageParser
import com.yet.pets.core.PetsKmpParseOutcome

data class ExamplePet(val definition: PetDefinition, val spritesheetBytes: ByteArray)

/** Session-local gallery. The parser is the only package validation authority. */
class PetGalleryState(bundledManifest: ByteArray, bundledSpritesheet: ByteArray) {
    val pets = mutableStateListOf<ExamplePet>()
    var selectedPetIndex by mutableIntStateOf(0)
        private set
    var selectedAnimation by mutableStateOf(PetAnimationKey(""))
        private set
    var importError by mutableStateOf<String?>(null)
        private set

    init {
        val result = PetsKmpPackageParser.parse(bundledManifest, bundledSpritesheet)
        require(result is PetsKmpParseOutcome.Success) { "Bundled Kodee package is invalid" }
        pets += ExamplePet(result.definition, bundledSpritesheet)
        selectedAnimation = result.definition.defaultAnimationKey
    }

    val pageCount: Int get() = pets.size + 1
    val selectedPet: ExamplePet get() = pets[selectedPetIndex]
    val overlayPet: ExamplePet get() = selectedPet
    val overlayAnimation: PetAnimationKey get() = selectedAnimation

    fun selectPage(page: Int) {
        if (page !in pets.indices) return
        selectedPetIndex = page
        selectedAnimation = pets[page].definition.defaultAnimationKey
    }

    fun play(key: PetAnimationKey) {
        if (selectedPet.definition.animation(key) != null) selectedAnimation = key
    }

    fun importPet(manifest: ByteArray, spritesheet: ByteArray, fallbackId: String = "imported-pet"): Boolean {
        return when (val outcome = parseImportedPet(manifest, spritesheet, fallbackId)) {
            is ImportedPetResult.Success -> {
                pets += ExamplePet(outcome.definition, spritesheet)
                selectPage(pets.lastIndex)
                importError = null
                true
            }
            else -> {
                importError = outcome.userMessage
                false
            }
        }
    }
}
