package SSGA_Backend.importer;
public class SalaryImportPreview {

    private int totalRows;
    private int validRows;
    private int errorRows;

    private String message;

    public SalaryImportPreview() {
    }

    public SalaryImportPreview(
            int totalRows,
            int validRows,
            int errorRows,
            String message
    ) {
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.errorRows = errorRows;
        this.message = message;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public void setValidRows(int validRows) {
        this.validRows = validRows;
    }

    public int getErrorRows() {
        return errorRows;
    }

    public void setErrorRows(int errorRows) {
        this.errorRows = errorRows;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}