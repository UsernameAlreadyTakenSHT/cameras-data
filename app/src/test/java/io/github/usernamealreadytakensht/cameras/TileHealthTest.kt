package io.github.usernamealreadytakensht.cameras

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileHealthTest {
    private fun address(text: String) = InetAddress.getByName(text)

    @Test
    fun recognisesTheAnswersOfADnsFilter() {
        // What the Pixel received from its filter: the phone itself, or "nowhere".
        assertTrue(TileHealth.isBlockedAnswer(listOf(address("127.0.0.1"))))
        assertTrue(TileHealth.isBlockedAnswer(listOf(address("0.0.0.0"))))
        assertTrue(TileHealth.isBlockedAnswer(listOf(address("::1"), address("0.0.0.0"))))
    }

    @Test
    fun leavesRealServersAlone() {
        assertFalse(TileHealth.isBlockedAnswer(listOf(address("145.239.193.231"))))
        // One real address among blocked ones is enough to reach the server.
        assertFalse(TileHealth.isBlockedAnswer(listOf(address("0.0.0.0"), address("145.239.193.231"))))
        assertFalse(TileHealth.isBlockedAnswer(emptyList()))
    }
}
