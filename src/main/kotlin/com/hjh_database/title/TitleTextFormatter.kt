package com.hjh_database.title

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.math.roundToInt

object TitleTextFormatter {
    private val gradientPattern = Regex(
        "(?is)<gradient:(#[0-9a-fA-F]{6}):(#[0-9a-fA-F]{6})>(.*?)</gradient>"
    )
    private val expandedAmpersandHexPattern = Regex("(?i)&x(?:&[0-9a-f]){6}")
    private val ampersandHexPattern = Regex("&#([0-9a-fA-F]{6})")
    private val legacyCodePattern = Regex("&([0-9a-fk-or])", RegexOption.IGNORE_CASE)
    private val trailingFormatPattern = Regex("(?i)(?:&[0-9a-fk-or]|&#[0-9a-f]{6})$")
    private val legacy = LegacyComponentSerializer.legacySection()
    private val plain = PlainTextComponentSerializer.plainText()

    fun component(input: String): Component {
        var result = Component.empty()
        var cursor = 0
        for (match in gradientPattern.findAll(input)) {
            if (match.range.first > cursor) {
                result = result.append(legacyComponent(input.substring(cursor, match.range.first)))
            }
            result = result.append(
                gradientComponent(match.groupValues[3], match.groupValues[1], match.groupValues[2])
            )
            cursor = match.range.last + 1
        }
        if (cursor < input.length) result = result.append(legacyComponent(input.substring(cursor)))
        return result
    }

    fun plainText(input: String): String = plain.serialize(component(input))

    fun visibleLength(input: String): Int {
        val visible = plainText(input)
        return visible.codePointCount(0, visible.length)
    }

    /**
     * 自定义称号只解析颜色/样式和固定格式的渐变标签，不解析点击、悬浮或命令事件。
     * 这里同时拒绝可能在旧版序列化链路中污染后续文本的悬空控制码。
     */
    fun validateCustomInput(input: String, maxVisibleLength: Int): String? {
        if (input.isEmpty()) return "称号不能为空"
        if (input.length > 256) return "称号原始输入过长"
        if (input == "&") return "称号不能只包含 & 符号"
        if (input.endsWith('&')) return "称号不能以 & 符号结尾"
        if ('§' in input) return "请使用 & 颜色代码，不能直接输入 § 符号"
        if (trailingFormatPattern.containsMatchIn(input)) return "称号不能以悬空的颜色或格式代码结尾"

        val codePoints = input.codePoints().toArray()
        if (codePoints.any { Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() }) {
            return "称号不能包含控制字符、零宽字符或双向文本控制符"
        }

        val gradientMatches = gradientPattern.findAll(input).toList()
        var unmatchedText = input
        for (match in gradientMatches.asReversed()) {
            if (containsGradientMarker(match.groupValues[3])) return "渐变标签不能嵌套"
            unmatchedText = unmatchedText.removeRange(match.range)
        }
        if (containsGradientMarker(unmatchedText)) {
            return "渐变格式不完整，应使用 <gradient:#RRGGBB:#RRGGBB>文字</gradient>"
        }

        val visible = plainText(input).trim()
        if (visible.isEmpty()) return "称号不能只包含颜色或格式代码"
        val visibleLength = visible.codePointCount(0, visible.length)
        if (visibleLength > maxVisibleLength) return "称号最多只能有 $maxVisibleLength 个字"
        return null
    }

    private fun legacyComponent(input: String): Component {
        val withExpandedHex = expandedAmpersandHexPattern.replace(input) { match ->
            val hex = match.value.drop(2).replace("&", "")
            buildSectionHex(hex)
        }
        val withSectionHex = ampersandHexPattern.replace(withExpandedHex) { match ->
            buildSectionHex(match.groupValues[1])
        }
        val withLegacyCodes = legacyCodePattern.replace(withSectionHex) { "§${it.groupValues[1]}" }
        return legacy.deserialize(withLegacyCodes)
    }

    private fun buildSectionHex(hex: String): String =
        buildString {
            append('§').append('x')
            for (char in hex) {
                append('§').append(char)
            }
        }

    private fun gradientComponent(rawText: String, startHex: String, endHex: String): Component {
        val text = plain.serialize(legacyComponent(rawText))
        val codePoints = text.codePoints().toArray()
        if (codePoints.isEmpty()) return Component.empty()

        val start = rgb(startHex)
        val end = rgb(endHex)
        var result = Component.empty()
        val denominator = (codePoints.size - 1).coerceAtLeast(1)
        for ((index, codePoint) in codePoints.withIndex()) {
            val ratio = index.toDouble() / denominator
            val red = (start.first + (end.first - start.first) * ratio).roundToInt()
            val green = (start.second + (end.second - start.second) * ratio).roundToInt()
            val blue = (start.third + (end.third - start.third) * ratio).roundToInt()
            result = result.append(
                Component.text(String(Character.toChars(codePoint)), TextColor.color(red, green, blue))
            )
        }
        return result
    }

    private fun rgb(hex: String): Rgb {
        val value = hex.removePrefix("#").toInt(16)
        return Rgb((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
    }

    private fun containsGradientMarker(text: String): Boolean {
        val lowered = text.lowercase()
        return "<gradient" in lowered || "</gradient" in lowered
    }

    private data class Rgb(val first: Int, val second: Int, val third: Int)
}
