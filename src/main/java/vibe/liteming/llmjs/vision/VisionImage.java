package vibe.liteming.llmjs.vision;

import com.google.gson.JsonObject;
import vibe.liteming.llmjs.format.MessagePart;
import org.jetbrains.annotations.Nullable;

public class VisionImage {
    private final String mimeType;
    private final String base64Data;
    private final String detail;
    private final int width;
    private final int height;
    private final int byteSize;
    private final @Nullable String source;
    private final @Nullable String id;

    public VisionImage(String mimeType, String base64Data, String detail,
                       int width, int height, int byteSize,
                       @Nullable String source, @Nullable String id) {
        this.mimeType = mimeType != null && !mimeType.isBlank() ? mimeType : "image/jpeg";
        this.base64Data = base64Data != null ? base64Data : "";
        this.detail = detail != null && !detail.isBlank() ? detail : "low";
        this.width = width;
        this.height = height;
        this.byteSize = byteSize;
        this.source = source;
        this.id = id;
    }

    public MessagePart.ImagePart toMessagePart() {
        return MessagePart.image(mimeType, base64Data, detail, width, height, byteSize);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("mimeType", mimeType);
        json.addProperty("detail", detail);
        json.addProperty("width", width);
        json.addProperty("height", height);
        json.addProperty("byteSize", byteSize);
        if (source != null) json.addProperty("source", source);
        if (id != null) json.addProperty("id", id);
        return json;
    }

    public String getMimeType() { return mimeType; }
    public String getBase64Data() { return base64Data; }
    public String getDetail() { return detail; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getByteSize() { return byteSize; }
    public @Nullable String getSource() { return source; }
    public @Nullable String getId() { return id; }
}
