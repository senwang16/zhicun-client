package com.zhicun.app.domain.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 向量数学与序列化（M12，纯函数）。
 *
 * 这里钉的都是"混入坏数据时不能把整个语义检索搞崩"的边界：
 * 维度不符、全零向量、NaN/Infinity、坏 Base64、超长向量。
 */
class VectorMathTest {

    private val delta = 1e-6

    @Test
    fun Cosine_sameVector_isOne() {
        val v = floatArrayOf(0.3f, -0.5f, 0.8f)

        assertEquals(1.0, VectorMath.cosine(v, v.copyOf()), delta)
    }

    @Test
    fun Cosine_orthogonal_isZero() {
        assertEquals(
            0.0,
            VectorMath.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)),
            delta,
        )
    }

    @Test
    fun Cosine_opposite_isMinusOne() {
        assertEquals(
            -1.0,
            VectorMath.cosine(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)),
            delta,
        )
    }

    @Test
    fun Cosine_ignoresMagnitude() {
        // 余弦只看方向：(1,1) 与 (100,100) 完全同向
        assertEquals(
            1.0,
            VectorMath.cosine(floatArrayOf(1f, 1f), floatArrayOf(100f, 100f)),
            delta,
        )
    }

    @Test
    fun Cosine_dimensionMismatch_isZero() {
        // 返回 0 而不是抛异常：坏向量只该"不参与排序"，不该让整个搜索失败
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f)), delta)
    }

    @Test
    fun Cosine_emptyVector_isZero() {
        assertEquals(0.0, VectorMath.cosine(FloatArray(0), floatArrayOf(1f)), delta)
    }

    @Test
    fun Cosine_zeroVector_isZero() {
        // 全零向量没有方向，除以 0 会得到 NaN；必须兜成 0，否则排序里会出现"NaN 排最前"
        assertEquals(0.0, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), delta)
    }

    @Test
    fun Cosine_nonFiniteValues_areZero() {
        // 网关偶尔会返回 NaN / Infinity（模型输出异常），不能让它污染排序
        val withNaN = floatArrayOf(Float.NaN, 1f)
        val withInf = floatArrayOf(Float.POSITIVE_INFINITY, 1f)

        assertEquals(0.0, VectorMath.cosine(withNaN, floatArrayOf(1f, 1f)), delta)
        assertEquals(0.0, VectorMath.cosine(withInf, floatArrayOf(1f, 1f)), delta)
    }

    @Test
    fun EncodeDecode_roundTripsExactly() {
        val original = floatArrayOf(0.0f, -1.5f, 3.14159f, 1e-8f, -2e7f)

        val decoded = VectorMath.decode(VectorMath.encode(original))

        assertArrayEquals(original, decoded, 0f)
    }

    @Test
    fun Encode_producesBase64OfFourBytesPerDimension() {
        // 3 维 → 12 字节 → Base64 16 个字符（含一个 '=' 填充）
        val encoded = VectorMath.encode(floatArrayOf(0f, 0f, 0f))

        assertEquals(16, encoded.length)
        assertEquals(12, java.util.Base64.getDecoder().decode(encoded).size)
    }

    @Test
    fun Decode_blankOrNull_isNull() {
        assertNull(VectorMath.decode(null))
        assertNull(VectorMath.decode(""))
        assertNull(VectorMath.decode("   "))
    }

    @Test
    fun Decode_invalidBase64_isNull() {
        assertNull(VectorMath.decode("这不是 base64"))
    }

    @Test
    fun Decode_byteLengthNotMultipleOfFour_isNull() {
        // 3 字节无法切成 float32，属于坏行
        val threeBytes = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))

        assertNull(VectorMath.decode(threeBytes))
    }

    @Test
    fun Decode_emptyPayload_isNull() {
        assertNull(VectorMath.decode(java.util.Base64.getEncoder().encodeToString(ByteArray(0))))
    }

    @Test
    fun Decode_oversizedVector_isRejected() {
        // 脏数据上限：超过 MAX_DIMENSIONS 直接拒绝，避免一次性吃掉几百 MB
        val tooLong = FloatArray(VectorMath.MAX_DIMENSIONS + 1)
        val encoded = VectorMath.encode(tooLong)

        assertNull(VectorMath.decode(encoded))
    }

    @Test
    fun Decode_atMaxDimensions_isAccepted() {
        val atLimit = FloatArray(VectorMath.MAX_DIMENSIONS) { 0.5f }

        val decoded = VectorMath.decode(VectorMath.encode(atLimit))

        assertEquals(VectorMath.MAX_DIMENSIONS, decoded?.size)
        assertTrue(decoded!![0] == 0.5f)
    }
}
