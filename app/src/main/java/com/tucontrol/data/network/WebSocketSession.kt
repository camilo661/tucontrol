package com.tucontrol.data.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Coroutine-friendly wrapper around an OkHttp [WebSocket]. */
class WebSocketSession private constructor(
    private val socket: WebSocket,
    private val inbox: Channel<String>,
) {
    /** Text frames received from the peer; closes when the socket closes. */
    val incoming: ReceiveChannel<String> get() = inbox

    fun send(text: String): Boolean = socket.send(text)

    fun close() {
        socket.close(NORMAL_CLOSURE, null)
        inbox.close()
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** Opens a socket and suspends until the handshake succeeds or fails. */
        suspend fun open(client: OkHttpClient, url: String, timeoutMs: Long = 5_000): WebSocketSession {
            val inbox = Channel<String>(Channel.UNLIMITED)
            val opened = CompletableDeferred<Unit>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbox.trySend(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    opened.completeExceptionally(t)
                    inbox.close(t)
                }
            }
            val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
            try {
                withTimeout(timeoutMs) { opened.await() }
            } catch (error: Throwable) {
                socket.cancel()
                throw error
            }
            return WebSocketSession(socket, inbox)
        }
    }
}
