package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> Client: result of a vision probe requested by C2SVisionProbePacket.
 * Consumed by the Test tab to render per-provider "Vision: yes/no (latency)".
 */
public class S2CVisionProbeResultPacket {
    private final String providerName;
    private final boolean supported;
    private final String error;
    private final long latencyMs;

    public S2CVisionProbeResultPacket(String providerName, boolean supported, String error, long latencyMs) {
        this.providerName = providerName;
        this.supported = supported;
        this.error = error == null ? "" : error;
        this.latencyMs = latencyMs;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(providerName, 256);
        buf.writeBoolean(supported);
        buf.writeUtf(error, 1024);
        buf.writeVarLong(latencyMs);
    }

    public static S2CVisionProbeResultPacket decode(FriendlyByteBuf buf) {
        return new S2CVisionProbeResultPacket(buf.readUtf(256), buf.readBoolean(),
                buf.readUtf(1024), buf.readVarLong());
    }

    public static void handle(S2CVisionProbeResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                vibe.liteming.llmjs.client.ClientEventHandler.onVisionProbeResult(
                        msg.providerName, msg.supported, msg.error, msg.latencyMs));
        ctx.get().setPacketHandled(true);
    }

    public String providerName() { return providerName; }
    public boolean supported() { return supported; }
    public String error() { return error; }
    public long latencyMs() { return latencyMs; }
}
