package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class DivisionMatcherTest {

    private static final List<DivisionMatcher.Candidate> DIVISIONS = List.of(
            new DivisionMatcher.Candidate(21, "O&M Division I", "OM-DIV-01"),
            new DivisionMatcher.Candidate(22, "O&M Division II", "OM-DIV-02"),
            new DivisionMatcher.Candidate(45, "Project Division I", "PRJ-DIV-01"),
            new DivisionMatcher.Candidate(62, "Central Stores Division", "STR-DIV-01"),
            new DivisionMatcher.Candidate(63, "Board Office", "BOARD-DIV-01")
    );

    @Test
    void resolvesNamesWithNoAndRomanNumerals() {
        assertEquals(21, DivisionMatcher.resolve(
                DIVISIONS, "O&M Division No.I").id());
        assertEquals(22, DivisionMatcher.resolve(
                DIVISIONS, "O&M Division No.II").id());
        assertEquals(45, DivisionMatcher.resolve(
                DIVISIONS, "Project Division No.I").id());
    }

    @Test
    void resolvesCodesAndIds() {
        assertEquals(21, DivisionMatcher.resolve(
                DIVISIONS, "OM-DIV-01").id());
        assertEquals(62, DivisionMatcher.resolve(
                DIVISIONS, "62").id());
    }

    @Test
    void toleratesSmallSpellingMistakes() {
        assertEquals(62, DivisionMatcher.resolve(
                DIVISIONS, "Central Stores Divison").id());
        assertEquals(63, DivisionMatcher.resolve(
                DIVISIONS, "Board Office").id());
    }

    @Test
    void leavesUnknownReferencesUnmatched() {
        DivisionMatcher.Resolution result = DivisionMatcher.resolve(
                DIVISIONS, "Unknown Division");

        assertFalse(result.matched());
        assertFalse(result.ambiguous());
    }

    @Test
    void reportsAmbiguousReferences() {
        DivisionMatcher.Resolution result = DivisionMatcher.resolve(
                List.of(
                        new DivisionMatcher.Candidate(1, "Same Division", "A"),
                        new DivisionMatcher.Candidate(2, "Same Division", "B")
                ),
                "Same Division"
        );

        assertTrue(result.ambiguous());
        assertFalse(result.matched());
    }
}
