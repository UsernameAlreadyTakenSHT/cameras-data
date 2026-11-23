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
    fun aServerIsUnreachableOnlyAfterLastingFailures() {
        val run = TileHealth.FailureRun(minCount = 5, minMillis = 4000)
        // A burst of failures within a second, as when tiles time out together: not yet.
        repeat(10) { assertFalse(run.add(1000L + it * 100)) }
        // Still failing four seconds after the first one: now.
        assertTrue(run.add(5000L))
    }

    @Test
    fun aTileReceivedStartsCountingAgain() {
        val run = TileHealth.FailureRun(minCount = 5, minMillis = 4000)
        repeat(4) { run.add(1000L + it) }
        run.reset()
        // Few failures since the last tile, however late: no message.
        repeat(4) { assertFalse(run.add(9000L + it)) }
        assertTrue(run.add(13_000L))
    }

    @Test
    fun leavesRealServersAlone() {
        assertFalse(TileHealth.isBlockedAnswer(listOf(address("145.239.193.231"))))
        // One real address among blocked ones is enough to reach the server.
        assertFalse(TileHealth.isBlockedAnswer(listOf(address("0.0.0.0"), address("145.239.193.231"))))
        assertFalse(TileHealth.isBlockedAnswer(emptyList()))
    }
}
