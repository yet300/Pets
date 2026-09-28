package com.yet.pets.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Internal serialization mirror of the pinned Codex CLI manifest schema
 * (`PetFile` in `codex-rs/tui/src/pets/model.rs`).
 *
 * Never public: the upstream shape may evolve and no consumer use case
 * justifies the ABI commitment. Cross-module access goes through
 * [PetPackageParser].
 */
@Serializable
internal data class CodexPetManifestDto(
    val id: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val spritesheetPath: String? = null,
    val frame: FrameSpecDto? = null,
    val animations: Map<String, AnimationSpecDto> = emptyMap(),
)

@Serializable
internal data class FrameSpecDto(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
)

@Serializable
internal data class AnimationSpecDto(
    val frames: List<Int> = emptyList(),
    val fps: Double? = null,
    @SerialName("loop")
    val loop: Boolean? = null,
    val fallback: String = "",
)
