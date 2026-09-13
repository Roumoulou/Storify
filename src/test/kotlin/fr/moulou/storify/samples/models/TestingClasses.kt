package fr.moulou.storify.samples.models

import fr.moulou.storify.Defaultable
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlin.collections.mutableMapOf

/**
 * Classe de données de test avec des types primitifs, des collections et des objets custom imbriqués.
 */
@Serializable
data class RandomData(
    var listOfStringVar: List<String>, val listOfStringVal: List<String>,
    var listOfIntegerVar: List<Int>, val listOfIntegerVal: List<Int>,
    var listOfHomeVar: List<Home>, val listOfHomeVal: List<Home>,

    var mutableListStringVar: MutableList<String>, val mutableListStringVal: MutableList<String>,
    var mutableListIntegerVar: MutableList<Int>, val mutableListIntegerVal: MutableList<Int>,
    var mutableListHomeVar: MutableList<Home>, val mutableListHomeVal: MutableList<Home>,

    var setOfStringVar: Set<String>, val setOfStringVal: Set<String>,
    var setOfIntegerVar: Set<Int>, val setOfIntegerVal: Set<Int>,
    var setOfHomeVar: Set<Home>, val setOfHomeVal: Set<Home>,

    var mutableSetOfStringVar: MutableSet<String>, val mutableSetOfStringVal: MutableSet<String>,
    var mutableSetOfIntegerVar: MutableSet<Int>, val mutableSetOfIntegerVal: MutableSet<Int>,
    var mutableSetOfHomeVar: MutableSet<Home>, val mutableSetOfHomeVal: MutableSet<Home>,

    var linkedHashSetOfStringVar: LinkedHashSet<String>, val linkedHashSetOfStringVal: LinkedHashSet<String>,
    var linkedHashSetOfIntegerVar: LinkedHashSet<Int>, val linkedHashSetOfIntegerVal: LinkedHashSet<Int>,
    var linkedHashSetOfHomeVar: LinkedHashSet<Home>, val linkedHashSetOfHomeVal: LinkedHashSet<Home>,

    var mapOfStringToStringVar: Map<String, String>, val mapOfStringToStringVal: Map<String, String>,
    var mapOfIntegerToIntegerVar: Map<Int, Int>, val mapOfIntegerToIntegerVal: Map<Int, Int>,
    var mapOfHomeByIndexVar: Map<Int, Home>, val mapOfHomeByIndexVal: Map<Int, Home>,

    var mutableMapOfStringToStringVar: MutableMap<String, String>, val mutableMapOfStringToStringVal: MutableMap<String, String>,
    var mutableMapOfIntegerToIntegerVar: MutableMap<Int, Int>, val mutableMapOfIntegerToIntegerVal: MutableMap<Int, Int>,
    var mutableMapOfHomeByIndexVar: MutableMap<Int, Home>, val mutableMapOfHomeByIndexVal: MutableMap<Int, Home>,

    var linkedHashMapOfStringToStringVar: LinkedHashMap<String, String>, val linkedHashMapOfStringToStringVal: LinkedHashMap<String, String>,
    var linkedHashMapOfIntegerToIntegerVar: LinkedHashMap<Int, Int>, val linkedHashMapOfIntegerToIntegerVal: LinkedHashMap<Int, Int>,
    var linkedHashMapOfHomeByIndexVar: LinkedHashMap<Int, Home>, val linkedHashMapOfHomeByIndexVal: LinkedHashMap<Int, Home>,

    var stringArrayVar: Array<String>, val stringArrayVal: Array<String>,
    var intArrayVar: IntArray, val intArrayVal: IntArray,
    var homeArrayVar: Array<Home>, val homeArrayVal: Array<Home>,

    var dataBlockVar: DatesBlock, val dataBlockVal: DatesBlock,

    var primitivesBlockVar: PrimitivesBlock, val primitivesBlockVal: PrimitivesBlock,

    var complexObject: MutableList<MutableMap<String, MutableMap<String, MutableList<String>>>>
) {
    companion object {

        // ─────────────────────────────────────────────
        // Validator pour RandomData (checks partiels)
        // ─────────────────────────────────────────────
        class RandomDataValidator : Validator<RandomData> {
            private val homeValidator = HomeValidator()

            override fun validate(data: RandomData, ctx: ValidationContext) {
                // Vérifier que les listes de Home contiennent des données valides
                ctx.validateEach("listOfHomeVar", data.listOfHomeVar, homeValidator)
                ctx.validateEach("listOfHomeVal", data.listOfHomeVal, homeValidator)
                ctx.validateEach("mutableListHomeVar", data.mutableListHomeVar, homeValidator)

                // Les listes de strings ne doivent pas contenir de blancs
                data.listOfStringVar.forEachIndexed { index, s ->
                    ctx.check(s.isNotBlank(), "listOfStringVar[$index]", "must not be blank", s)
                }

                // Les sets d'entiers ne doivent pas contenir de valeurs négatives
                data.setOfIntegerVar.forEach { value ->
                    ctx.check(value >= 0, "setOfIntegerVar", "must not contain negative values", value)
                }
            }
        }

        fun default(): RandomData {
            return RandomData(
                listOfStringVar = listOf(), listOfStringVal = listOf(),
                listOfIntegerVar = listOf(), listOfIntegerVal = listOf(),
                listOfHomeVar = listOf(), listOfHomeVal = listOf(),

                mutableListStringVar = mutableListOf(), mutableListStringVal = mutableListOf(),
                mutableListIntegerVar = mutableListOf(), mutableListIntegerVal = mutableListOf(),
                mutableListHomeVar = mutableListOf(), mutableListHomeVal = mutableListOf(),

                setOfStringVar = setOf(), setOfStringVal = setOf(),
                setOfIntegerVar = setOf(), setOfIntegerVal = setOf(),
                setOfHomeVar = setOf(), setOfHomeVal = setOf(),

                mutableSetOfStringVar = mutableSetOf(), mutableSetOfStringVal = mutableSetOf(),
                mutableSetOfIntegerVar = mutableSetOf(), mutableSetOfIntegerVal = mutableSetOf(),
                mutableSetOfHomeVar = mutableSetOf(), mutableSetOfHomeVal = mutableSetOf(),

                linkedHashSetOfStringVar = LinkedHashSet(), linkedHashSetOfStringVal = LinkedHashSet(),
                linkedHashSetOfIntegerVar = LinkedHashSet(), linkedHashSetOfIntegerVal = LinkedHashSet(),
                linkedHashSetOfHomeVar = LinkedHashSet(), linkedHashSetOfHomeVal = LinkedHashSet(),

                mapOfStringToStringVar = mapOf(), mapOfStringToStringVal = mapOf(),
                mapOfIntegerToIntegerVar = mapOf(), mapOfIntegerToIntegerVal = mapOf(),
                mapOfHomeByIndexVar = mapOf(), mapOfHomeByIndexVal = mapOf(),

                mutableMapOfStringToStringVar = mutableMapOf(), mutableMapOfStringToStringVal = mutableMapOf(),
                mutableMapOfIntegerToIntegerVar = mutableMapOf(), mutableMapOfIntegerToIntegerVal = mutableMapOf(),
                mutableMapOfHomeByIndexVar = mutableMapOf(), mutableMapOfHomeByIndexVal = mutableMapOf(),

                linkedHashMapOfStringToStringVar = LinkedHashMap(), linkedHashMapOfStringToStringVal = LinkedHashMap(),
                linkedHashMapOfIntegerToIntegerVar = LinkedHashMap(), linkedHashMapOfIntegerToIntegerVal = LinkedHashMap(),
                linkedHashMapOfHomeByIndexVar = LinkedHashMap(), linkedHashMapOfHomeByIndexVal = LinkedHashMap(),

                stringArrayVar = emptyArray(), stringArrayVal = emptyArray(),
                intArrayVar = intArrayOf(), intArrayVal = intArrayOf(),
                homeArrayVar = emptyArray(), homeArrayVal = emptyArray(),

                dataBlockVar = DatesBlock.default(), dataBlockVal = DatesBlock.default(),
                primitivesBlockVar = PrimitivesBlock.default(), primitivesBlockVal = PrimitivesBlock.default(),

                complexObject = mutableListOf(
                    mutableMapOf(
                        "key1" to mutableMapOf("key 1.1" to mutableListOf("value 1.1.1", "value 1.1.2")),
                        "key2" to mutableMapOf("key 2.1" to mutableListOf("value 2.1.1", "value 2.1.2"))
                    )
                )
            )
        }

    }
}

@Serializable
data class SimpleNestedData(
    var randomDataVar: RandomData, val randomDataVal: RandomData,
) {
    companion object {
        // ─────────────────────────────────────────────
        // Validator pour SimpleNestedData
        // ─────────────────────────────────────────────
        class SimpleNestedDataValidator : Validator<SimpleNestedData> {
            private val randomDataValidator = RandomData.Companion.RandomDataValidator()

            override fun validate(data: SimpleNestedData, ctx: ValidationContext) {
                ctx.validateNested("randomDataVar", data.randomDataVar, randomDataValidator)
                ctx.validateNested("randomDataVal", data.randomDataVal, randomDataValidator)
            }
        }

        fun default(): SimpleNestedData {
            return SimpleNestedData(randomDataVar = RandomData.default(), randomDataVal = RandomData.default())
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class PrimitivesBlock(
    var byteVar: Byte, val byteVal: Byte,
    var uByteVar: UByte, val uByteVal: UByte,
    var shortVar: Short, val shortVal: Short,
    var uShortVar: UShort, val uShortVal: UShort,
    var intVar: Int, val intVal: Int,
    var uIntVar: UInt, val uIntVal: UInt,
    var longVar: Long, val longVal: Long,
    var uLongVar: ULong, val uLongVal: ULong,
    var floatVar: Float, val floatVal: Float,
    var doubleVar: Double, val doubleVal: Double,
    var booleanVar: Boolean, val booleanVal: Boolean,
    var charVar: Char, val charVal: Char,
    var stringVar: String, val stringVal: String,
    var unitVar: Unit, val unitVal: Unit,
) {
    companion object {

        // ─────────────────────────────────────────────
        // Validator pour PrimitivesBlock
        // ─────────────────────────────────────────────
        class PrimitivesBlockValidator : Validator<PrimitivesBlock> {
            @OptIn(ExperimentalUnsignedTypes::class)
            override fun validate(data: PrimitivesBlock, ctx: ValidationContext) {
                // Quelques vérifications de cohérence sur les primitives
                ctx.check(data.intVar >= 0, "intVar", "must be non-negative", data.intVar)
                ctx.check(data.longVar >= 0L, "longVar", "must be non-negative", data.longVar)
                ctx.check(data.floatVar.isFinite(), "floatVar", "must be a finite number", data.floatVar)
                ctx.check(data.doubleVar.isFinite(), "doubleVar", "must be a finite number", data.doubleVar)
                ctx.check(!data.doubleVar.isNaN(), "doubleVar", "must not be NaN", data.doubleVar)
                ctx.check(!data.floatVar.isNaN(), "floatVar", "must not be NaN", data.floatVar)
                ctx.check(data.stringVar.length <= 1000, "stringVar", "must be 1000 characters or less", data.stringVar)
            }
        }

        fun default() = PrimitivesBlock(
            byteVar = 1, byteVal = 2,
            uByteVar = 1u, uByteVal = 2u,
            shortVar = 10, shortVal = 20,
            uShortVar = 10u, uShortVal = 20u,
            intVar = 100, intVal = 200,
            uIntVar = 100u, uIntVal = 200u,
            longVar = 1000L, longVal = 2000L,
            uLongVar = 1000uL, uLongVal = 2000uL,
            floatVar = 1.5f, floatVal = 2.5f,
            doubleVar = 3.14, doubleVal = 6.28,
            booleanVar = true, booleanVal = false,
            charVar = 'A', charVal = 'Z',
            stringVar = "hello", stringVal = "world",
            unitVar = Unit, unitVal = Unit,
        )
    }
}

@Serializable
data class DatesBlock(
    var localDateVar: LocalDate, val localDateVal: LocalDate,
    var localTimeVar: LocalTime, val localTimeVal: LocalTime,
    var localDateTimeVar: LocalDateTime, val localDateTimeVal: LocalDateTime,
    var instantVar: Instant, val instantVal: Instant,
    var dateTimePeriodVar: DateTimePeriod, val dateTimePeriodVal: DateTimePeriod,
    var dateTimeUnitVar: DateTimeUnit, val dateTimeUnitVal: DateTimeUnit,
) {
    companion object {
        fun default() = DatesBlock(
            localDateVar = LocalDate(2025, 1, 1), localDateVal = LocalDate(2025, 6, 15),
            localTimeVar = LocalTime(12, 0, 0), localTimeVal = LocalTime(18, 30, 0),
            localDateTimeVar = LocalDateTime(2025, 1, 1, 12, 0, 0), localDateTimeVal = LocalDateTime(2025, 6, 15, 18, 30, 0),
            instantVar = Instant.parse("2025-01-01T00:00:00Z"), instantVal = Instant.parse("2025-06-15T12:00:00Z"),
            dateTimePeriodVar = DateTimePeriod(years = 1), dateTimePeriodVal = DateTimePeriod(months = 6),
            dateTimeUnitVar = DateTimeUnit.DAY, dateTimeUnitVal = DateTimeUnit.HOUR,
        )
    }
}

