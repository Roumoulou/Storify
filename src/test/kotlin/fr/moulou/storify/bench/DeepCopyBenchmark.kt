package fr.moulou.storify.bench

import fr.moulou.storify.utils.deepCopyValue
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.system.measureNanoTime

/**
 * Benchmarks pour mesurer le coût réel du deep copy via CBOR (serialize → deserialize).
 *
 * Chaque benchmark :
 * 1. Fait un warmup (1 000 itérations) pour que la JVM JIT-compile le code
 * 2. Mesure N itérations et rapporte la moyenne par opération
 */
class DeepCopyBenchmark {

    // ── Data classes de test ──

    @Serializable
    data class Tiny(var x: Int = 42, var name: String = "hello")

    @Serializable
    data class Small(
        var name: String = "Alice",
        var level: Int = 10,
        var health: Double = 99.5,
        var active: Boolean = true
    )

    @Serializable
    data class Medium(
        var name: String = "Guild_Alpha",
        var level: Int = 5,
        var members: List<MemberData> = (1..20).map { MemberData("Player_$it", it * 10) },
        var ranks: Map<String, Int> = mapOf("owner" to 100, "officer" to 50, "member" to 10)
    )

    @Serializable
    data class MemberData(var username: String, var score: Int)

    @Serializable
    data class Large(
        var name: String = "BigGuild",
        var members: List<MemberData> = (1..200).map { MemberData("Player_$it", it) },
        var inventory: Map<String, List<String>> = (1..50).associate {
            "slot_$it" to (1..10).map { i -> "item_${it}_$i" }
        },
        var nested: Medium = Medium()
    )

    @Serializable
    data class Huge(
        var guilds: List<Large> = (1..10).map { Large(name = "Guild_$it") },
        var metadata: Map<String, String> = (1..100).associate { "key_$it" to "value_$it" }
    )

    // ── Benchmark runner ──

    private inline fun <reified T> bench(label: String, iterations: Int, obj: T) {
        // Warmup
        repeat(1_000) { deepCopyValue(obj) }

        // Measure
        val totalNs = measureNanoTime {
            repeat(iterations) { deepCopyValue(obj) }
        }

        val avgUs = (totalNs / iterations) / 1_000.0
        val avgMs = avgUs / 1_000.0
        val opsPerSec = if (avgUs > 0) (1_000_000.0 / avgUs).toLong() else 0

        println("%-12s │ %8.1f µs │ %6.3f ms │ %,10d ops/s │ %,d iterations".format(label, avgUs, avgMs, opsPerSec, iterations))
    }

    // ── Tests ──

    @Test
    fun `deep copy benchmark — all sizes`() {
        println()
        println("Deep Copy Benchmark (CBOR serialize → deserialize)")
        println("═══════════════════════════════════════════════════════════════════")
        println("%-12s │ %8s │ %9s │ %14s │ %s".format("Object", "Avg", "Avg (ms)", "Throughput", "Iterations"))
        println("─────────────┼──────────┼───────────┼────────────────┼────────────")

        bench("Tiny",    100_000, Tiny())
        bench("Small",    50_000, Small())
        bench("Medium",   10_000, Medium())
        bench("Large",     1_000, Large())
        bench("Huge",        200, Huge())

        println("═══════════════════════════════════════════════════════════════════")
        println()
    }

    @Test
    fun `deep copy benchmark — primitive vs object assignment`() {
        println()
        println("Comparison: Deep Copy vs Direct Assignment vs .copy()")
        println("═══════════════════════════════════════════════════════════════════")

        val small = Small()
        val iterations = 100_000

        // Warmup
        repeat(1_000) {
            deepCopyValue(small)
            small.copy()
            val x = small.level
        }

        val deepCopyNs = measureNanoTime { repeat(iterations) { deepCopyValue(small) } }
        val copyNs = measureNanoTime { repeat(iterations) { small.copy() } }
        val assignNs = measureNanoTime { repeat(iterations) { @Suppress("UNUSED_VARIABLE") val x = small.level } }

        val dcAvg = (deepCopyNs / iterations) / 1_000.0
        val cpAvg = (copyNs / iterations) / 1_000.0
        val asAvg = (assignNs / iterations) / 1_000.0

        println("%-20s │ %8.1f µs".format("deepCopyValue()", dcAvg))
        println("%-20s │ %8.1f µs".format(".copy() (shallow)", cpAvg))
        println("%-20s │ %8.3f µs".format("direct assignment", asAvg))
        println()
        println("Deep copy is ~%.0fx slower than .copy()".format(dcAvg / cpAvg))
        println("Deep copy is ~%.0fx slower than direct assignment".format(dcAvg / asAvg))
        println("═══════════════════════════════════════════════════════════════════")
        println()
    }

    @Test
    fun `deep copy benchmark — scaling with collection size`() {
        println()
        println("Scaling: Deep Copy vs Collection Size")
        println("═══════════════════════════════════════════════════════════════════")
        println("%-12s │ %8s │ %14s".format("List size", "Avg", "Throughput"))
        println("─────────────┼──────────┼────────────────")

        for (size in listOf(1, 10, 50, 100, 500, 1000)) {
            @Serializable
            data class ListWrapper(val items: List<MemberData>)
            val obj = ListWrapper((1..size).map { MemberData("P$it", it) })
            val iters = maxOf(100, 10_000 / size)
            repeat(500) { deepCopyValue(obj) } // warmup
            val totalNs = measureNanoTime { repeat(iters) { deepCopyValue(obj) } }
            val avgUs = (totalNs / iters) / 1_000.0
            val ops = if (avgUs > 0) (1_000_000.0 / avgUs).toLong() else 0
            println("%-12s │ %8.1f µs │ %,10d ops/s".format("$size items", avgUs, ops))
        }

        println("═══════════════════════════════════════════════════════════════════")
        println()
    }
}
