package com.earbook.app.book

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 中文 TXT 常见编码探测：BOM > UTF-8 严格解码 > GB18030 兜底。
 * GB18030 向下兼容 GBK / GB2312，是简体中文小说的事实兜底编码。
 */
object CharsetDetector {

    fun detect(bytes: ByteArray): Charset = detect(bytes, bytes.size)

    /**
     * 基于采样字节探测编码。
     * @param length 实际有效字节数（样本可能截断于多字节字符中间）
     */
    fun detect(bytes: ByteArray, length: Int): Charset {
        if (length >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Charsets.UTF_8

        if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Charsets.UTF_16LE
        if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return Charsets.UTF_16BE

        // 尝试 UTF-8 严格解码：绝大多数合法 UTF-8 文本能通过。
        // 样本可能在多字节字符中间被截断——回退到最后一个字符边界再校验。
        if (isStrictUtf8(bytes, trimToUtf8Boundary(bytes, length))) return Charsets.UTF_8

        return charset("GB18030")
    }

    /** 返回 [0, p) 为完整 UTF-8 字符序列的截断点 p */
    private fun trimToUtf8Boundary(bytes: ByteArray, length: Int): Int {
        var p = length
        // 先跳过末尾的 continuation 字节（10xxxxxx）
        while (p > 0 && (bytes[p - 1].toInt() and 0xC0) == 0x80) p--
        // 再排除指向残缺多字节字符的 lead 字节（其后缺 continuation，必为非法）
        if (p > 0 && (bytes[p - 1].toInt() and 0x80) != 0) p--
        return p
    }

    private fun isStrictUtf8(bytes: ByteArray, length: Int): Boolean = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
        true
    } catch (_: Exception) {
        false
    }
}
