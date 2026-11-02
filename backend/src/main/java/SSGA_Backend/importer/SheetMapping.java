package SSGA_Backend.importer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical reader for the {@code import_job_sheets.column_mapping} JSON that
 * {@link ImportJobSheetMappingService} writes:
 * <pre>
 * { "columns": { "&lt;field&gt;": "&lt;header&gt;",
 *                "componentColumns": { "&lt;CODE&gt;": "&lt;header&gt;" } },
 *   "options": { "layout": "LONG", "effectiveFromDefault": "2024-01-01" } }
 * </pre>
 * Validation, preview and import all read the same stored mapping; keeping one
 * parser here stops the three from drifting apart again.
 */
final class SheetMapping {

    private final Map<String, String> columns;
    private final Map<String, String> components;
    private final Map<String, String> options;

    private SheetMapping(
            Map<String, String> columns,
            Map<String, String> components,
            Map<String, String> options
    ) {
        this.columns = columns;
        this.components = components;
        this.options = options;
    }

    static SheetMapping parse(JsonMapper jsonMapper, String json)
            throws IOException {

        Map<String, String> columns = new LinkedHashMap<>();
        Map<String, String> components = new LinkedHashMap<>();
        Map<String, String> options = new LinkedHashMap<>();

        if (json == null || json.isBlank()) {
            return new SheetMapping(columns, components, options);
        }

        Map<?, ?> root = jsonMapper.readValue(json, Map.class);

        if (root.get("columns") instanceof Map<?, ?> rawColumns) {
            for (Map.Entry<?, ?> entry : rawColumns.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    continue;
                }

                if ("componentColumns".equals(key)
                        && entry.getValue() instanceof Map<?, ?> rawComponents) {
                    for (Map.Entry<?, ?> component : rawComponents.entrySet()) {
                        if (component.getKey() instanceof String code
                                && component.getValue() instanceof String header) {
                            components.put(code, header);
                        }
                    }
                } else if (entry.getValue() instanceof String header) {
                    columns.put(key, header);
                }
            }
        }

        if (root.get("options") instanceof Map<?, ?> rawOptions) {
            for (Map.Entry<?, ?> entry : rawOptions.entrySet()) {
                if (entry.getKey() instanceof String key
                        && entry.getValue() != null) {
                    options.put(key, entry.getValue().toString());
                }
            }
        }

        return new SheetMapping(columns, components, options);
    }

    Map<String, String> columns() {
        return columns;
    }

    Map<String, String> components() {
        return components;
    }

    Map<String, String> options() {
        return options;
    }

    String header(String field) {
        return columns.get(field);
    }

    String option(String key) {
        return options.get(key);
    }

    String effectiveFromDefault() {
        return options.get("effectiveFromDefault");
    }

    String layout() {
        String layout = options.get("layout");
        return layout == null ? "" : layout.trim().toUpperCase(Locale.ROOT);
    }
}
