package vibe.liteming.llmjs.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server -> Client: dump recent LLM log entries when console opens. */
public class S2CLogHistoryPacket {
    private final List<String> entries;

    public S2CLogHistoryPacket(List<String> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (String entry : entries) {
            buf.writeUtf(entry, 32767);
        }
    }

    public static S2CLogHistoryPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<String> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(buf.readUtf(32767));
        }
        return new S2CLogHistoryPacket(entries);
    }

    public static void handle(S2CLogHistoryPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> vibe.liteming.llmjs.client.ClientEventHandler.handleLogHistory(msg.entries));
        ctx.get().setPacketHandled(true);
    }

    public List<String> getEntries() {
        return entries;
    }
}
