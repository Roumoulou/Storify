package fr.moulou.storify.core

import fr.moulou.storify.Operation
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.StoreMeta
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.reflect.KProperty1

interface Store<DATA : Any> : AutoCloseable {

    val log: Logger get() = LoggerFactory.getLogger(javaClass)

    var data: DATA
    val path: Path
    val format: StoreFormat<*>
    val meta: StoreMeta?

    fun saveImmediate()
    fun reloadFromFile()

    val onSaveCallbacks: MutableList<(Operation<DATA>) -> Unit>
    val onReloadCallbacks: MutableList<(Operation<DATA>) -> Unit>
    val onUpdateCallbacks: MutableList<(Operation<DATA>) -> Unit>
    val onUpdateCallbacksMap: MutableMap<KProperty1<*, *>, MutableList<(Operation<DATA>) -> Unit>>

    fun registerOnSave(callback: (Operation<DATA>) -> Unit)
    fun registerOnReload(callback: (Operation<DATA>) -> Unit)
    fun registerOnUpdate(callback: (Operation<DATA>) -> Unit)
    fun registerOnUpdateOn(prop: KProperty1<*, *>, callback: (Operation<DATA>) -> Unit)

    fun pauseAutoSave()

    fun resumeAutoSave()

    fun isAutoSavePaused(): Boolean

    /** `true` après [close] : le store reste lisible, mais fermé aux écritures. */
    val isClosed: Boolean

    /** Détache proprement le store : tick annulé, planificateur arrêté, hook JVM désarmé, sauvegarde d'adieu si dirty. Idempotent. */
    override fun close()

}
