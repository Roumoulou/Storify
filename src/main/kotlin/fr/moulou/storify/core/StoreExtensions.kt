package fr.moulou.storify.core

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

// ══════════════════════════════════════════════════════════════════════════════
// SYNC — Default mode. Everything runs on the calling thread.
// ══════════════════════════════════════════════════════════════════════════════
// ✅ Data always valid         ✅ Value available immediately
// ⏱️ Deep copy + validation on the calling thread (blocking)
//
// Available: set, setIn, mutate, mutateIn, transaction
// ══════════════════════════════════════════════════════════════════════════════

inline fun <reified DATA : Any, reified VALUE> BaseStore<DATA>.set(property: KMutableProperty1<DATA, VALUE>, value: VALUE) = setIn(property, value) { this }

inline fun <reified DATA : Any, reified RECEIVER : Any, reified VALUE> BaseStore<DATA>.setIn(property: KMutableProperty1<RECEIVER, VALUE>, value: VALUE, noinline receiver: DATA.() -> RECEIVER) = setInternal(property, value, receiver)

inline fun <reified DATA : Any, reified VALUE : Any> BaseStore<DATA>.mutate(property: KProperty1<DATA, VALUE>, noinline block: (VALUE) -> Unit) = mutateIn(property, { this }, block)

inline fun <reified DATA : Any, reified RECEIVER : Any, reified VALUE : Any> BaseStore<DATA>.mutateIn(property: KProperty1<RECEIVER, VALUE>, noinline receiver: DATA.() -> RECEIVER, noinline block: (VALUE) -> Unit) = mutateInternal(property, receiver, block)

inline fun <reified DATA : Any> BaseStore<DATA>.transaction(noinline block: DATA.() -> Unit) = transactionInternal(block)
