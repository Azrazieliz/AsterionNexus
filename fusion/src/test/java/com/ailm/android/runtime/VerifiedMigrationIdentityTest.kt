package com.ailm.android.runtime

import org.junit.Assert.*
import org.junit.Test

class VerifiedMigrationIdentityTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun row(id: Long, sha: String, bytes: Long = 100) =
        VerifiedMigrationIdentity.record(id, sha, bytes)!!

    @Test fun installationLocalIdsNeverDetermineBindings() {
        assertEquals(mapOf(1L to 2L, 2L to 1L), VerifiedMigrationIdentity.uniqueBindings(
            listOf(row(1,a),row(2,b)),listOf(row(1,b),row(2,a))))
    }

    @Test fun ambiguousAndChangedFilesAreHeld() {
        assertTrue(VerifiedMigrationIdentity.uniqueBindings(listOf(row(1,a)),
            listOf(row(2,a),row(3,a))).isEmpty())
        assertTrue(VerifiedMigrationIdentity.uniqueBindings(listOf(row(1,a),row(2,a)),
            listOf(row(3,a))).isEmpty())
        assertTrue(VerifiedMigrationIdentity.uniqueBindings(listOf(row(1,a)),
            listOf(row(1,a,101))).isEmpty())
        assertNull(VerifiedMigrationIdentity.record(1,"",100))
        assertNull(VerifiedMigrationIdentity.record(1,a,0))
    }
}
