package SSGA_Backend.importer;

import java.util.Map;

public class ImportJobSheetAutoMapResponse {
    private long jobId;
    private int sheetIndex;
    private String sheetName;
    private String suggestedType;
    private Map<String, Object> suggestedColumns;
    private Map<String, Object> suggestedOptions;
    private String message;

    public ImportJobSheetAutoMapResponse() {}

    public ImportJobSheetAutoMapResponse(long jobId, int sheetIndex, String sheetName,
            String suggestedType, Map<String, Object> suggestedColumns,
            Map<String, Object> suggestedOptions, String message) {
        this.jobId = jobId;
        this.sheetIndex = sheetIndex;
        this.sheetName = sheetName;
        this.suggestedType = suggestedType;
        this.suggestedColumns = suggestedColumns;
        this.suggestedOptions = suggestedOptions;
        this.message = message;
    }

    public long getJobId() { return jobId; }
    public void setJobId(long jobId) { this.jobId = jobId; }
    public int getSheetIndex() { return sheetIndex; }
    public void setSheetIndex(int sheetIndex) { this.sheetIndex = sheetIndex; }
    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }
    public String getSuggestedType() { return suggestedType; }
    public void setSuggestedType(String suggestedType) { this.suggestedType = suggestedType; }
    public Map<String, Object> getSuggestedColumns() { return suggestedColumns; }
    public void setSuggestedColumns(Map<String, Object> suggestedColumns) { this.suggestedColumns = suggestedColumns; }
    public Map<String, Object> getSuggestedOptions() { return suggestedOptions; }
    public void setSuggestedOptions(Map<String, Object> suggestedOptions) { this.suggestedOptions = suggestedOptions; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}