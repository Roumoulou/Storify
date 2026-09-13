@file:Suppress("unused")

package fr.moulou.storify

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

sealed class Operation<DATA : Any>

class SetOperation<DATA : Any, RECEIVER : Any, VALUE>(
    val prop: KMutableProperty1<RECEIVER, VALUE>,
    val old: CapturedValue<VALUE>,
    val new: CapturedValue<VALUE>
) : Operation<DATA>()

enum class SaveTrigger { AUTO_SAVE, IMMEDIATE, SHUTDOWN, CLOSE }

class SaveOperation<DATA : Any>(
    val old: CapturedValue<DATA>,
    val new: CapturedValue<DATA>,
    val trigger: SaveTrigger
) : Operation<DATA>()

class ReloadOperation<DATA : Any>(
    val prop: KProperty<*>,
    val old: CapturedValue<DATA>,
    val new: CapturedValue<DATA>
) : Operation<DATA>()

class MutateOperation<DATA : Any, RECEIVER : Any, VALUE : Any>(
    val prop: KProperty1<RECEIVER, VALUE>,
    val old: CapturedValue<VALUE>,
    val new: CapturedValue<VALUE>
) : Operation<DATA>()

class ValidationFailedOperation<DATA : Any, RECEIVER : Any, VALUE>(
    val prop: KProperty1<RECEIVER, VALUE>,
    val attemptedValue: CapturedValue<VALUE>,
    val old: CapturedValue<VALUE>,
    val validationError: String
) : Operation<DATA>()

class TransactionOperation<DATA : Any>(
    val old: CapturedValue<DATA>,
    val new: CapturedValue<DATA>,
    val success: Boolean = true,
    val validationError: String? = null
) : Operation<DATA>()
