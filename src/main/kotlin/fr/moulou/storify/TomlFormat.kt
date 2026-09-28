// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.decodeFromNativeReader
import dev.eav.tomlkt.encodeToNativeWriter
import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.utils.JsonTreeCopier
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories

class TomlFormat(
    private val toml: Toml = Toml { ignoreUnknownKeys = true }
) : StoreFormat {

    /**
     * Le copieur profond des stores TOML : l'arbre JSON, au module de sérialiseurs de ce `Toml` (C-29). Un aller-retour TOML ne saurait pas
     * copier une valeur seule (une liste, un scalaire), la racine d'un document TOML étant toujours une table.
     */
    private val copier = JsonTreeCopier(Json { serializersModule = toml.serializersModule })

    override fun deepCopier(): DeepCopier = copier

    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.bufferedReader().use { reader -> toml.decodeFromNativeReader(deserializer, reader) }
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.bufferedWriter().use { writer -> toml.encodeToNativeWriter(serializer, data, writer) }
    }

    override fun fileExtension(): String = "toml"

    fun underlyingToml(): Toml = toml
}
