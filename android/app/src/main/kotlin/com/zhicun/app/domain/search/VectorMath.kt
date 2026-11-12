package com.zhicun.app.domain.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * 向量的相似度与序列化（M12，纯函数，无 Android 依赖）。
 *
 * ## 为什么存 Base64 而不是逗号分隔的浮点数
 * 一条记录一次检索要解码**全部**向量：逗号串需要逐个 `toFloat()` 解析（1024 维就是
 * 1024 次字符串解析），Base64 只是把字节搬出来再转成浮点。体积上 Base64 是 4/3 倍，
 * 逗号串约 10 倍（`-0.01234567,` 这样的文本）。个人知识库上万条后这点差异就是
 * 每次搜索多几百毫秒。
 */
object VectorMath {

    /** 单条向量的维度上限：防止脏数据（或写坏的库）让解码直接吃掉几百 MB 内存。 */
    const val MAX_DIMENSIONS: Int = 8_192

    /**
     * 相似度下限：低于该余弦值视为"不相关"，不进入任何结果（M12 检索 / M18 相关推荐共用）。
     *
     * 余弦对任意两段文本都给得出一个数，哪怕是毫不相关的两条也能有 0.2~0.35。
     * 不做截断的话，搜任何词都会返回"全部记录"，召回率看起来 100%，
     * 实际上把真正相关的那几条稀释掉了。
     *
     * 0.30 是**保守取值**（宁可少召回，也不要凑数）：它对 OpenAI 系与多数开源
     * 中文 embedding 模型都落在"弱相关以上"。换模型后应当重新标定 ——
     * 它放在这里（domain 纯函数常量）而不是散落在实现里，就是为了让"换模型要重标"
     * 这件事只有一个落点。
     */
    const val MIN_SIMILARITY: Double = 0.30

    /**
     * 余弦相似度，取值 [-1, 1]；**任何异常情形一律返回 0.0**。
     *
     * 返回 0 而不是抛异常的取舍：检索时混入一条坏向量（维度不符、全零、含 NaN）
     * 应该只是"这条记录不参与排序"，不该让整个搜索失败。
     */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.size != b.size) return 0.0

        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0

        val similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB))
        // NaN/Inf 只可能来自输入里的脏值（Infinity），同样按"无相似度"处理
        return if (similarity.isFinite()) similarity.coerceIn(-1.0, 1.0) else 0.0
    }

    /** 向量 → Base64（小端 float32，每维 4 字节）。 */
    fun encode(vector: FloatArray): String {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    /**
     * Base64 → 向量；无法解析时返回 `null`。
     *
     * 返回 `null` 而不是空数组：调用方需要区分"这一行坏了"（值得记一条日志或将来清理）
     * 与"网关没给向量"（正常情况）。
     */
    fun decode(encoded: String?): FloatArray? {
        if (encoded.isNullOrBlank()) return null
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null

        val dimensions = bytes.size / Float.SIZE_BYTES
        if (dimensions > MAX_DIMENSIONS) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float }
    }
}
