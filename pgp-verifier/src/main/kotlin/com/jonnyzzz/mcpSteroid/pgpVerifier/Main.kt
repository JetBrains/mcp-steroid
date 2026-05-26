package com.jonnyzzz.mcpSteroid.pgpVerifier

import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

sealed interface VerificationResult {
    data object Ok : VerificationResult

    sealed interface Failure : VerificationResult {
        val message: String
    }

    data class UnknownSigner(override val message: String) : Failure
    data class InvalidSignature(override val message: String) : Failure
    data class MalformedSignature(override val message: String) : Failure
}

private sealed class VerificationFailureException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private class UnknownSignerException(message: String) : VerificationFailureException(message)
private class InvalidSignatureException(message: String) : VerificationFailureException(message)
private class MalformedSignatureException(message: String, cause: Throwable? = null) :
    VerificationFailureException(message, cause)

object Verifier {
    fun verify(archive: File, signature: File, publicKeyBundle: File): VerificationResult {
        return try {
            verifyDetachedPgpSignature(archive, signature, publicKeyBundle)
            VerificationResult.Ok
        } catch (e: UnknownSignerException) {
            VerificationResult.UnknownSigner(e.message)
        } catch (e: InvalidSignatureException) {
            VerificationResult.InvalidSignature(e.message)
        } catch (e: MalformedSignatureException) {
            VerificationResult.MalformedSignature(e.message)
        } catch (e: Exception) {
            VerificationResult.MalformedSignature(
                "Malformed PGP signature/key data while verifying ${archive.name}: " +
                    (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun pgpKeyIdHex(keyId: Long): String = keyId.toULong().toString(16).uppercase().padStart(16, '0')

    private fun verifyDetachedPgpSignature(archive: File, signature: File, publicKeyBundle: File) {
        val rings = PGPUtil.getDecoderStream(publicKeyBundle.inputStream()).use { stream ->
            PGPPublicKeyRingCollection(stream, BcKeyFingerprintCalculator())
        }

        val sig: PGPSignature = PGPUtil.getDecoderStream(signature.inputStream()).use { stream ->
            val factory = PGPObjectFactory(stream, BcKeyFingerprintCalculator())
            val first = factory.nextObject()
            val sigList = when (first) {
                is PGPSignatureList -> first
                is PGPCompressedData -> PGPObjectFactory(
                    first.dataStream,
                    BcKeyFingerprintCalculator(),
                ).nextObject() as PGPSignatureList

                else -> throw MalformedSignatureException(
                    "Unexpected packet in signature file ${signature.name}: " +
                        (first?.javaClass?.simpleName ?: "null")
                )
            }
            if (sigList.size() == 0) {
                throw MalformedSignatureException("Signature file ${signature.name} contains no signature packets")
            }
            sigList[0]
        }

        val keyId = pgpKeyIdHex(sig.keyID)
        val key = rings.getPublicKey(sig.keyID)
            ?: throw UnknownSignerException(
                "Signature on ${archive.name} was made with key id 0x$keyId, " +
                    "which is NOT present in the bundled Corretto public key ring (${publicKeyBundle.name}). " +
                    "Refusing to extract — possible tampered archive or untrusted signer."
            )

        sig.init(BcPGPContentVerifierBuilderProvider(), key)
        BufferedInputStream(archive.inputStream()).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sig.update(buffer, 0, read)
            }
        }
        if (!sig.verify()) {
            throw InvalidSignatureException(
                "PGP signature verification FAILED for ${archive.name} " +
                    "(signed by key id 0x$keyId, key in ${publicKeyBundle.name}). " +
                    "Archive may be corrupted or tampered."
            )
        }
    }
}

internal fun runCli(args: Array<String>, stderr: PrintStream = System.err): Int {
    if (args.size != 3) {
        stderr.println("[pgp-verifier] Usage: pgp-verifier <archive> <signature> <public-key>")
        return 2
    }

    val archive = File(args[0])
    val signature = File(args[1])
    val publicKey = File(args[2])
    listOf(
        "archive" to archive,
        "signature" to signature,
        "public key" to publicKey,
    ).firstOrNull { (_, file) -> !file.isFile }?.let { (label, file) ->
        stderr.println("[pgp-verifier] Usage error: $label file does not exist: ${file.absolutePath}")
        return 2
    }

    return when (val result = Verifier.verify(archive, signature, publicKey)) {
        VerificationResult.Ok -> 0
        is VerificationResult.Failure -> {
            stderr.println("[pgp-verifier] ${result.message}")
            3
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}
