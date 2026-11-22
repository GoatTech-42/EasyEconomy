package dev.easyeconomy.selftest;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

// Carpet-style loopback stand-in: captures the packet listener the server
// installs and swallows everything that would touch a real socket, so a
// fake ServerPlayer can join, run commands and click menus with no client.
final class FakeConnection extends Connection {
	private static final SocketAddress LOCAL = new InetSocketAddress("127.0.0.1", 25565);
	private PacketListener listener;

	FakeConnection() {
		super(PacketFlow.SERVERBOUND);
	}

	@Override
	public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T newListener) {
		this.listener = newListener;
	}

	@Override
	public void setupOutboundProtocol(ProtocolInfo<?> info) {
	}

	@Override
	public PacketListener getPacketListener() {
		return listener;
	}

	@Override
	public void send(Packet<?> packet) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener futureListener) {
	}

	@Override
	public void send(Packet<?> packet, ChannelFutureListener futureListener, boolean flush) {
	}

	@Override
	public void tick() {
	}

	@Override
	public void flushChannel() {
	}

	@Override
	public void runOnceConnected(java.util.function.Consumer<Connection> consumer) {
		consumer.accept(this);
	}

	@Override
	public void disconnect(Component reason) {
	}

	@Override
	public void disconnect(DisconnectionDetails details) {
	}

	@Override
	public boolean isConnected() {
		return true;
	}

	@Override
	public boolean isMemoryConnection() {
		return true;
	}

	@Override
	public SocketAddress getRemoteAddress() {
		return LOCAL;
	}
}
