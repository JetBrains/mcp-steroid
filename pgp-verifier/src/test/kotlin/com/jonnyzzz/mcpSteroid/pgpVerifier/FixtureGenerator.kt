package com.jonnyzzz.mcpSteroid.pgpVerifier

import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.openpgp.PGPEncryptedData
import org.bouncycastle.openpgp.PGPKeyRingGenerator
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKeyRing
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Security
import java.util.Date

/**
 * Regenerates checked-in PGP verifier fixtures.
 *
 * Recipe:
 * `PGP_VERIFIER_REGENERATE_FIXTURES=true ./gradlew :pgp-verifier:test --tests '*FixtureGeneratorTest*'`
 *
 * The generated resources are committed under `src/test/resources/fixtures/`. The signing keys are created
 * in-memory for regeneration only; no secret key material is written to the repository.
 */
object FixtureGenerator {
    fun regenerate(outputDir: Path) {
        ensureBouncyCastleProvider()
        Files.createDirectories(outputDir)

        val payload = goodArchiveBytes()
        Files.write(outputDir.resolve("good-archive.bin"), payload)

        val badPayload = payload.clone()
        badPayload[127] = (badPayload[127].toInt() xor 0x40).toByte()
        Files.write(outputDir.resolve("bad-archive.bin"), badPayload)

        val signingKey = generateKeyRing(
            userId = "pgp-verifier fixture signing key <fixture@example.test>",
            random = seededRandom("pgp-verifier signing key"),
        )
        writePublicKey(signingKey.publicKeyRing, outputDir.resolve("test-pubkey.asc"))
        writeDetachedSignature(payload, signingKey.secretKeyRing, outputDir.resolve("good-archive.bin.sig"))

        val unknownKey = generateKeyRing(
            userId = "pgp-verifier fixture unknown key <unknown@example.test>",
            random = seededRandom("pgp-verifier unknown key"),
        )
        writePublicKey(unknownKey.publicKeyRing, outputDir.resolve("unknown-signer-pubkey.asc"))
    }

    private fun goodArchiveBytes(): ByteArray = ByteArray(512) { index ->
        ((index * 31 + 17) and 0xff).toByte()
    }

    private fun generateKeyRing(userId: String, random: SecureRandom): GeneratedKeyRing {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        keyPairGenerator.initialize(2048, random)
        val pgpKeyPair = JcaPGPKeyPair(
            PGPPublicKey.RSA_SIGN,
            keyPairGenerator.generateKeyPair(),
            Date(0),
        )
        val digestCalculator = JcaPGPDigestCalculatorProviderBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build()
            .get(HashAlgorithmTags.SHA1)
        val contentSignerBuilder = JcaPGPContentSignerBuilder(
            pgpKeyPair.publicKey.algorithm,
            HashAlgorithmTags.SHA256,
        ).setProvider(BouncyCastleProvider.PROVIDER_NAME)
        val secretKeyEncryptor = JcePBESecretKeyEncryptorBuilder(
            PGPEncryptedData.AES_256,
            digestCalculator,
        )
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .setSecureRandom(random)
            .build(EMPTY_PASSPHRASE)
        val keyRingGenerator = PGPKeyRingGenerator(
            PGPSignature.POSITIVE_CERTIFICATION,
            pgpKeyPair,
            userId,
            digestCalculator,
            null,
            null,
            contentSignerBuilder,
            secretKeyEncryptor,
        )
        return GeneratedKeyRing(
            publicKeyRing = keyRingGenerator.generatePublicKeyRing(),
            secretKeyRing = keyRingGenerator.generateSecretKeyRing(),
        )
    }

    private fun writePublicKey(publicKeyRing: PGPPublicKeyRing, path: Path) {
        ArmoredOutputStream(Files.newOutputStream(path)).use { output ->
            publicKeyRing.encode(output)
        }
    }

    private fun writeDetachedSignature(payload: ByteArray, secretKeyRing: PGPSecretKeyRing, path: Path) {
        val secretKey = secretKeyRing.secretKey
        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(EMPTY_PASSPHRASE)
        )
        val signatureGenerator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
        )
        signatureGenerator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        signatureGenerator.update(payload, 0, payload.size)
        ArmoredOutputStream(Files.newOutputStream(path)).use { output ->
            signatureGenerator.generate().encode(output)
        }
    }

    private fun seededRandom(seed: String): SecureRandom {
        return SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed.toByteArray(Charsets.UTF_8))
        }
    }

    private fun ensureBouncyCastleProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private data class GeneratedKeyRing(
        val publicKeyRing: PGPPublicKeyRing,
        val secretKeyRing: PGPSecretKeyRing,
    )

    private val EMPTY_PASSPHRASE = CharArray(0)
}
