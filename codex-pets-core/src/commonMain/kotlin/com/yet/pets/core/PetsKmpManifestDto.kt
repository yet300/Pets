package com.yet.pets.core

import kotlinx.serialization.Serializable

/**
 * Internal serialization mirror of Pets KMP Package Format v1.
 *
 * Never public: the wire shape may evolve and no consumer use case justifies
 * the ABI commitment. Cross-module access goes through [PetsKmpPackageParser].
 * Public runtime model ([PetDefinition] et al.) remains independent of JSON.
 */
@Serializable
internal data class PetsKmpManifestDto(
    val schema: String? = null,
    val schemaVersion: Int? = null,
    val id: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val spritesheetPath: String? = null,
    val frame: PetsKmpCellDto? = null,
    val defaultAnimation: String? = null,
    val animations: List<PetsKmpAnimationDto> = emptyList(),
)

@Serializable
internal data class PetsKmpCellDto(
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
internal data class PetsKmpAnimationDto(
    val key: String? = null,
    val loopStart: Int? = null,
    val frames: List<PetsKmpFrameDto> = emptyList(),
)

@Serializable
internal data class PetsKmpFrameDto(
    val index: Int? = null,
    val durationMs: Long? = null,
)
