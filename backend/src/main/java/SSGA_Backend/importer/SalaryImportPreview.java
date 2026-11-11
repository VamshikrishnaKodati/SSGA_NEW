package SSGA_Backend.importer;

import java.util.List;
import java.util.Map;

public class SalaryImportPreview {
    private int totalRows;
    private int validRows;
    private int errorRows;
    private int warningRows;
    private String layoutType;
    private List<String> headers;
    private Map<String, Object> suggestedMappings;
    private List<Map<String, Object>> sampleRows;
    private List<String> errors;
    private List<String> warnings;
    private String message;

    public SalaryImportPreview() {}

    public SalaryImportPreview(int totalRows, int validRows, int errorRows, String message) {
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.errorRows = errorRows;
        this.message = message;
    }

    public SalaryImportPreview(int totalRows, int validRows, int errorRows, int warningRows,
            String layoutType, List<String> headers, Map<String, Object> suggestedMappings,
            List<Map<String, Object>> sampleRows, List<String> errors, List<String> warnings) {
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.errorRows = errorRows;
        this.warningRows = warningRows;
        this.layoutType = layoutType;
        this.headers = headers;
        this.suggestedMappings = suggestedMappings;
        this.sampleRows = sampleRows;
        this.errors = errors;
        this.warnings = warnings;
        this.message = buildMessage(validRows, errorRows, warningRows);
    }

    private String buildMessage(int valid, int errors, int warnings) {
        return String.format("Parsed %d rows: %d valid, %d errors, %d warnings. Layout: %s", 
            totalRows, valid, errors, warnings, layoutType);
    }

    // Getters and setters
    public int getTotalRows() { return totalRows; }
    public void setTotalRows(int totalRows) { this.totalRows = totalRows; }
    public int getValidRows() { return validRows; }
    public void setValidRows(int validRows) { this.validRows = validRows; }
    public int getErrorRows() { return errorRows; }
    public void setErrorRows(int errorRows) { this.errorRows = errorRows; }
    public int getWarningRows() { return warningRows; }
    public void setWarningRows(int warningRows) { this.warningRows = warningRows; }
    public String getLayoutType() { return layoutType; }
    public void setLayoutType(String layoutType) { this.layoutType = layoutType; }
    public List<String> getHeaders() { return headers; }
    public void setHeaders(List<String> headers) { this.headers = headers; }
    public Map<String, Object> getSuggestedMappings() { return suggestedMappings; }
    public void setSuggestedMappings(Map<String, Object> suggestedMappings) { this.suggestedMappings = suggestedMappings; }
    public List<Map<String, Object>> getSampleRows() { return sampleRows; }
    public void setSampleRows(List<Map<String, Object>> sampleRows) { this.sampleRows = sampleRows; }
    public List<String> getErrors() { return errors; }
    public void setErrors(List<String> errors) { this.errors = errors; }
    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}