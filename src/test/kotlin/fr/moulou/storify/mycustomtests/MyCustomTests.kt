// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.mycustomtests

import fr.moulou.storify.Defaultable
import fr.moulou.storify.StorePath
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.utils.deepCopyViaCbor
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

@Serializable
@StorePath("my-custom-config.json")
data class MyCustomConfig(
    val name: String
){
    companion object : Defaultable<MyCustomConfig> {
        override fun getDefault(): MyCustomConfig = MyCustomConfig(name = "my super config")
    }
}

class MyCustomTests {

    @Test
    fun myCustomTest() {
        val store = StoreFactory.create<MyCustomConfig>()

        val de = store._data.deepCopyViaCbor()

        

        println("fin")
    }

}