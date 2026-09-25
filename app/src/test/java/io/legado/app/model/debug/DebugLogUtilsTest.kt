package io.legado.app.model.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DebugLogUtils 与 toDebugString 纯函数测试。
 * - 覆盖字符串化路径：null/字符串/CharSequence/Collection/Map/Array/兜底 toString。
 * - 边界：maxLength 截断、header 超长、剩余配额不足。
 * - formatDuration 三段式（ms / s / m s）与 null 输入。
 */
class DebugLogUtilsTest {

    // ---------- toDebugString ----------

    @Test
    fun `null 返回 null`() {
        assertNull(null.toDebugString())
    }

    @Test
    fun `字符串直接截断`() {
        assertEquals("hello", "hello".toDebugString())
        assertEquals("hello", "hello".toDebugString(5))
        assertEquals("he", "hello".toDebugString(2))
        assertEquals("h".repeat(200), "h".repeat(500).toDebugString())
    }

    @Test
    fun `CharSequence 按字符串处理`() {
        assertEquals("abc", StringBuilder("abc").toDebugString())
    }

    @Test
    fun `空集合只输出 header`() {
        assertEquals("Collection(size=0)", emptyList<Any>().toDebugString())
    }

    @Test
    fun `非空集合输出首元素`() {
        assertEquals("Collection(size=1)[42]", listOf(42).toDebugString())
        assertEquals("Collection(size=2)[a]", listOf("a", "b").toDebugString())
    }

    @Test
    fun `集合首元素递归字符串化且受配额限制`() {
        val nested = listOf(listOf(1))
        assertEquals("Collection(size=1)[Collection(size=1)[1]]", nested.toDebugString())
    }

    @Test
    fun `map 与 array 只输出 size`() {
        assertEquals("Map(size=2)", mapOf("a" to 1, "b" to 2).toDebugString())
        assertEquals("Array(size=3)", arrayOf(1, 2, 3).toDebugString())
    }

    @Test
    fun `普通对象回退 toString 并截断`() {
        assertEquals("kotlin.Unit", Unit.toDebugString())
        assertEquals("12", 123456789.toDebugString(2))
    }

    @Test
    fun `maxLength 小于等于 header 长度时只保留 header 前缀`() {
        assertEquals("Collection", "Collection(size=3)".toDebugString(10))
        assertEquals("Co", "Collection(size=3)".toDebugString(2))
        assertEquals("Collection(size=3)", listOf(1, 2, 3).toDebugString(18))
        assertEquals("Collection(size=3)", listOf(1, 2, 3).toDebugString(19))
        assertEquals("", listOf(1, 2, 3).toDebugString(0))
    }

    @Test
    fun `maxLength 只够给 header 与括号时不出首元素`() {
        // header 18 字符 + 2 括号 = 20，剩余配额 = maxLength - 20
        assertEquals("Collection(size=1)", listOf(42).toDebugString(19))
        assertEquals("Collection(size=1)", listOf(42).toDebugString(20))
        // 配额为 1，首元素 42 截断为 "4"
        assertEquals("Collection(size=1)[4]", listOf(42).toDebugString(21))
        assertEquals("Collection(size=1)[42]", listOf(42).toDebugString(22))
    }

    // ---------- DebugLogUtils.formatDuration ----------

    @Test
    fun `formatDuration 毫秒段`() {
        assertEquals("0ms", DebugLogUtils.formatDuration(0))
        assertEquals("999ms", DebugLogUtils.formatDuration(999))
    }

    @Test
    fun `formatDuration 秒段含小数`() {
        assertEquals("1.0s", DebugLogUtils.formatDuration(1000))
        assertEquals("1.5s", DebugLogUtils.formatDuration(1500))
        assertEquals("59.999s", DebugLogUtils.formatDuration(59999))
    }

    @Test
    fun `formatDuration 分钟段`() {
        assertEquals("1m 0s", DebugLogUtils.formatDuration(60_000))
        assertEquals("2m 5s", DebugLogUtils.formatDuration(125_000))
        assertEquals("10m 1s", DebugLogUtils.formatDuration(601_000))
    }

    @Test
    fun `formatDuration null 输入返回 null`() {
        assertNull(DebugLogUtils.formatDuration(null))
    }

    // ---------- DebugLogUtils 时间格式化（时区无关的正则断言） ----------

    @Test
    fun `formatFullTime 输出完整日期时间格式`() {
        val result = DebugLogUtils.formatFullTime(1_700_000_000_000L)
        assertTrue(result, Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}").containsMatchIn(result))
    }

    @Test
    fun `formatShortTime 输出时分秒毫秒格式`() {
        val result = DebugLogUtils.formatShortTime(1_700_000_000_000L)
        assertTrue(result, Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3}").containsMatchIn(result))
    }
}