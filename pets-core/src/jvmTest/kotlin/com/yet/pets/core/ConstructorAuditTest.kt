package com.yet.pets.core

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-level audit (remediation §26): [PetDefinition] must not acquire an
 * internal constructor default for `defaultAnimationKey` again. Every adapter
 * supplies it explicitly, and the Kotlin compiler enforces that at every call
 * site. This test fails closed if a default is reintroduced (the compiler
 * would generate a synthetic `$default` constructor helper).
 */
class ConstructorAuditTest {

    @Test
    fun petDefinitionHasNoConstructorDefaults() {
        val helpers = PetDefinition::class.java.declaredMethods.filter {
            it.name.contains("\$default")
        }
        assertTrue(helpers.isEmpty(), "PetDefinition must not declare constructor defaults: $helpers")
    }
}
