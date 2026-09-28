package com.yet.pets.io

/**
 * Internal control-flow carrier: thrown to unwind archive/package processing
 * with an already-typed [PetLoadError]. Caught at public loader boundaries and
 * converted to [PetLoadOutcome.Failure]. Never escapes the module.
 */
internal class AbortWith(val error: PetLoadError) : Exception()
