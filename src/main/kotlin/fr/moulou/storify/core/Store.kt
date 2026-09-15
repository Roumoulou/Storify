package fr.moulou.storify.core

import fr.moulou.storify.Operation
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.StoreMeta
import fr.moulou.storify.validation.ValidationResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.reflect.KProperty1

interface Store<DATA : Any> : AutoCloseable {

    val log: Logger get() = LoggerFactory.getLogger(javaClass)

    val data: DATA
    val path: Path
    val format: StoreFormat
    val meta: StoreMeta?

    fun saveImmediate()

    /**
     * Relit le fichier et remplace les données en mémoire, avec revalidation par défaut : en échec,
     * la mémoire reste intacte et une ValidationException remonte. `validate = false` saute la revalidation.
     */
    fun reloadFromFile(validate: Boolean = true)

    /** Valide les données en mémoire avec le validator du store (Success sans validator). */
    fun validateNow(): ValidationResult

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
