package me.vlosses.vLosses;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayServiceTest {
    @Test
    void animationAdvancesAtBoundaryAndWraps() {
        List<String> frames = List.of("first", "second", "third");
        assertEquals("first", DisplayService.frame(frames, 0, 20));
        assertEquals("first", DisplayService.frame(frames, 19, 20));
        assertEquals("second", DisplayService.frame(frames, 20, 20));
        assertEquals("third", DisplayService.frame(frames, 59, 20));
        assertEquals("first", DisplayService.frame(frames, 60, 20));
    }

    @Test
    void longRunningAnimationKeepsItsFrameWithoutIntegerOverflow() {
        assertEquals("second", DisplayService.frame(List.of("first", "second", "third"), 4_294_967_300L, 20));
        assertEquals("", DisplayService.frame(List.of(), Long.MAX_VALUE, 20));
        assertEquals("only", DisplayService.frame(List.of("only"), Long.MAX_VALUE, 20));
    }

    @Test
    void duplicateAndEmptySidebarLinesHaveDistinctScoreEntries() {
        Map<String, String> entries = new HashMap<>();
        List<String> lines = List.of("", "same", "", "same", "", "same", "", "same", "", "same", "", "same", "", "same", "");
        for (int index = 0; index < lines.size(); index++) entries.put(DisplayService.entryKey(index), lines.get(index));
        assertEquals(15, entries.size());
        assertEquals(8, entries.values().stream().filter(String::isEmpty).count());
        assertEquals(7, entries.values().stream().filter("same"::equals).count());
        assertThrows(IllegalArgumentException.class, () -> DisplayService.entryKey(15));
        assertThrows(IllegalArgumentException.class, () -> DisplayService.entryKey(-1));
    }
}
