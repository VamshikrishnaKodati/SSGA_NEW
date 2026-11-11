package SSGA_Backend.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves a division reference from an uploaded workbook to a division row.
 * Real files carry labels such as "O&M Division No.I" while the master stores
 * "O&M Division I", and spelling mistakes such as "Divison" are common, so an
 * exact match is tried first and a conservative fuzzy fallback second.
 */
final class DivisionMatcher {

    private static final int MIN_SCORE = 80;

    private DivisionMatcher() {
    }

    record Candidate(long id, String name, String code) {
    }

    record Resolution(long id, boolean ambiguous) {

        boolean matched() {
            return id > 0 && !ambiguous;
        }
    }

    static Resolution resolve(List<Candidate> divisions, String reference) {
        if (divisions == null || divisions.isEmpty()
                || reference == null || reference.isBlank()) {
            return new Resolution(-1, false);
        }

        String wanted = compact(reference);

        if (wanted.isEmpty()) {
            return new Resolution(-1, false);
        }

        List<Candidate> exact = new ArrayList<>();

        for (Candidate division : divisions) {
            if (wanted.equals(compact(division.name()))
                    || wanted.equals(compact(division.code()))
                    || wanted.equals(String.valueOf(division.id()))) {
                exact.add(division);
            }
        }

        if (exact.size() == 1) {
            return new Resolution(exact.get(0).id(), false);
        }
        if (exact.size() > 1) {
            return new Resolution(-1, true);
        }

        Candidate best = null;
        int bestScore = 0;
        boolean tie = false;

        for (Candidate division : divisions) {
            int score = Math.max(
                    similarity(wanted, compact(division.name())),
                    similarity(wanted, compact(division.code()))
            );

            if (score > bestScore) {
                best = division;
                bestScore = score;
                tie = false;
            } else if (score == bestScore && score >= MIN_SCORE) {
                tie = true;
            }
        }

        if (best == null || bestScore < MIN_SCORE) {
            return new Resolution(-1, false);
        }
        if (tie) {
            return new Resolution(-1, true);
        }

        return new Resolution(best.id(), false);
    }

    static String compact(String value) {
        if (value == null) {
            return "";
        }

        String cleaned = value.toUpperCase(Locale.ROOT)
                .replace('&', ' ')
                .replaceAll("[^A-Z0-9]+", " ")
                .trim();

        if (cleaned.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        for (String token : cleaned.split("\\s+")) {
            if (!"NO".equals(token)) {
                result.append(token);
            }
        }

        return result.toString();
    }

    private static int similarity(String left, String right) {
        if (left.equals(right)) {
            return 100;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }

        int max = Math.max(left.length(), right.length());
        int distance = levenshtein(left, right);

        return (int) Math.round(100.0 * (max - distance) / max);
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];

        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }

        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;

            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int cost = left.charAt(leftIndex - 1)
                        == right.charAt(rightIndex - 1) ? 0 : 1;

                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1,
                                previous[rightIndex] + 1),
                        previous[rightIndex - 1] + cost
                );
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[right.length()];
    }
}
