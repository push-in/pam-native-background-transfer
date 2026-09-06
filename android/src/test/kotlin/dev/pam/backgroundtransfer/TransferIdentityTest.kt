package dev.pam.backgroundtransfer

import org.junit.Assert.*
import org.junit.Test

class TransferIdentityTest {
    @Test fun queuedUploadKeepsItsDirectionWithoutProgress() {
        assertEquals(2, TransferIdentity.kind(setOf(TransferIdentity.tag(2)), 0))
        assertEquals(1, TransferIdentity.kind(setOf(TransferIdentity.tag(1)), 0))
    }
    @Test fun oldTransfersRequireKnownOutput() {
        assertEquals(2, TransferIdentity.kind(emptySet(), 2))
        try {
            TransferIdentity.kind(emptySet(), 0)
            fail("Unknown kind was guessed")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun conflictingIdentityIsRejected() {
        try {
            TransferIdentity.kind(setOf(TransferIdentity.tag(1), TransferIdentity.tag(2)), 2)
            fail("Conflicting identity accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
