package fr.moulou.storify.support

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
data class RandomData2(
    var listOfStringVar2: List<String> = listOf(), val listOfStringVal2: List<String> = listOf(),
    var listOfIntegerVar2: List<Int> = listOf(), val listOfIntegerVal2: List<Int> = listOf(),
    var listOfHomeVar2: List<Home> = listOf(), val listOfHomeVal2: List<Home> = listOf(),

    var mutableListStringVar2: MutableList<String> = mutableListOf(), val mutableListStringVal2: MutableList<String> = mutableListOf(),
    var mutableListIntegerVar2: MutableList<Int> = mutableListOf(), val mutableListIntegerVal2: MutableList<Int> = mutableListOf(),
    var mutableListHomeVar2: MutableList<Home> = mutableListOf(), val mutableListHomeVal2: MutableList<Home> = mutableListOf(),

    var setOfStringVar2: Set<String> = setOf(), val setOfStringVal2: Set<String> = setOf(),
    var setOfIntegerVar2: Set<Int> = setOf(), val setOfIntegerVal2: Set<Int> = setOf(),
    var setOfHomeVar2: Set<Home> = setOf(), val setOfHomeVal2: Set<Home> = setOf(),

    var mutableSetOfStringVar2: MutableSet<String> = mutableSetOf(), val mutableSetOfStringVal2: MutableSet<String> = mutableSetOf(),
    var mutableSetOfIntegerVar2: MutableSet<Int> = mutableSetOf(), val mutableSetOfIntegerVal2: MutableSet<Int> = mutableSetOf(),
    var mutableSetOfHomeVar2: MutableSet<Home> = mutableSetOf(), val mutableSetOfHomeVal2: MutableSet<Home> = mutableSetOf(),

    var linkedHashSetOfStringVar2: LinkedHashSet<String> = LinkedHashSet(), val linkedHashSetOfStringVal2: LinkedHashSet<String> = LinkedHashSet(),
    var linkedHashSetOfIntegerVar2: LinkedHashSet<Int> = LinkedHashSet(), val linkedHashSetOfIntegerVal2: LinkedHashSet<Int> = LinkedHashSet(),
    var linkedHashSetOfHomeVar2: LinkedHashSet<Home> = LinkedHashSet(), val linkedHashSetOfHomeVal2: LinkedHashSet<Home> = LinkedHashSet(),

    var mapOfStringToStringVar2: Map<String, String> = mapOf(), val mapOfStringToStringVal2: Map<String, String> = mapOf(),
    var mapOfIntegerToIntegerVar2: Map<Int, Int> = mapOf(), val mapOfIntegerToIntegerVal2: Map<Int, Int> = mapOf(),
    var mapOfHomeByIndexVar2: Map<Int, Home> = mapOf(), val mapOfHomeByIndexVal2: Map<Int, Home> = mapOf(),

    var mutableMapOfStringToStringVar2: MutableMap<String, String> = mutableMapOf(), val mutableMapOfStringToStringVal2: MutableMap<String, String> = mutableMapOf(),
    var mutableMapOfIntegerToIntegerVar2: MutableMap<Int, Int> = mutableMapOf(), val mutableMapOfIntegerToIntegerVal2: MutableMap<Int, Int> = mutableMapOf(),
    var mutableMapOfHomeByIndexVar2: MutableMap<Int, Home> = mutableMapOf(), val mutableMapOfHomeByIndexVal2: MutableMap<Int, Home> = mutableMapOf(),

    var linkedHashMapOfStringToStringVar2: LinkedHashMap<String, String> = LinkedHashMap(), val linkedHashMapOfStringToStringVal2: LinkedHashMap<String, String> = LinkedHashMap(),
    var linkedHashMapOfIntegerToIntegerVar2: LinkedHashMap<Int, Int> = LinkedHashMap(), val linkedHashMapOfIntegerToIntegerVal2: LinkedHashMap<Int, Int> = LinkedHashMap(),
    var linkedHashMapOfHomeByIndexVar2: LinkedHashMap<Int, Home> = LinkedHashMap(), val linkedHashMapOfHomeByIndexVal2: LinkedHashMap<Int, Home> = LinkedHashMap(),

    var stringArrayVar2: Array<String> = emptyArray(), val stringArrayVal2: Array<String> = emptyArray(),
    var intArrayVar2: IntArray = intArrayOf(), val intArrayVal2: IntArray = intArrayOf(),
    var homeArrayVar2: Array<Home> = emptyArray(), val homeArrayVal2: Array<Home> = emptyArray(),

    var dataBlockVar2: DatesBlock2 = DatesBlock2(), val dataBlockVal2: DatesBlock2 = DatesBlock2(),

    var primitivesBlockVar2: PrimitivesBlock2 = PrimitivesBlock2(), val primitivesBlockVal2: PrimitivesBlock2 = PrimitivesBlock2(),

    var complexObject2: MutableList<MutableMap<String, MutableMap<String, MutableList<String>>>> = mutableListOf(
        mutableMapOf(
            "key1" to mutableMapOf("key 1.1" to mutableListOf("value 1.1.1", "value 1.1.2")),
            "key2" to mutableMapOf("key 2.1" to mutableListOf("value 2.1.1", "value 2.1.2"))
        )
    )
) {
    companion object {
        class RandomDataValidator2 : Validator<RandomData2> {
            private val homeValidator2 = HomeValidator2()

            override fun validate(data: RandomData2, ctx: ValidationContext) {
                // Vérifier que les listes de Home contiennent des données valides
                ctx.validateEach("listOfHomeVar2", data.listOfHomeVar2, homeValidator2)
                ctx.validateEach("listOfHomeVal2", data.listOfHomeVal2, homeValidator2)
                ctx.validateEach("mutableListHomeVar2", data.mutableListHomeVar2, homeValidator2)

                // Les listes de strings ne doivent pas contenir de blancs
                data.listOfStringVar2.forEachIndexed { index, s ->
                    ctx.check(s.isNotBlank(), "listOfStringVar2[$index]", "must not be blank", s)
                }

                // Les sets d'entiers ne doivent pas contenir de valeurs négatives
                data.setOfIntegerVar2.forEach { value ->
                    ctx.check(value >= 0, "setOfIntegerVar2", "must not contain negative values", value)
                }
            }
        }
    }
}

@Serializable
data class SimpleNestedData2(
    var randomDataVar2: RandomData2 = RandomData2(),
    val randomDataVal2: RandomData2 = RandomData2()
) {
    companion object {
        class SimpleNestedDataValidator2 : Validator<SimpleNestedData2> {
            private val randomDataValidator2 = RandomData2.Companion.RandomDataValidator2()

            override fun validate(data: SimpleNestedData2, ctx: ValidationContext) {
                ctx.validateNested("randomDataVar2", data.randomDataVar2, randomDataValidator2)
                ctx.validateNested("randomDataVal2", data.randomDataVal2, randomDataValidator2)
            }
        }
    }
}

@OptIn(ExperimentalUnsignedTypes::class)
@Serializable
data class PrimitivesBlock2(
    var byteVar2: Byte = 1, val byteVal2: Byte = 2,
    var uByteVar2: UByte = 1u, val uByteVal2: UByte = 2u,
    var shortVar2: Short = 10, val shortVal2: Short = 20,
    var uShortVar2: UShort = 10u, val uShortVal2: UShort = 20u,
    var intVar2: Int = 100, val intVal2: Int = 200,
    var uIntVar2: UInt = 100u, val uIntVal2: UInt = 200u,
    var longVar2: Long = 1000L, val longVal2: Long = 2000L,
    var uLongVar2: ULong = 1000uL, val uLongVal2: ULong = 2000uL,
    var floatVar2: Float = 1.5f, val floatVal2: Float = 2.5f,
    var doubleVar2: Double = 3.14, val doubleVal2: Double = 6.28,
    var booleanVar2: Boolean = true, val booleanVal2: Boolean = false,
    var charVar2: Char = 'A', val charVal2: Char = 'Z',
    var stringVar2: String = "hello", val stringVal2: String = "world",
    var unitVar2: Unit = Unit, val unitVal2: Unit = Unit
) {
    companion object {
        class PrimitivesBlockValidator2 : Validator<PrimitivesBlock2> {
            @OptIn(ExperimentalUnsignedTypes::class)
            override fun validate(data: PrimitivesBlock2, ctx: ValidationContext) {
                // Quelques vérifications de cohérence sur les primitives
                ctx.check(data.intVar2 >= 0, "intVar2", "must be non-negative", data.intVar2)
                ctx.check(data.longVar2 >= 0L, "longVar2", "must be non-negative", data.longVar2)
                ctx.check(data.floatVar2.isFinite(), "floatVar2", "must be a finite number", data.floatVar2)
                ctx.check(data.doubleVar2.isFinite(), "doubleVar2", "must be a finite number", data.doubleVar2)
                ctx.check(!data.doubleVar2.isNaN(), "doubleVar2", "must not be NaN", data.doubleVar2)
                ctx.check(!data.floatVar2.isNaN(), "floatVar2", "must not be NaN", data.floatVar2)
                ctx.check(data.stringVar2.length <= 1000, "stringVar2", "must be 1000 characters or less", data.stringVar2)
            }
        }
    }
}

@Serializable
data class DatesBlock2(
    var localDateVar2: LocalDate = LocalDate(2025, 1, 1), val localDateVal2: LocalDate = LocalDate(2025, 6, 15),
    var localTimeVar2: LocalTime = LocalTime(12, 0, 0), val localTimeVal2: LocalTime = LocalTime(18, 30, 0),
    var localDateTimeVar2: LocalDateTime = LocalDateTime(2025, 1, 1, 12, 0, 0), val localDateTimeVal2: LocalDateTime = LocalDateTime(2025, 6, 15, 18, 30, 0),
    var instantVar2: Instant = Instant.parse("2025-01-01T00:00:00Z"), val instantVal2: Instant = Instant.parse("2025-06-15T12:00:00Z"),
    var dateTimePeriodVar2: DateTimePeriod = DateTimePeriod(years = 1), val dateTimePeriodVal2: DateTimePeriod = DateTimePeriod(months = 6),
    var dateTimeUnitVar2: DateTimeUnit = DateTimeUnit.DAY, val dateTimeUnitVal2: DateTimeUnit = DateTimeUnit.HOUR
)
