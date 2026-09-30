// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.core

import kotlinx.serialization.serializer
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/*
 * Les extensions publiques d'update sur BaseStore : set et setIn (remplacer une valeur), mutate et mutateIn (modifier un objet mutable en place, avec
 * navigation), transaction (tout ou rien, rollback sur exception). Tout s'exécute sur le fil appelant, sous le write lock du store ; les callbacks sont
 * notifiés hors du lock.
 *
 * set, setIn, mutate et mutateIn sont inline pour une seule raison : matérialiser le sérialiseur de la valeur à leur site réifié, par une lambda que
 * le pipeline n'appelle que si une copie profonde est due. Tout le reste vit dans BaseStore, en fonctions ordinaires (setValue, mutateValue) : le
 * code d'un appelant, donc le jar d'un mod, ne contient que cet appel (C-45). transaction n'a rien à matérialiser, elle n'est pas inline.
 */

inline fun <reified DATA : Any, reified VALUE> BaseStore<DATA>.set(property: KMutableProperty1<DATA, VALUE>, value: VALUE) = setIn(property, value) { this }

inline fun <reified DATA : Any, reified RECEIVER : Any, reified VALUE> BaseStore<DATA>.setIn(property: KMutableProperty1<RECEIVER, VALUE>, value: VALUE, noinline receiver: DATA.() -> RECEIVER) = setValue(property, value, { serializer<VALUE>() }, receiver)

inline fun <reified DATA : Any, reified VALUE : Any> BaseStore<DATA>.mutate(property: KProperty1<DATA, VALUE>, noinline block: (VALUE) -> Unit) = mutateIn(property, { this }, block)

inline fun <reified DATA : Any, reified RECEIVER : Any, reified VALUE : Any> BaseStore<DATA>.mutateIn(property: KProperty1<RECEIVER, VALUE>, noinline receiver: DATA.() -> RECEIVER, noinline block: (VALUE) -> Unit) = mutateValue(property, { serializer<VALUE>() }, receiver, block)

fun <DATA : Any> BaseStore<DATA>.transaction(block: DATA.() -> Unit) = transactionInternal(block)
