package vibe.liteming.llmjs.client.widget;

import org.junit.jupiter.api.Test;
import vibe.liteming.llmcore.LlmRoute;
import vibe.liteming.llmcore.PriorityRoutingConfig;
import vibe.liteming.llmcore.RoutingConfigStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutingPanelLayoutTest {
    @Test
    void fourthProviderWrapsAfterThreeColumnLogicalWidth() {
        int columns = RoutingPanel.providerColumnCount(440, 110);

        assertEquals(3, columns);
        assertEquals(1, RoutingPanel.providerRows(3, columns));
        assertEquals(2, RoutingPanel.providerRows(4, columns));
        assertEquals(2, RoutingPanel.providerRows(6, columns));
        assertEquals(3, RoutingPanel.providerRows(7, columns));
    }

    @Test
    void narrowColumnShrinksToOneVisibleSlotInsteadOfDroppingEntries() {
        int columns = RoutingPanel.providerColumnCount(84, 110);

        assertEquals(1, columns);
        assertEquals(5, RoutingPanel.providerRows(5, columns));
    }

    @Test
    void mediumPanelStacksUntilBothSidesHaveRoomForProviderNames() {
        assertEquals(true, RoutingPanel.usesStackedProviderLayout(590));
        assertEquals(false, RoutingPanel.usesStackedProviderLayout(591));
    }

    @Test
    void fourAvailableProvidersFitTheFullWidthWhenMediumLayoutStacks() {
        int fullWidthColumns = RoutingPanel.providerColumnCount(520, 110);

        assertEquals(4, fullWidthColumns);
        assertEquals(1, RoutingPanel.providerRows(4, fullWidthColumns));
    }

    @Test
    void threeLeftClicksThenTwoRightClicksThenLeftBuildTheRequestedRoute() {
        LlmRoute route = LlmRoute.empty().withDeadline(90);
        route = RoutingPanel.withCandidateClick(route, "A", 0, -1);
        assertEquals("A", route.expression());
        route = RoutingPanel.withCandidateClick(route, "A", 0, 0);
        assertEquals("A*2", route.expression());
        route = RoutingPanel.withCandidateClick(route, "A", 0, 0);
        assertEquals("A*3", route.expression());
        route = RoutingPanel.withCandidateClick(route, "B", 1, 0);
        assertEquals("A*3 > B", route.expression());
        route = RoutingPanel.withCandidateClick(route, "C", 1, 1);
        route = RoutingPanel.withCandidateClick(route, "A", 0, 1);
        assertEquals("A*3 > (B | C) > A", route.expression());
        PriorityRoutingConfig saved = RoutingConfigStore.parse(RoutingConfigStore.toJsonString(
                PriorityRoutingConfig.empty().withDefaultRoute(route)));
        assertEquals(route, saved.defaultRoute());
        assertEquals(90, saved.defaultRoute().deadlineSeconds());
        // Saving/reopening resets the gesture: an existing trailing A is a completed stage.
        assertEquals("A*3 > (B | C) > A > A",
                RoutingPanel.withCandidateClick(saved.defaultRoute(), "A", 0, -1).expression());
    }

    @Test
    void switchingMouseButtonsStartsANewStageAndRepeatedRaceMembersAreIgnored() {
        LlmRoute route = RoutingPanel.withCandidateClick(LlmRoute.empty(), "A", 0, -1);
        route = RoutingPanel.withCandidateClick(route, "A", 1, 0);
        assertEquals("A > A", route.expression());
        assertSame(route, RoutingPanel.withCandidateClick(route, "A", 1, 1));
        route = RoutingPanel.withCandidateClick(route, "B", 1, 1);
        route = RoutingPanel.withCandidateClick(route, "B", 0, 1);
        assertEquals("A > (A | B) > B", route.expression());
        assertSame(route, RoutingPanel.withCandidateClick(route, "C", 2, 0));
    }

    @Test
    void candidateClicksRespectRetryRaceAndStageLimitsWithoutLosingTheRoute() {
        LlmRoute retries = LlmRoute.parse("A*10");
        assertThrows(IllegalArgumentException.class, () -> RoutingPanel.withCandidateClick(retries, "A", 0, 0));
        assertEquals("A*10", retries.expression());
        LlmRoute race = LlmRoute.parse("(A | B | C | D | E | F | G | H)");
        assertSame(race, RoutingPanel.withCandidateClick(race, "A", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> RoutingPanel.withCandidateClick(race, "I", 1, 1));
        assertEquals(8, race.stages().get(0).candidates().size());
        LlmRoute full = LlmRoute.parse("A > ".repeat(15) + "B");
        assertThrows(IllegalArgumentException.class, () -> RoutingPanel.withCandidateClick(full, "C", 0, 0));
        assertEquals(16, full.stages().size());
        assertEquals("A > ".repeat(15) + "B*2",
                RoutingPanel.withCandidateClick(full, "B", 0, 0).expression());
    }
}
