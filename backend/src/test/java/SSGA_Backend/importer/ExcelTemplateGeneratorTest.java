package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the template generator against drifting from the salary component
 * master. The generator holds a hand-maintained copy of the register, and it
 * silently fell a whole migration behind once already: it shipped BASIC,
 * GPF_DED, NPS_DED and COURT_ATT while the database held BASIC_PAY, GPF_EPF,
 * PRC_2020_NPS and COURT_ATCH.
 *
 * <p>The check reads the codes straight out of the migrations rather than from
 * a database, so it runs with no datasource.
 */
class ExcelTemplateGeneratorTest {

    private static final Path MIGRATION_43 = Paths.get(
            "..", "database", "43_salary_components_master_reset.sql"
    );
    private static final Path MIGRATION_45 = Paths.get(
            "..", "database", "45_add_court_attachment.sql"
    );

    /** Matches the leading ('CODE', 'Name', 'CATEGORY', ...) of a VALUES row. */
    private static final Pattern ROW = Pattern.compile(
            "\\(\\s*'([A-Z0-9_]+)'\\s*,\\s*'[^']*'\\s*,\\s*'(EARNING|DEDUCTION|SUMMARY_TOTAL)'"
    );

    private static List<String> codesFrom(Path migration, String category)
            throws IOException {
        assertTrue(
                Files.exists(migration),
                "migration not found: " + migration.toAbsolutePath()
        );

        List<String> codes = new ArrayList<>();

        Matcher matcher = ROW.matcher(Files.readString(migration));

        while (matcher.find()) {
            if (category.equals(matcher.group(2))) {
                codes.add(matcher.group(1));
            }
        }

        return codes;
    }

    private static Set<String> asSet(String[] values) {
        return new LinkedHashSet<>(List.of(values));
    }

    @Test
    void earningComponentsMatchTheRegister() throws IOException {
        List<String> expected = codesFrom(MIGRATION_43, "EARNING");

        assertEquals(
                expected,
                List.of(ExcelTemplateGenerator.EARNING_COMPONENTS),
                "EARNING_COMPONENTS drifted from 43_salary_components_master_reset.sql"
        );
    }

    @Test
    void deductionComponentsMatchTheRegister() throws IOException {
        List<String> expected = new ArrayList<>(codesFrom(MIGRATION_43, "DEDUCTION"));
        expected.addAll(codesFrom(MIGRATION_45, "DEDUCTION"));

        assertEquals(
                expected,
                List.of(ExcelTemplateGenerator.DEDUCTION_COMPONENTS),
                "DEDUCTION_COMPONENTS drifted from migrations 43 and 45"
        );
    }

    @Test
    void theTemplateCoversEveryImportableRowInTheRegister() throws IOException {
        Set<String> register = new LinkedHashSet<>();
        register.addAll(codesFrom(MIGRATION_43, "EARNING"));
        register.addAll(codesFrom(MIGRATION_43, "DEDUCTION"));
        register.addAll(codesFrom(MIGRATION_45, "DEDUCTION"));

        Set<String> template = new LinkedHashSet<>();
        template.addAll(asSet(ExcelTemplateGenerator.EARNING_COMPONENTS));
        template.addAll(asSet(ExcelTemplateGenerator.DEDUCTION_COMPONENTS));

        assertEquals(register, template);

        // The summary rows are derived by the engine and must never be offered
        // as importable component codes.
        assertTrue(template.contains("COURT_ATCH"));
    }

    /**
     * The generator reads divisions from the database, so there is no copy left
     * to drift. The mapping from a row to the dropdown value is the only logic
     * left worth pinning: the dropdown must show the unit name, not its code.
     */
    @Test
    void theDropdownListsUnitNamesFromTheLoadedMaster() {
        ExcelTemplateGenerator.DivisionMaster master =
                new ExcelTemplateGenerator.DivisionMaster(List.of(
                        new ExcelTemplateGenerator.DivisionMaster.Division(
                                "DIV_OM_01", "O&M Division No.I", "DIVISION", "WING_OM"
                        ),
                        new ExcelTemplateGenerator.DivisionMaster.Division(
                                "CIR_PCC_01", "PCC-I", "CIRCLE", "WING_PROJ"
                        )
                ));

        assertEquals(List.of("O&M Division No.I", "PCC-I"), List.of(master.names()));
    }
}

