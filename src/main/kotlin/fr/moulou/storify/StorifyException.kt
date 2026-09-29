// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

/**
 * L'ancêtre des exceptions que Storify lève à cause d'un fichier (C-33) : un fichier qui ne se lit pas ou ne se décode pas
 * ([StoreDecodeException]), ou qui ne passe pas sa validation ([fr.moulou.storify.validation.ValidationException]). Un consommateur les
 * attrape d'un seul `catch` pour tout ce qui empêche d'ouvrir ou de recharger un store. Les fautes du code (écrire sur un store fermé ou en
 * lecture seule) restent des `IllegalStateException`.
 */
open class StorifyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
