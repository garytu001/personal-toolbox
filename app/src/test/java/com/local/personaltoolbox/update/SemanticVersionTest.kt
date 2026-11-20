package com.local.personaltoolbox.update

import org.junit.Assert.*
import org.junit.Test

class SemanticVersionTest {
    private fun version(value: String) = requireNotNull(SemanticVersion.parse(value))

    @Test fun comparesNumericComponentsRatherThanStrings() {
        assertTrue(version("1.10.0") > version("1.9.9"))
        assertTrue(version("2.0.0") > version("1.999.999"))
        assertTrue(version("1.0.1") > version("1.0.0"))
        assertEquals(0, version("v1.0.0").compareTo(version("1.0.0")))
    }

    @Test fun followsSemverPrereleaseOrdering() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (left, right) -> assertTrue("$left < $right", version(left) < version(right)) }
    }

    @Test fun ignoresBuildMetadataForPrecedence() {
        assertEquals(0, version("1.0.0+build.12").compareTo(version("1.0.0+other")))
    }

    @Test fun rejectsMalformedOrAmbiguousVersions() {
        listOf("", "1.0", "1.0.0.1", "01.0.0", "1.0.0-01", "1.0.0-", "release-1.0.0", "1.0.0+", "1.0.-1")
            .forEach { assertNull(it, SemanticVersion.parse(it)) }
    }

    @Test fun handlesLargeNumericComponentsWithoutOverflow() {
        assertTrue(version("999999999999999999999999.0.0") > version("2147483647.0.0"))
        assertTrue(version("1.0.0-999999999999999999999999") > version("1.0.0-999999999999999999999998"))
    }
}
