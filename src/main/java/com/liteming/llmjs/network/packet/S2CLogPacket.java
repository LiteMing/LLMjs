package com.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class S2CLogPacket {
    private final String logEntryJson;

    public S2CLogPacket(String logEntryJson) {
        this.logEntryJson = logEntryJson;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(logEntryJson, 32767);
    }

    public static S2CLogPacket decode(FriendlyByteBuf buf) {
        return new S2CLogPacket(buf.readUtf(32767));
    }

    public static void handle(S2CLogPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Client-side handler - will be connected to UI in Task 12
        });
        ctx.get().setPacketHandled(true);
    }

    public String getLogEntryJson() { return logEntryJson; }
}
