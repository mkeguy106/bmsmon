package dev.joely.bmsmon

import dev.joely.bmsmon.cloud.gzip
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionTest {
    @Test fun gzipRoundTrips() {
        val original = ("""{"batch_seq":7,"samples":[""" +
            (1..50).joinToString(",") {
                """{"ts_ms":1719686400000,"address":"C8:47:80:15:67:44","alias":"2012 · A","soc":87.0}"""
            } + "]}").toByteArray()
        val restored = GZIPInputStream(gzip(original).inputStream()).readBytes()
        assertEquals(original.toList(), restored.toList())  // fully flushed, not truncated
    }

    @Test fun gzipShrinksRepetitiveJson() {
        val body = ("""{"address":"C8:47:80:15:67:44","alias":"2012 · A"}""".repeat(100)).toByteArray()
        assertTrue("gzip should shrink repetitive JSON", gzip(body).size < body.size / 2)
    }

    @Test fun gzipHandlesEmpty() {
        assertEquals(0, GZIPInputStream(gzip(ByteArray(0)).inputStream()).readBytes().size)
    }
}
