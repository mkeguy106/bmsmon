package dev.joely.bmsmon.cloud

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class JwtSignerTest {
    private fun kp() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test fun bodyHash_is_unpadded_base64url_sha256() {
        val body = """{"x":1}""".toByteArray()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(body))
        assertEquals(expected, Jwt.bodyHash(body))
    }

    @Test fun signed_jwt_verifies_and_binds_body() {
        val pair = kp()
        val body = """{"batch_seq":1,"samples":[]}""".toByteArray()
        val token = Jwt.signEs256(pair.private, "dev-123", body, nowMs = 1_000_000L)
        val jwt = SignedJWT.parse(token)
        assertEquals(JWSAlgorithm.ES256, jwt.header.algorithm)
        assertTrue(jwt.verify(ECDSAVerifier(pair.public as java.security.interfaces.ECPublicKey)))
        val c = jwt.jwtClaimsSet
        assertEquals("dev-123", c.subject)
        assertEquals(Jwt.bodyHash(body), c.getStringClaim("bh"))
        assertEquals(1000L, c.issueTime.time / 1000)         // iat seconds
        assertEquals(1060L, c.expirationTime.time / 1000)    // exp = iat + 60
        assertTrue(c.jwtid != null && c.jwtid.isNotEmpty())
    }
}
