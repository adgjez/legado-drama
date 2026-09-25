package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ImageProcessUtils.calculateSampleSize 纯函数测试。
 * 场景覆盖：目标大于源（无需缩放）、等比缩小到各档位、宽高约束取最小值。
 */
class ImageProcessUtilsTest {

    @Test
    fun `目标尺寸大于源图尺寸不缩放`() {
        assertEquals(1, ImageProcessUtils.calculateSampleSize(100, 100, 800, 800))
    }

    @Test
    fun `恰好缩小一半`() {
        assertEquals(2, ImageProcessUtils.calculateSampleSize(800, 600, 400, 300))
    }

    @Test
    fun `宽高约束分别限制采样倍数`() {
        // 宽可到 4 倍，但高第 3 轮（1000）已不满足 1024
        assertEquals(4, ImageProcessUtils.calculateSampleSize(8000, 6000, 1024, 1024))
    }

    @Test
    fun `横图先到宽度约束`() {
        assertEquals(2, ImageProcessUtils.calculateSampleSize(4000, 3000, 1000, 1000))
    }

    @Test
    fun `竖图先到高度约束`() {
        assertEquals(1, ImageProcessUtils.calculateSampleSize(3000, 4000, 2000, 2000))
    }

    @Test
    fun `宽高比值无关采样倍数只受较小维约束`() {
        assertEquals(2, ImageProcessUtils.calculateSampleSize(1080, 1920, 400, 600))
    }

    @Test
    fun `大图多档位采样`() {
        assertEquals(8, ImageProcessUtils.calculateSampleSize(16000, 12000, 1000, 1000))
    }
}