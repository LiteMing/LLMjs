package vibe.liteming.llmjs.security;

import org.junit.jupiter.api.Test;
import vibe.liteming.llmjs.test.ConsoleTestCodec;
import vibe.liteming.llmjs.test.ConsoleTestRequest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTestGrantServiceTest {
    @Test
    void grantBindsPlayerRequestAndImmutableServerDraft() {
        ConsoleTestGrantService service = new ConsoleTestGrantService(1_000L);
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ConsoleTestRequest request = ConsoleTestRequest.simple(requestId, "approved", "");

        assertTrue(service.issue(player.toString(), ConsoleTestCodec.toJson(request)));
        assertEquals("approved", service.authorizedRequest(player, requestId).orElseThrow()
                .messages().get(0).parts().get(0).text());
        assertFalse(service.authorizedRequest(UUID.randomUUID(), requestId).isPresent());
        assertFalse(service.authorizedRequest(player, UUID.randomUUID()).isPresent());
    }

    @Test
    void malformedAndExpiredGrantsAreRejected() {
        ConsoleTestGrantService service = new ConsoleTestGrantService(1_000L);
        UUID player = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        ConsoleTestRequest request = ConsoleTestRequest.simple(requestId, "approved", "");

        assertFalse(service.issue("not-a-uuid", ConsoleTestCodec.toJson(request), 100L));
        assertFalse(service.issue(player.toString(), "{bad-json", 100L));
        assertTrue(service.issue(player.toString(), ConsoleTestCodec.toJson(request), 100L));
        assertTrue(service.activeGrant(player, 1_100L).isPresent());
        assertFalse(service.activeGrant(player, 1_101L).isPresent());
    }
}
