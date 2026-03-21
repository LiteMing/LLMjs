package com.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CStatusResponsePacket {
    private final String statusJson;
    private final boolean openConsole;

    public S2CStatusResponsePacket(String statusJson, boolean openConsole) {
        this.statusJson = statusJson;
        this.openConsole = openConsole;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(statusJson, 32767);
        buf.writeBoolean(openConsole);
    }

    public static S2CStatusResponsePacket decode(FriendlyByteBuf buf) {
        return new S2CStatusResponsePacket(buf.readUtf(32767), buf.readBoolean());
    }

    public static void handle(S2CStatusResponsePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (msg.openConsole) {
                com.liteming.llmjs.client.ClientEventHandler.openConsole(msg.statusJson);
            } else {
                com.liteming.llmjs.client.ClientEventHandler.updateStatus(msg.statusJson);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    public String getStatusJson() { return statusJson; }
    public boolean isOpenConsole() { return openConsole; }
}
