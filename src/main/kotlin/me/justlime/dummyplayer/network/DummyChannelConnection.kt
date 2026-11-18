package me.justlime.dummyplayer.network

import com.hypixel.hytale.protocol.FormattedMessage
import com.hypixel.hytale.protocol.NetworkChannel
import com.hypixel.hytale.protocol.ToClientPacket
import com.hypixel.hytale.protocol.io.ChannelConnection
import com.hypixel.hytale.protocol.io.ConnectionHandler
import com.hypixel.hytale.protocol.io.PacketStatsRecorder
import com.hypixel.hytale.protocol.packets.connection.QuicApplicationErrorCode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer
import java.util.function.BooleanSupplier
import java.util.logging.Level

/**
 * Connection with no real client behind it. Since server 0.7.7, PacketHandler takes a
 * ChannelConnection instead of a netty Channel. Writes are dropped here, so packets sent
 * directly to the channel (chunks, world map) don't pile up in memory.
 */
class DummyChannelConnection : ChannelConnection {

    @Volatile
    private var active = true

    override fun flush() {}

    override fun write(packet: ToClientPacket) {}

    override fun writeAndFlush(packet: ToClientPacket) {}

    override fun write(packets: Array<out ToClientPacket>) {}

    override fun writeAndFlush(packets: Array<out ToClientPacket>) {}

    override fun isActive(): Boolean = active

    override fun isWritable(): Boolean = active

    override fun remoteAddress(): SocketAddress = ADDRESS

    override fun formatRemoteAddress(): String = "dummy"

    override fun disconnect(message: FormattedMessage) {
        active = false
    }

    override fun getPacketStatsRecorder(): PacketStatsRecorder = PacketStatsRecorder.NOOP

    override fun getSniHostname(): String? = null

    override fun isFromSameOrigin(other: ChannelConnection): Boolean = other === this

    override fun execute(task: Runnable) = task.run()

    override fun getClientCertificate(): X509Certificate? = null

    override fun initTimeoutContext(stage: String, context: String) {}

    override fun updateTimeoutContext(stage: String, context: String) {}

    override fun updateTimeoutContext(context: String) {}

    override fun setPacketTimeout(timeout: Duration) {}

    override fun clearPacketTimeout() {}

    override fun setStageTimeout(stage: String, timeout: Duration, condition: BooleanSupplier, onTimeout: Runnable) {}

    override fun clearStageTimeout() {}

    override fun logConnectionTimings(stage: String, level: Level) {}

    override fun setupAuxiliaryChannels(
        handler: ConnectionHandler,
        onChannel: BiConsumer<NetworkChannel, ChannelConnection>
    ): CompletableFuture<Void> {
        for (channel in NetworkChannel.VALUES) {
            if (channel != NetworkChannel.Default) onChannel.accept(channel, this)
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun setChannelHandler(handler: ConnectionHandler) {}

    override fun closeConnection() {
        active = false
    }

    override fun closeApplicationConnection() {
        active = false
    }

    override fun closeApplicationConnection(code: QuicApplicationErrorCode) {
        active = false
    }

    override fun closeApplicationConnection(code: QuicApplicationErrorCode, message: FormattedMessage) {
        active = false
    }

    override fun updateStreamPriority(priority: Int, incremental: Boolean) {}

    private companion object {
        val ADDRESS: SocketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), 0)
    }
}
