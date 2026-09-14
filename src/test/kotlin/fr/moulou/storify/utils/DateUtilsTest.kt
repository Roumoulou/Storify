package fr.moulou.storify.utils

import fr.moulou.storify.utils.DateUtils.formatLocal
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

/**
 * `DateUtils.formatLocal` : le motif par défaut des horodatages du sidecar, et le motif custom.
 */
class DateUtilsTest {

    @Test
    fun `le motif par défaut est celui du sidecar`() {
        val formatted = Clock.System.now().formatLocal()
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}""").matches(formatted))
    }

    @Test
    fun `un motif custom s'applique`() {
        val year = Clock.System.now().formatLocal("yyyy")
        assertTrue(Regex("""\d{4}""").matches(year))
    }
}
