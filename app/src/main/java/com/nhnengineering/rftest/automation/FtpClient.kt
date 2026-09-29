package com.nhnengineering.rftest.automation

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

data class FtpConfig(
    val host: String,
    val port: Int = 21,
    val user: String = "anonymous",
    val pass: String = "anonymous@",
    val remoteFileName: String = "rftest_upload.bin",
    /** Random bytes, not zeroes -- a compressing middlebox would squash a block of zeroes and
     *  report a throughput the link cannot actually deliver, the same reasoning SpeedTester's
     *  upload uses. */
    val bytes: Int = 2_000_000,
    val connectTimeoutMs: Int = 8_000,
)

/**
 * A minimal FTP client covering exactly the one operation a throughput probe needs: authenticate,
 * switch to passive binary mode, and `STOR` a buffer of random bytes, timing only the data transfer.
 *
 * Not a general FTP client: no listing, no download, no active mode, and no explicit-TLS (`AUTH
 * TLS`) support. RFC 959's full command set is a much bigger surface than this app needs, and a
 * narrow implementation of the one path this app actually exercises is safer than a broader one that
 * is only ever tested by this same code.
 */
class FtpClient(private val config: FtpConfig) {

    class FtpError(message: String) : Exception(message)

    /** Uploads [FtpConfig.bytes] random bytes and returns the transfer's own throughput in Mbps. */
    fun upload(): Double {
        Socket().use { control ->
            control.connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
            val reader = BufferedReader(InputStreamReader(control.getInputStream()))
            val writer = control.getOutputStream()

            expect(readReply(reader), 220)
            command(writer, reader, "USER ${config.user}", setOf(230, 331))
            command(writer, reader, "PASS ${config.pass}", setOf(230))
            command(writer, reader, "TYPE I", setOf(200))
            val pasvReply = command(writer, reader, "PASV", setOf(227))
            val address = parsePasvReply(pasvReply)
                ?: throw FtpError("Could not parse the server's PASV reply: $pasvReply")

            val payload = ByteArray(config.bytes).also { Random.nextBytes(it) }
            val start: Long
            val end: Long
            Socket().use { data ->
                data.connect(InetSocketAddress(address.first, address.second), config.connectTimeoutMs)
                command(writer, reader, "STOR ${config.remoteFileName}", setOf(150, 125))
                start = System.currentTimeMillis()
                data.getOutputStream().use { it.write(payload) }
                end = System.currentTimeMillis()
            }
            expect(readReply(reader), 226)
            runCatching { command(writer, reader, "QUIT", setOf(221)) }

            val millis = (end - start).coerceAtLeast(1)
            return (payload.size * 8.0) / (millis / 1000.0) / 1_000_000.0
        }
    }

    private fun expect(reply: String, code: Int) {
        if (reply.take(3).toIntOrNull() != code) throw FtpError("Expected $code, got: $reply")
    }

    private fun command(writer: OutputStream, reader: BufferedReader, cmd: String, expectAnyOf: Set<Int>): String {
        writer.write("$cmd\r\n".toByteArray(Charsets.US_ASCII))
        writer.flush()
        val reply = readReply(reader)
        if (reply.take(3).toIntOrNull() !in expectAnyOf) {
            throw FtpError("'$cmd' expected one of $expectAnyOf, got: $reply")
        }
        return reply
    }

    /**
     * Reads one full reply, following RFC 959's multi-line shape (`230-text` ... `230 text`) to its
     * terminating line rather than stopping at the first line-break a banner or a verbose server
     * happens to contain.
     */
    private fun readReply(reader: BufferedReader): String {
        val first = reader.readLine() ?: throw FtpError("Connection closed before a reply arrived.")
        if (first.length < 4 || first[3] != '-') return first
        val code = first.take(3)
        while (true) {
            val next = reader.readLine() ?: throw FtpError("Connection closed mid multi-line reply.")
            if (next.startsWith("$code ")) return next
        }
    }
}

/** Parses a `227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)` reply into a host and port. */
internal fun parsePasvReply(reply: String): Pair<String, Int>? {
    val m = Regex("""\((\d+),(\d+),(\d+),(\d+),(\d+),(\d+)\)""").find(reply) ?: return null
    val (h1, h2, h3, h4, p1, p2) = m.destructured
    return "$h1.$h2.$h3.$h4" to (p1.toInt() * 256 + p2.toInt())
}
