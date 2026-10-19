// SPDX-FileCopyrightText: 2024-2026 lisonge (kotlin-json5)
// SPDX-License-Identifier: Apache-2.0
//
// Copié de kotlin-json5 0.8.0 (https://github.com/lisonge/kotlin-json5, li.songe:json5), sous la licence Apache, version 2.0, dont le texte
// est LICENSE.json5 à la racine du dépôt. Modifié par Roumoulou le 2026-10-19 (Storify, C-65) : le paquet li.songe.json5 relocalisé en
// fr.moulou.storify.internal.json5, et les déclarations de premier niveau passées de public à internal. Le reste du fichier est inchangé.

package fr.moulou.storify.internal.json5

import kotlinx.serialization.json.JsonElement

internal object Json5 {
    /**
     * Scans [input] without requiring it to be valid JSON5.
     * Whitespace, including line breaks, is omitted when [ignoreWhitespace] is `true`.
     */
    public fun scanSyntax(
        input: CharSequence,
        ignoreWhitespace: Boolean = true,
    ): Json5SyntaxTokens {
        return scanJson5Syntax(input, ignoreWhitespace)
    }

    /**
     * Scans [input] without retaining tokens and invokes [consumer] synchronously
     * for every emitted syntax span.
     */
    public fun scanSyntax(
        input: CharSequence,
        ignoreWhitespace: Boolean = true,
        consumer: Json5SyntaxConsumer,
    ) {
        scanJson5Syntax(input, ignoreWhitespace, consumer)
    }

    /**
     * Parses [input] into a source-aware document.
     * Invalid or incomplete input is recovered into [Json5Document.diagnostics] instead of throwing.
     */
    public fun parseToDocument(input: CharSequence): Json5Document {
        return parseJson5Document(input.toString())
    }

    /**
     * Parses valid [input] into a [JsonElement].
     * Comments and original source spelling are not represented in the result.
     *
     * @throws Json5ParseException if [input] is invalid or incomplete.
     */
    public fun parseToJsonElement(input: CharSequence): JsonElement {
        return parseJson5Element(input.toString())
    }

    public fun encodeToString(
        element: JsonElement,
        config: Json5EncoderConfig = encoderConfig,
    ): String {
        return innerEncodeToString(element, config)
    }

    /**
     * Encodes [value] as a complete JSON5 string literal, including its quotes.
     *
     * The output uses canonical escape sequences and may therefore differ from
     * the source representation of an equivalent parsed string. Only
     * [Json5EncoderConfig.quoteStrategy] affects the result.
     */
    public fun encodeString(
        value: String,
        config: Json5EncoderConfig = encoderConfig,
    ): String {
        return stringifyString(value, config.quoteStrategy)
    }

    /**
     * Encodes [key] as a complete JSON5 property name.
     *
     * Only [Json5EncoderConfig.quoteStrategy] and [Json5EncoderConfig.unquotedKey]
     * affect the result.
     */
    public fun encodeKey(
        key: String,
        config: Json5EncoderConfig = encoderConfig,
    ): String {
        return stringifyKey(key, config)
    }

    /**
     * Formats valid [input] while preserving comments.
     * Raw literals are preserved unless [Json5FormatConfig.quoteStrategy] enables re-encoding.
     *
     * @throws Json5ParseException if [input] is invalid or incomplete.
     */
    public fun format(
        input: CharSequence,
        config: Json5FormatConfig = formatConfig,
    ): String {
        val document = parseToDocument(input).requireValid()
        return formatJson5Document(document, config)
    }

    /**
     * Returns edits relative to [input] that produce [format], or an empty list
     * when [input] is already formatted with [config].
     */
    public fun formatToEdits(
        input: CharSequence,
        config: Json5FormatConfig = formatConfig,
    ): List<Json5TextEdit> {
        val source = input.toString()
        return singleReplacementEdit(source, format(source, config))
    }

    public val encoderConfig: Json5EncoderConfig = Json5EncoderConfig()

    public val formatConfig: Json5FormatConfig = Json5FormatConfig()
}
