package SSGA_Backend.importer;

import java.util.List;

public class StagingDetailFilterRequest {
    private List<String> validationStatus;
    private Boolean hasTempId;
    private Boolean divisionMismatch;
    private String componentCode;

    public List<String> getValidationStatus() { return validationStatus; }
    public void setValidationStatus(List<String> validationStatus) { this.validationStatus = validationStatus; }
    public Boolean getHasTempId() { return hasTempId; }
    public void setHasTempId(Boolean hasTempId) { this.hasTempId = hasTempId; }
    public Boolean getDivisionMismatch() { return divisionMismatch; }
    public void setDivisionMismatch(Boolean divisionMismatch) { this.divisionMismatch = divisionMismatch; }
    public String getComponentCode() { return componentCode; }
    public void setComponentCode(String componentCode) { this.componentCode = componentCode; }
}