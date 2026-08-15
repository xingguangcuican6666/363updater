package com.github.fanziyun.updater.util

import java.math.BigInteger

object SemVer {
    fun compare(leftValue: String, rightValue: String): Int {
        val left = parse(leftValue)
        val right = parse(rightValue)
        if (left.valid != right.valid) return left.valid.compareTo(right.valid)
        if (!left.valid) {
            val insensitive = left.original.compareTo(right.original, ignoreCase = true)
            return if (insensitive != 0) insensitive else left.original.compareTo(right.original)
        }
        for (index in 0 until maxOf(left.core.size, right.core.size)) {
            val result = left.core.getOrElse(index) { BigInteger.ZERO }
                .compareTo(right.core.getOrElse(index) { BigInteger.ZERO })
            if (result != 0) return result
        }
        if (left.preRelease.isEmpty() || right.preRelease.isEmpty()) {
            return left.preRelease.isEmpty().compareTo(right.preRelease.isEmpty())
        }
        for (index in 0 until minOf(left.preRelease.size, right.preRelease.size)) {
            val result = compareIdentifier(left.preRelease[index], right.preRelease[index])
            if (result != 0) return result
        }
        return left.preRelease.size.compareTo(right.preRelease.size)
    }

    private data class Parsed(
        val original: String,
        val core: List<BigInteger>,
        val preRelease: List<String>,
        val valid: Boolean,
    )

    private fun parse(value: String): Parsed {
        val original = value.trim()
        val normalized = original.removeVersionPrefix().substringBefore('+')
        val coreParts = normalized.substringBefore('-').split('.')
        val core = coreParts.map { it.takeWhile(Char::isDigit).toBigIntegerOrNull() }
        val valid = original.isNotEmpty() && core.isNotEmpty() && core.all { it != null }
        val preRelease = normalized.substringAfter('-', "").split('.').filter(String::isNotEmpty)
        return Parsed(original, core.filterNotNull(), preRelease, valid)
    }

    private fun compareIdentifier(left: String, right: String): Int {
        val leftNumber = left.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        val rightNumber = right.takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right)
        }
    }

    private fun String.removeVersionPrefix(): String =
        if (length > 1 && (first() == 'v' || first() == 'V') && this[1].isDigit()) substring(1) else this
}
