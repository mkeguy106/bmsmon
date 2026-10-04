package dev.joely.bmsmon.cloud

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Every signed POST looks its key up first (DATA-17). No key sends nothing and holds the rows as
 * KeyMissing; a Keystore that throws is a hiccup, not proof the key is gone, so it is Transient.
 */
class DeviceKeyLookupTest {
    private val sent = PostOutcome(PostResult.Ok, code = 200, fromApi = true)

    @Test fun noKeySendsNothing() {
        var sends = 0
        val o = postWithDeviceKey({ null }) { sends++; sent }
        assertEquals(PostOutcome(PostResult.KeyMissing), o)
        assertEquals(0, sends)
    }

    @Test fun aKeystoreFailureIsTransientAndSendsNothing() {
        var sends = 0
        val o = postWithDeviceKey({ throw ProviderException("keystore busy") }) { sends++; sent }
        assertEquals(PostOutcome(PostResult.Transient), o)
        assertEquals(0, sends)
    }

    @Test fun aKeySignsAndSends() {
        val key: PrivateKey = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().private
        var used: PrivateKey? = null
        val o = postWithDeviceKey({ key }) { used = it; sent }
        assertSame(sent, o)
        assertSame(key, used)
    }
}
