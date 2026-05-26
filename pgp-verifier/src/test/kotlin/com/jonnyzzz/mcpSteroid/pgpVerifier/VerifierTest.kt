package com.jonnyzzz.mcpSteroid.pgpVerifier

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VerifierTest {
    @Test
    fun `good-archive verifies cleanly`() {
        val result = Verifier.verify(
            archive = fixture("good-archive.bin"),
            signature = fixture("good-archive.bin.sig"),
            publicKeyBundle = fixture("test-pubkey.asc"),
        )

        assertSame(VerificationResult.Ok, result)
    }

    @Test
    fun `flipped-byte archive fails with InvalidSignature`() {
        val result = Verifier.verify(
            archive = fixture("bad-archive.bin"),
            signature = fixture("good-archive.bin.sig"),
            publicKeyBundle = fixture("test-pubkey.asc"),
        )

        val failure = assertIs<VerificationResult.InvalidSignature>(result)
        assertTrue(failure.message.contains("verification FAILED"), failure.message)
    }

    @Test
    fun `signature signed by unknown key fails with UnknownSigner`() {
        val result = Verifier.verify(
            archive = fixture("good-archive.bin"),
            signature = fixture("good-archive.bin.sig"),
            publicKeyBundle = fixture("unknown-signer-pubkey.asc"),
        )

        val failure = assertIs<VerificationResult.UnknownSigner>(result)
        assertTrue(failure.message.contains("NOT present"), failure.message)
    }
}
