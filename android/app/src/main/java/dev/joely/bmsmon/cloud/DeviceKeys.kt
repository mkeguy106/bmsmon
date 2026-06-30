package dev.joely.bmsmon.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.UUID

private const val ALIAS = "bmsmon_device"
private const val KS = "AndroidKeyStore"

/** JWT assembly + hashing — pure, JVM-testable (no Keystore dependency). */
object Jwt {
    fun bodyHash(body: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body))

    fun signEs256(privateKey: PrivateKey, deviceId: String, body: ByteArray,
                  nowMs: Long, ttlSec: Long = 60): String {
        val claims = JWTClaimsSet.Builder()
            .subject(deviceId)
            .issueTime(Date((nowMs / 1000) * 1000))
            .expirationTime(Date(((nowMs / 1000) + ttlSec) * 1000))
            .jwtID(UUID.randomUUID().toString())
            .claim("bh", bodyHash(body))
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).build(), claims)
        // Nimbus ECDSASigner accepts a non-extractable PrivateKey (Keystore handle).
        jwt.sign(ECDSASigner(privateKey, Curve.P_256))
        return jwt.serialize()
    }
}

/** Android Keystore P-256 keypair for device auth. The private key never leaves the device. */
object DeviceKeys {
    private fun ks() = KeyStore.getInstance(KS).apply { load(null) }

    fun hasKey(): Boolean = ks().containsAlias(ALIAS)

    fun ensureKeyPair() {
        if (hasKey()) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KS)
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    fun privateKey(): PrivateKey = (ks().getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry).privateKey

    fun publicKey(): PublicKey = ks().getCertificate(ALIAS).publicKey

    fun publicKeySpkiB64(): String =
        Base64.getEncoder().encodeToString(publicKey().encoded) // X.509 SubjectPublicKeyInfo (SPKI)

    fun deleteKey() { if (hasKey()) ks().deleteEntry(ALIAS) }
}
