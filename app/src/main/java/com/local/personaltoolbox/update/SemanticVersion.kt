package com.local.personaltoolbox.update

import java.math.BigInteger

class SemanticVersion private constructor(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        for ((left, right) in listOf(major to other.major, minor to other.minor, patch to other.patch)) {
            left.compareTo(right).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[i]
            val right = other.prerelease[i]
            val leftNumber = left.toBigIntegerOrNull()
            val rightNumber = right.toBigIntegerOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value.trim().removePrefix("v")) ?: return null
            val prerelease = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (prerelease.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return SemanticVersion(match.groupValues[1].toBigInteger(), match.groupValues[2].toBigInteger(),
                match.groupValues[3].toBigInteger(), prerelease)
        }
    }
}
