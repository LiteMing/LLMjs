package vibe.liteming.llmjs.network;

import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.network.packet.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class LLMNetwork {
    private static final String PROTOCOL_VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(LLMjs.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    public static void register() {
        CHANNEL.messageBuilder(C2SChatRequestPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SChatRequestPacket::encode)
                .decoder(C2SChatRequestPacket::decode)
                .consumerMainThread(C2SChatRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SStatusRequestPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SStatusRequestPacket::encode)
                .decoder(C2SStatusRequestPacket::decode)
                .consumerMainThread(C2SStatusRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CChatResponsePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CChatResponsePacket::encode)
                .decoder(S2CChatResponsePacket::decode)
                .consumerMainThread(S2CChatResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CStatusResponsePacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CStatusResponsePacket::encode)
                .decoder(S2CStatusResponsePacket::decode)
                .consumerMainThread(S2CStatusResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CLogPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CLogPacket::encode)
                .decoder(S2CLogPacket::decode)
                .consumerMainThread(S2CLogPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SSetupProviderPacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SSetupProviderPacket::encode)
                .decoder(C2SSetupProviderPacket::decode)
                .consumerMainThread(C2SSetupProviderPacket::handle)
                .add();

        CHANNEL.messageBuilder(S2CScreenshotRequestPacket.class, packetId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CScreenshotRequestPacket::encode)
                .decoder(S2CScreenshotRequestPacket::decode)
                .consumerMainThread(S2CScreenshotRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(C2SVisionImagePacket.class, packetId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SVisionImagePacket::encode)
                .decoder(C2SVisionImagePacket::decode)
                .consumerMainThread(C2SVisionImagePacket::handle)
                .add();
    }
}
