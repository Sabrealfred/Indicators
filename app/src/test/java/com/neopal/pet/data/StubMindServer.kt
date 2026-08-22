package com.neopal.pet.data

import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A throwaway HTTP server that speaks just enough to stand in for a model service.
 *
 * It exists because the network half of [RemoteMindClient] had never once been executed. The
 * client is pure JVM — no Android anywhere in it — so the only thing between it and a real run
 * was something on the other end of a socket, and everything that half does is the kind of thing
 * that is wrong in ways reading cannot catch: which header the key travels in, whether a redirect
 * is followed, what a non-2xx does, whether a timeout ends as null or as an exception thrown
 * through a coroutine.
 *
 * Deliberately built on [ServerSocket] rather than the JDK's own HTTP server. This is compiled as
 * an Android unit test, where the platform jar sits on the classpath alongside the JDK, and
 * `java.base` sockets are the one thing certain to be there. A test that cannot run is worth less
 * than a test that is slightly longer.
 *
 * One connection at a time, which is all any of these tests needs.
 */
class StubMindServer(private val reply: (Received) -> Reply) : AutoCloseable {

    /** What the client actually sent, as opposed to what it was supposed to send. */
    data class Received(
        val method: String,
        val path: String,
        /** Header names lowercased; HTTP header names are case-insensitive and clients vary. */
        val headers: Map<String, String>,
        val body: String,
    )

    data class Reply(
        val status: Int = 200,
        val body: String = "",
        val extraHeaders: Map<String, String> = emptyMap(),
        /** Held this long before answering, to exercise the client's own timeout. */
        val delayMillis: Long = 0L,
    )

    private val socket = ServerSocket(0)

    /** Every request that arrived, in order. The point of most of the assertions. */
    val received: MutableList<Received> = CopyOnWriteArrayList()

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}/v1"

    private val thread = Thread {
        while (!socket.isClosed) {
            try {
                socket.accept().use { client ->
                    // Read bytes, never characters. Content-Length counts bytes, and these
                    // prompts are full of em dashes, so a reader that consumed that many *chars*
                    // would sit waiting for bytes that were never coming and the request would
                    // die of the client's own timeout — which looks exactly like the client
                    // being broken. It cost an afternoon to find that the first time.
                    val input = client.getInputStream()
                    val head = ArrayList<Byte>(512)
                    while (head.size < 4 || !head.takeLast(4).let {
                            it[0] == CR && it[1] == LF && it[2] == CR && it[3] == LF
                        }
                    ) {
                        val b = input.read()
                        if (b < 0) return@use
                        head += b.toByte()
                    }
                    val lines = String(head.toByteArray(), Charsets.UTF_8).split("\r\n")
                    val parts = lines.first().split(' ')
                    val headers = HashMap<String, String>()
                    for (line in lines.drop(1)) {
                        if (line.isEmpty()) continue
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.take(colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val bytes = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val n = input.read(bytes, read, length - read)
                        if (n < 0) break
                        read += n
                    }
                    val body = String(bytes, 0, read, Charsets.UTF_8)

                    val request = Received(
                        method = parts.getOrElse(0) { "" },
                        path = parts.getOrElse(1) { "" },
                        headers = headers,
                        body = body,
                    )
                    received += request

                    val answer = reply(request)
                    if (answer.delayMillis > 0) Thread.sleep(answer.delayMillis)

                    val replyBytes = answer.body.toByteArray(Charsets.UTF_8)
                    val replyHead = buildString {
                        append("HTTP/1.1 ${answer.status} X\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${replyBytes.size}\r\n")
                        answer.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                        append("Connection: close\r\n\r\n")
                    }
                    client.getOutputStream().apply {
                        write(replyHead.toByteArray(Charsets.UTF_8))
                        write(replyBytes)
                        flush()
                    }
                }
            } catch (stopped: SocketException) {
                // close() races the accept loop; that is how it is meant to stop.
                return@Thread
            } catch (ignored: Throwable) {
                // A client that hangs up mid-request is a case under test, not a failure here.
            }
        }
    }.apply { isDaemon = true; start() }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CR: Byte = 13
        private const val LF: Byte = 10

        /** A chat-completions response carrying [content], shaped as OpenAI and OpenRouter send it. */
        fun completion(content: String): String {
            val escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
            return """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"$escaped"},""" +
                """"finish_reason":"stop"}],"usage":{"total_tokens":11}}"""
        }
    }
}
