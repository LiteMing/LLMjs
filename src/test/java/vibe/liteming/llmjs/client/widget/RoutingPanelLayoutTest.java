package vibe.liteming.llmjs.client.widget;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void narrowPanelStacksActiveAndAvailableSections() {
        assertEquals(true, RoutingPanel.usesStackedProviderLayout(359));
        assertEquals(false, RoutingPanel.usesStackedProviderLayout(360));
    }
}
