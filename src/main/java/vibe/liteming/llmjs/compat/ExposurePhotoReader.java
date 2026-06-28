package vibe.liteming.llmjs.compat;

import vibe.liteming.llmjs.LLMjs;
import vibe.liteming.llmjs.vision.VisionImage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

public final class ExposurePhotoReader {
    private static final String PHOTOGRAPH_ID = "exposure:photograph";
    private static final String AGED_PHOTOGRAPH_ID = "exposure:aged_photograph";

    private ExposurePhotoReader() {
    }

    public record ExposureImage(VisionImage image, String exposureId, InteractionHand hand) {
    }

    public static Optional<ExposureImage> readHeldPhoto(ServerPlayer player, String detail, int maxBytes) {
        Optional<ExposureImage> main = readPhoto(player, InteractionHand.MAIN_HAND, detail, maxBytes);
        if (main.isPresent()) return main;
        return readPhoto(player, InteractionHand.OFF_HAND, detail, maxBytes);
    }

    public static boolean isExposurePhoto(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        String key = id.toString();
        return PHOTOGRAPH_ID.equals(key) || AGED_PHOTOGRAPH_ID.equals(key);
    }

    public static @Nullable String getExposureId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null) return null;

        String fromPhotographFrame = readFrameId(tag, "photograph_frame");
        if (fromPhotographFrame != null) return fromPhotographFrame;

        String fromExposureFrame = readFrameId(tag, "exposure_frame");
        if (fromExposureFrame != null) return fromExposureFrame;

        if (tag.contains("Id")) return tag.getString("Id");
        if (tag.contains("id")) return tag.getString("id");
        if (tag.contains("identifier")) return tag.getString("identifier");
        return null;
    }

    private static Optional<ExposureImage> readPhoto(ServerPlayer player, InteractionHand hand, String detail, int maxBytes) {
        ItemStack stack = player.getItemInHand(hand);
        if (!isExposurePhoto(stack)) return Optional.empty();
        String exposureId = getExposureId(stack);
        if (exposureId == null || exposureId.isBlank()) {
            LLMjs.LOGGER.warn("Exposure photo in {} has no exposure id", hand);
            return Optional.empty();
        }
        byte[] pngBytes = readPhotoPng(player.server, exposureId);
        if (pngBytes == null || pngBytes.length == 0) return Optional.empty();
        if (pngBytes.length > maxBytes) {
            LLMjs.LOGGER.warn("Exposure photo '{}' is too large: {} > {}", exposureId, pngBytes.length, maxBytes);
            return Optional.empty();
        }
        String base64 = Base64.getEncoder().encodeToString(pngBytes);
        PhotoSize size = readPhotoSize(player.server, exposureId).orElse(new PhotoSize(0, 0));
        VisionImage image = new VisionImage("image/png", base64, detail,
                size.width(), size.height(), pngBytes.length, "exposure", exposureId);
        return Optional.of(new ExposureImage(image, exposureId, hand));
    }

    private static @Nullable String readFrameId(CompoundTag root, String key) {
        if (!root.contains(key)) return null;
        CompoundTag frame = root.getCompound(key);
        if (frame.contains("identifier")) return frame.getString("identifier");
        if (frame.contains("id")) return frame.getString("id");
        return null;
    }

    private static byte @Nullable [] readPhotoPng(MinecraftServer server, String exposureId) {
        try {
            PhotoPixels cached = readCachedPixels(server, exposureId).orElse(null);
            if (cached != null) return encodePng(cached);

            PhotoPixels disk = readDiskPixels(server, exposureId).orElse(null);
            if (disk != null) return encodePng(disk);
        } catch (Exception e) {
            LLMjs.LOGGER.error("Failed to read Exposure photo '{}'", exposureId, e);
        }
        return null;
    }

    private static Optional<PhotoSize> readPhotoSize(MinecraftServer server, String exposureId) {
        Optional<PhotoPixels> cached = readCachedPixels(server, exposureId);
        if (cached.isPresent()) return cached.map(p -> new PhotoSize(p.width(), p.height()));
        return readDiskPixels(server, exposureId).map(p -> new PhotoSize(p.width(), p.height()));
    }

    private static Optional<PhotoPixels> readCachedPixels(MinecraftServer server, String exposureId) {
        try {
            Object dataStorage = server.overworld().getDataStorage();
            Field statesField = null;
            for (Field field : dataStorage.getClass().getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    statesField = field;
                    break;
                }
            }
            if (statesField == null) return Optional.empty();
            statesField.setAccessible(true);
            Map<?, ?> states = (Map<?, ?>) statesField.get(dataStorage);
            Object exposureData = states.get("exposures/" + exposureId);
            if (exposureData == null) return Optional.empty();

            int width = readIntField(exposureData, "width");
            int height = readIntField(exposureData, "height");
            byte[] pixels = readBytesField(exposureData, "pixels");
            if (isValid(width, height, pixels)) {
                return Optional.of(new PhotoPixels(width, height, pixels));
            }
        } catch (Exception e) {
            LLMjs.LOGGER.debug("Exposure in-memory cache read failed for '{}': {}", exposureId, e.getMessage());
        }
        return Optional.empty();
    }

    private static Optional<PhotoPixels> readDiskPixels(MinecraftServer server, String exposureId) {
        try {
            Path worldRoot = server.getWorldPath(LevelResource.ROOT);
            File file = worldRoot.resolve("data").resolve("exposures").resolve(exposureId + ".dat").toFile();
            if (!file.exists()) return Optional.empty();

            CompoundTag root = NbtIo.readCompressed(file);
            CompoundTag data = root.contains("data") ? root.getCompound("data") : root;
            int width = data.getInt("width");
            int height = data.getInt("height");
            byte[] pixels = data.getByteArray("pixels");
            if (isValid(width, height, pixels)) {
                return Optional.of(new PhotoPixels(width, height, pixels));
            }
        } catch (Exception e) {
            LLMjs.LOGGER.warn("Exposure .dat read failed for '{}': {}", exposureId, e.getMessage());
        }
        return Optional.empty();
    }

    private static byte[] encodePng(PhotoPixels photo) throws Exception {
        BufferedImage image = new BufferedImage(photo.width(), photo.height(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < photo.height(); y++) {
            for (int x = 0; x < photo.width(); x++) {
                int index = y * photo.width() + x;
                int packed = photo.pixels()[index] & 0xFF;
                int rgb = MapColor.getColorFromPackedId(packed);
                image.setRGB(x, y, 0xFF000000 | (rgb & 0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", out);
        return out.toByteArray();
    }

    private static boolean isValid(int width, int height, byte[] pixels) {
        return width > 0 && height > 0 && pixels != null && pixels.length >= width * height;
    }

    private static int readIntField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(instance);
    }

    private static byte[] readBytesField(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (byte[]) field.get(instance);
    }

    private record PhotoPixels(int width, int height, byte[] pixels) {
    }

    private record PhotoSize(int width, int height) {
    }
}
