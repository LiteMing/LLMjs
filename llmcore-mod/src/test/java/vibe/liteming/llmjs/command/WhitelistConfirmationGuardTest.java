package vibe.liteming.llmjs.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhitelistConfirmationGuardTest {
    private final WhitelistConfirmationGuard guard = new WhitelistConfirmationGuard(30_000L);

    @Test
    void exactSecondAttemptConfirmsOnlyOnce() {
        assertFalse(guard.confirmOrArm("owner", "true:player-a", 1_000L));
        assertTrue(guard.confirmOrArm("owner", "true:player-a", 2_000L));
        assertFalse(guard.confirmOrArm("owner", "true:player-a", 3_000L));
    }

    @Test
    void confirmationIsBoundToActorAndOperation() {
        assertFalse(guard.confirmOrArm("owner-a", "true:player-a", 1_000L));
        assertFalse(guard.confirmOrArm("owner-b", "true:player-a", 2_000L));
        assertFalse(guard.confirmOrArm("owner-a", "false:player-a", 3_000L));
        assertFalse(guard.confirmOrArm("owner-a", "true:player-a", 4_000L));
        assertTrue(guard.confirmOrArm("owner-a", "true:player-a", 5_000L));
    }

    @Test
    void expiredConfirmationMustBeArmedAgain() {
        assertFalse(guard.confirmOrArm("owner", "true:player-a", 1_000L));
        assertFalse(guard.confirmOrArm("owner", "true:player-a", 31_001L));
        assertTrue(guard.confirmOrArm("owner", "true:player-a", 31_002L));
    }
}
