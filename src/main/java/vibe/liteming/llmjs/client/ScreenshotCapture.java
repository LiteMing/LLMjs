package vibe.liteming.llmjs.client;

import vibe.liteming.llmjs.vision.VisionRequestManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Locale;

@OnlyIn(Dist.CLIENT)
public final class ScreenshotCapture {
    private ScreenshotCapture() {
    }

    public record Result(byte[] bytes, String mimeType, int width, int height) {
    }

    public static Result capture(VisionRequestManager.CaptureOptions options) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        NativeImage nativeImage = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
        try {
            BufferedImage source = toBufferedImage(nativeImage);
            String mimeType = normalizeMime(options.mimeType());
            int targetWidth = Math.min(options.maxWidth(), source.getWidth());
            int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
            float quality = options.quality();

            byte[] best = new byte[0];
            int bestWidth = targetWidth;
            int bestHeight = targetHeight;
            for (int attempt = 0; attempt < 10; attempt++) {
                BufferedImage scaled = scale(source, targetWidth, targetHeight, "image/jpeg".equals(mimeType));
                byte[] encoded = encode(scaled, mimeType, quality);
                best = encoded;
                bestWidth = targetWidth;
                bestHeight = targetHeight;
                if (encoded.length <= options.maxBytes()) {
                    return new Result(encoded, mimeType, targetWidth, targetHeight);
                }
                if ("manual".equalsIgnoreCase(options.compression())) {
                    throw new IllegalStateException("manual compression exceeded " + options.maxBytes() + " bytes");
                }
                if (quality > 0.35f && "image/jpeg".equals(mimeType)) {
                    quality = Math.max(0.30f, quality - 0.12f);
                } else {
                    targetWidth = Math.max(128, Math.round(targetWidth * 0.82f));
                    targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
                    quality = Math.min(0.72f, options.quality());
                }
            }
            if (best.length > options.maxBytes()) {
                throw new IllegalStateException("auto compression could not fit image under " + options.maxBytes() + " bytes");
            }
            return new Result(best, mimeType, bestWidth, bestHeight);
        } finally {
            nativeImage.close();
        }
    }

    private static BufferedImage toBufferedImage(NativeImage image) {
        BufferedImage buffered = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y);
                int a = (abgr >> 24) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int r = abgr & 0xFF;
                buffered.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return buffered;
    }

    private static BufferedImage scale(BufferedImage source, int width, int height, boolean opaque) {
        BufferedImage scaled = new BufferedImage(width, height,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (opaque) {
                graphics.setColor(Color.BLACK);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private static byte[] encode(BufferedImage image, String mimeType, float quality) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ("image/png".equals(mimeType)) {
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (MemoryCacheImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(quality);
            }
            writer.setOutput(imageOut);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static String normalizeMime(String mimeType) {
        String lower = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if ("image/png".equals(lower)) return "image/png";
        return "image/jpeg";
    }
}
