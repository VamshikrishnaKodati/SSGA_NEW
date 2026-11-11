package SSGA_Backend.importer;

import java.util.List;

public class HmwssbDivisionDto {
    private long id;
    private String divisionCode;
    private String divisionName;
    private String category;
    private String parentGroup;
    private int displayOrder;
    private String status;
    private String verificationStatus;
    
    // Hierarchy fields
    private String wingCode;
    private String wingName;
    private String circleCode;
    private String circleName;
    private Integer hierarchyLevel;
    private Long parentDivisionId;
    private String areaCoverage;
    private String headDesignation;
    
    // Children for tree structure
    private List<HmwssbDivisionDto> children;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getDivisionCode() { return divisionCode; }
    public void setDivisionCode(String divisionCode) { this.divisionCode = divisionCode; }
    public String getDivisionName() { return divisionName; }
    public void setDivisionName(String divisionName) { this.divisionName = divisionName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getParentGroup() { return parentGroup; }
    public void setParentGroup(String parentGroup) { this.parentGroup = parentGroup; }
    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }
    
    public String getWingCode() { return wingCode; }
    public void setWingCode(String wingCode) { this.wingCode = wingCode; }
    public String getWingName() { return wingName; }
    public void setWingName(String wingName) { this.wingName = wingName; }
    public String getCircleCode() { return circleCode; }
    public void setCircleCode(String circleCode) { this.circleCode = circleCode; }
    public String getCircleName() { return circleName; }
    public void setCircleName(String circleName) { this.circleName = circleName; }
    public Integer getHierarchyLevel() { return hierarchyLevel; }
    public void setHierarchyLevel(Integer hierarchyLevel) { this.hierarchyLevel = hierarchyLevel; }
    public Long getParentDivisionId() { return parentDivisionId; }
    public void setParentDivisionId(Long parentDivisionId) { this.parentDivisionId = parentDivisionId; }
    public String getAreaCoverage() { return areaCoverage; }
    public void setAreaCoverage(String areaCoverage) { this.areaCoverage = areaCoverage; }
    public String getHeadDesignation() { return headDesignation; }
    public void setHeadDesignation(String headDesignation) { this.headDesignation = headDesignation; }
    
    public List<HmwssbDivisionDto> getChildren() { return children; }
    public void setChildren(List<HmwssbDivisionDto> children) { this.children = children; }
}