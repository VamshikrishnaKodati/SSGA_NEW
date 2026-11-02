package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit checks that the canonical mapping parser reads every shape the
 * confirm-mapping service can store (fields, nested componentColumns, options).
 */
class SheetMappingTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void readsFieldsComponentsAndOptionsSeparately() throws Exception {
        SheetMapping mapping = SheetMapping.parse(jsonMapper, """
                {
                  "columns": {
                    "employeeCode": "Emp Code",
                    "amount": "Gross",
                    "componentColumns": { "BASIC": "Basic Pay" }
                  },
                  "options": { "layout": "long", "effectiveFromDefault": "2024-04-01" }
                }
                """);

        assertEquals("Emp Code", mapping.header("employeeCode"));
        assertEquals("Gross", mapping.header("amount"));
        assertEquals(1, mapping.components().size());
        assertEquals("Basic Pay", mapping.components().get("BASIC"));
        assertEquals("LONG", mapping.layout());
        assertEquals("2024-04-01", mapping.effectiveFromDefault());
    }

    @Test
    void blankJsonYieldsEmptyMapping() throws Exception {
        SheetMapping mapping = SheetMapping.parse(jsonMapper, null);

        assertTrue(mapping.columns().isEmpty());
        assertTrue(mapping.components().isEmpty());
        assertEquals("", mapping.layout());
        assertNull(mapping.effectiveFromDefault());
    }

    @Test
    void missingOptionsLeaveLayoutBlank() throws Exception {
        SheetMapping mapping = SheetMapping.parse(jsonMapper, """
                { "columns": { "amount": "Gross" } }
                """);

        assertEquals("", mapping.layout());
        assertEquals("Gross", mapping.header("amount"));
    }
}
