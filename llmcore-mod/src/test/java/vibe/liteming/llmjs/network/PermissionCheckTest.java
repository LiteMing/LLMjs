package vibe.liteming.llmjs.network;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckTest {
    private static final UUID PLAYER_ID = UUID.fromString("a78cc4bd-861b-45dc-87ec-4699aab476e5");

    @Test
    void whitelistMatchesCanonicalUuidIgnoringCaseAndWhitespace() {
        assertTrue(PermissionCheck.isWhitelisted(
                PLAYER_ID,
                List.of("  A78CC4BD-861B-45DC-87EC-4699AAB476E5  ")));
    }

    @Test
    void whitelistRejectsInvalidOrDifferentUuid() {
        assertFalse(PermissionCheck.isWhitelisted(
                PLAYER_ID,
                List.of("not-a-uuid", "3137f116-8b14-46b2-b54d-6d940eecbdb4")));
        assertFalse(PermissionCheck.isWhitelisted(PLAYER_ID, List.of()));
        assertFalse(PermissionCheck.isWhitelisted(null, List.of(PLAYER_ID.toString())));
    }

    @Test
    void viewerStatusDoesNotResolveOrExposeProviderData() {
        AtomicBoolean invoked = new AtomicBoolean(false);

        JsonObject status = PermissionCheck.createStatusPayload(true, false, false, () -> {
            invoked.set(true);
            JsonObject full = new JsonObject();
            full.addProperty("maskedKey", "sk-...last");
            return full;
        }, JsonObject::new);

        assertFalse(invoked.get());
        assertEquals(3, status.size());
        assertTrue(status.get("canView").getAsBoolean());
        assertFalse(status.get("canTest").getAsBoolean());
        assertFalse(status.get("canAdminister").getAsBoolean());
        assertFalse(status.has("maskedKey"));
        assertFalse(status.has("providers"));
    }

    @Test
    void administratorStatusIncludesFullPayloadAndPermissionFlag() {
        JsonObject status = PermissionCheck.createStatusPayload(true, true, true, () -> {
            JsonObject full = new JsonObject();
            full.addProperty("count", 2);
            return full;
        }, JsonObject::new);

        assertEquals(2, status.get("count").getAsInt());
        assertTrue(status.get("canView").getAsBoolean());
        assertTrue(status.get("canTest").getAsBoolean());
        assertTrue(status.get("canAdminister").getAsBoolean());
    }

    @Test
    void delegatedTestStatusUsesRestrictedPayloadOnly() {
        AtomicBoolean fullInvoked = new AtomicBoolean(false);
        JsonObject status = PermissionCheck.createStatusPayload(false, true, false, () -> {
            fullInvoked.set(true);
            return new JsonObject();
        }, () -> {
            JsonObject restricted = new JsonObject();
            restricted.addProperty("purpose", "CHAT");
            return restricted;
        });

        assertFalse(fullInvoked.get());
        assertEquals("CHAT", status.get("purpose").getAsString());
        assertFalse(status.get("canView").getAsBoolean());
        assertTrue(status.get("canTest").getAsBoolean());
        assertFalse(status.get("canAdminister").getAsBoolean());
        assertFalse(status.has("providers"));
        assertFalse(status.has("routing"));
    }
}
