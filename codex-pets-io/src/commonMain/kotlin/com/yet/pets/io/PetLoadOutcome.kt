package com.yet.pets.io

import com.yet.pets.core.PetDefinition

/**
 * Outcome of loading a pet package. Foreign malformed/untrusted packages always
 * yield [Failure] — public loaders never throw for package data.
 */
public sealed interface PetLoadOutcome {
    /**
     * Loaded package. [spritesheetBytes] holds the original bounded encoded
     * image bytes (never a decoded platform image). Content-based equality.
     */
    public class Success(
        public val definition: PetDefinition,
        public val spritesheetBytes: ByteArray,
    ) : PetLoadOutcome {
        override fun equals(other: Any?): Boolean =
            other is Success &&
                definition == other.definition &&
                spritesheetBytes.contentEquals(other.spritesheetBytes)

        override fun hashCode(): Int =
            31 * definition.hashCode() + spritesheetBytes.contentHashCode()

        override fun toString(): String =
            "Success(definition=$definition, spritesheetBytes=<${spritesheetBytes.size} bytes>)"
    }

    /** Loading failed with one or more typed errors. Collection is snapshotted. */
    public class Failure(errors: List<PetLoadError>) : PetLoadOutcome {
        public constructor(error: PetLoadError) : this(listOf(error))

        public val errors: List<PetLoadError> = errors.toList()

        override fun equals(other: Any?): Boolean =
            other is Failure && errors == other.errors

        override fun hashCode(): Int = errors.hashCode()

        override fun toString(): String = "Failure(errors=$errors)"
    }
}
