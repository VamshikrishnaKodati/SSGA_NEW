package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public class StagingDetailRow {
    private long id;
    private int rowIndex;
    private String rawHrmsId;
    private String tempHrmsId;
    private String rawAadhaar;
    private String rawPan;
    private String rawContact;
    private String rawEmployeeName;
    private String rawDivision;
    private String rawDivisionCode;
    private Long empMasterId;
    private Long matchedDivisionId;
    private Long componentId;
    private String componentCode;
    private String componentName;
    private String category;
    private BigDecimal amount;
    private LocalDate effectiveFrom;
    private String validationStatus;
    private Map<String, Object> errorDetails;
    private boolean isExcluded;
    private String divisionName;
    private String divisionCode;
    private String masterName;
    private String masterHrmsId;

    // Getters and setters
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public int getRowIndex() { return rowIndex; }
    public void setRowIndex(int rowIndex) { this.rowIndex = rowIndex; }
    public String getRawHrmsId() { return rawHrmsId; }
    public void setRawHrmsId(String rawHrmsId) { this.rawHrmsId = rawHrmsId; }
    public String getTempHrmsId() { return tempHrmsId; }
    public void setTempHrmsId(String tempHrmsId) { this.tempHrmsId = tempHrmsId; }
    public String getRawAadhaar() { return rawAadhaar; }
    public void setRawAadhaar(String rawAadhaar) { this.rawAadhaar = rawAadhaar; }
    public String getRawPan() { return rawPan; }
    public void setRawPan(String rawPan) { this.rawPan = rawPan; }
    public String getRawContact() { return rawContact; }
    public void setRawContact(String rawContact) { this.rawContact = rawContact; }
    public String getRawEmployeeName() { return rawEmployeeName; }
    public void setRawEmployeeName(String rawEmployeeName) { this.rawEmployeeName = rawEmployeeName; }
    public String getRawDivision() { return rawDivision; }
    public void setRawDivision(String rawDivision) { this.rawDivision = rawDivision; }
    public String getRawDivisionCode() { return rawDivisionCode; }
    public void setRawDivisionCode(String rawDivisionCode) { this.rawDivisionCode = rawDivisionCode; }
    public Long getEmpMasterId() { return empMasterId; }
    public void setEmpMasterId(Long empMasterId) { this.empMasterId = empMasterId; }
    public Long getMatchedDivisionId() { return matchedDivisionId; }
    public void setMatchedDivisionId(Long matchedDivisionId) { this.matchedDivisionId = matchedDivisionId; }
    public Long getComponentId() { return componentId; }
    public void setComponentId(Long componentId) { this.componentId = componentId; }
    public String getComponentCode() { return componentCode; }
    public void setComponentCode(String componentCode) { this.componentCode = componentCode; }
    public String getComponentName() { return componentName; }
    public void setComponentName(String componentName) { this.componentName = componentName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public String getValidationStatus() { return validationStatus; }
    public void setValidationStatus(String validationStatus) { this.validationStatus = validationStatus; }
    public Map<String, Object> getErrorDetails() { return errorDetails; }
    public void setErrorDetails(Map<String, Object> errorDetails) { this.errorDetails = errorDetails; }
    public boolean isExcluded() { return isExcluded; }
    public void setExcluded(boolean excluded) { isExcluded = excluded; }
    public String getDivisionName() { return divisionName; }
    public void setDivisionName(String divisionName) { this.divisionName = divisionName; }
    public String getDivisionCode() { return divisionCode; }
    public void setDivisionCode(String divisionCode) { this.divisionCode = divisionCode; }
    public String getMasterName() { return masterName; }
    public void setMasterName(String masterName) { this.masterName = masterName; }
    public String getMasterHrmsId() { return masterHrmsId; }
    public void setMasterHrmsId(String masterHrmsId) { this.masterHrmsId = masterHrmsId; }
}