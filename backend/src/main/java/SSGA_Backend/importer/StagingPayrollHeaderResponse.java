package SSGA_Backend.importer;

public class StagingPayrollHeaderResponse {
    private java.util.UUID id;
    private String batchNumber;
    private String fileName;
    private String status;
    private int totalRecords;
    private int validRecords;
    private int errorRecords;
    private int tempIdCount;
    private double totalGrossPayout;
    private double totalDeductions;
    private double totalNetPayout;
    private int payrollMonth;
    private int payrollYear;
    private String createdAt;
    private String message;

    public StagingPayrollHeaderResponse() {}

    public StagingPayrollHeaderResponse(java.util.UUID id, String batchNumber, String fileName, String status,
            int totalRecords, int validRecords, int errorRecords, int tempIdCount,
            double totalGrossPayout, double totalDeductions, double totalNetPayout,
            int payrollMonth, int payrollYear, String createdAt, String message) {
        this.id = id;
        this.batchNumber = batchNumber;
        this.fileName = fileName;
        this.status = status;
        this.totalRecords = totalRecords;
        this.validRecords = validRecords;
        this.errorRecords = errorRecords;
        this.tempIdCount = tempIdCount;
        this.totalGrossPayout = totalGrossPayout;
        this.totalDeductions = totalDeductions;
        this.totalNetPayout = totalNetPayout;
        this.payrollMonth = payrollMonth;
        this.payrollYear = payrollYear;
        this.createdAt = createdAt;
        this.message = message;
    }

    // Getters and setters
    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID id) { this.id = id; }
    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }
    public int getValidRecords() { return validRecords; }
    public void setValidRecords(int validRecords) { this.validRecords = validRecords; }
    public int getErrorRecords() { return errorRecords; }
    public void setErrorRecords(int errorRecords) { this.errorRecords = errorRecords; }
    public int getTempIdCount() { return tempIdCount; }
    public void setTempIdCount(int tempIdCount) { this.tempIdCount = tempIdCount; }
    public double getTotalGrossPayout() { return totalGrossPayout; }
    public void setTotalGrossPayout(double totalGrossPayout) { this.totalGrossPayout = totalGrossPayout; }
    public double getTotalDeductions() { return totalDeductions; }
    public void setTotalDeductions(double totalDeductions) { this.totalDeductions = totalDeductions; }
    public double getTotalNetPayout() { return totalNetPayout; }
    public void setTotalNetPayout(double totalNetPayout) { this.totalNetPayout = totalNetPayout; }
    public int getPayrollMonth() { return payrollMonth; }
    public void setPayrollMonth(int payrollMonth) { this.payrollMonth = payrollMonth; }
    public int getPayrollYear() { return payrollYear; }
    public void setPayrollYear(int payrollYear) { this.payrollYear = payrollYear; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}