// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.factory

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.AnnotatedTomlData
import fr.moulou.storify.support.ExternalAnnotatedDefaults
import fr.moulou.storify.support.ExternalPlainDefaults
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.ResourceData
import fr.moulou.storify.support.classFilesOf
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.referencesInto
import fr.moulou.storify.support.resetAnnotatedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** L'appelant témoin : les huit fabriques, avec ce qu'un mod y met de lui-même, une config et un format explicites. */
class FactoryCaller {
    fun byCompanion() = StoreFactory.create<AnnotatedData>()
    fun byCompanionAt(path: String) = StoreFactory.create<PlainData>(path, config = StoreConfig(withAutoSave = false))
    fun byConstructor() = StoreFactory.createFromConstructor<AnnotatedData>()
    fun byConstructorAt(path: String) = StoreFactory.createFromConstructor<AnnotatedTomlData>(path, format = JsonFormat())
    fun byDefaultable() = StoreFactory.createFromDefaultable<AnnotatedData, ExternalAnnotatedDefaults>()
    fun byDefaultableAt(path: String) = StoreFactory.createFromDefaultable<PlainData, ExternalPlainDefaults>(path, config = StoreConfig(withAutoSave = false))
    fun byResource() = StoreFactory.createFromResource<ResourceData>()
    fun byResourceAt(path: String) = StoreFactory.createFromResource<ResourceData>(path, "resource-data_default.json")
}

/**
 * Le garde-fou de C-46 : les huit fabriques sont inline pour matérialiser `DATA::class` et le sérialiseur, rien de plus. Le fichier .class
 * d'un appelant ne doit référencer de la lib que `StoreFactory` et ses quatre points d'entrée, plus ce que l'appelant construit lui-même.
 * Ni les annotations, ni l'enum des formats, ni le constructeur de `BaseStore` : ce qu'un mod compilé aujourd'hui ne doit pas figer.
 */
class FactoryCallerBytecodeTest {

    @Test
    fun `un appelant des huit fabriques ne référence de la lib que StoreFactory, ses points d'entrée, et sa propre config et son format`() {
        val references = classFilesOf(FactoryCaller::class.java).values
            .flatMap { referencesInto(it, "fr/moulou/storify/") }
            .filterNot { it.startsWith("factory/") || it.startsWith("support/") } // ses propres classes, et les data classes qu'il ouvre
            .toSortedSet()

        val expected = sortedSetOf(
            "JsonFormat.<init>", "core/StoreConfig.<init>", // ce que l'appelant construit lui-même
            "core/StoreFactory.INSTANCE", "core/StoreFactory.openFromCompanion", "core/StoreFactory.openFromConstructor", "core/StoreFactory.openFromDefaultable", "core/StoreFactory.openFromResource",
        )
        assertEquals(expected, references)
    }

    @Test
    fun `l'appelant témoin ouvre bien ses stores par les huit fabriques`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        resetAnnotatedFile("build/tmp/storify-tests/annotated/resource-data.json")
        val caller = FactoryCaller()

        caller.byCompanion().use { assertEquals("bonjour", it.data.greeting) }
        caller.byCompanionAt(newStorePath("companion.json").toString()).use { assertEquals("default", it.data.name) }
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json") // le fichier annoté vient de naître : chaque voie repart sans lui
        caller.byConstructor().use { assertEquals("bonjour", it.data.greeting) }
        caller.byConstructorAt(newStorePath("constructor.json").toString()).use { assertEquals("toml", it.data.title) }
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        caller.byDefaultable().use { assertEquals("externe", it.data.greeting) }
        caller.byDefaultableAt(newStorePath("defaultable.json").toString()).use { assertEquals("external", it.data.name) }
        caller.byResource().use { assertEquals("resource", it.data.origin) }
        caller.byResourceAt(newStorePath("resource.json").toString()).use { assertEquals(42, it.data.level) }
    }
}
