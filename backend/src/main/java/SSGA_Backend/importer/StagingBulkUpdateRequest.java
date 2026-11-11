package SSGA_Backend.importer;

import java.util.List;

public class StagingBulkUpdateRequest {
    private List<Long> detailIds;
    private String componentCode;
    private Long matchedDivisionId;
    private Boolean isExcluded;

    public List<Long> getDetailIds() { return detailIds; }
    public void setDetailIds(List<Long> detailIds) { this.detailIds = detailIds; }
    public String getComponentCode() { return componentCode; }
    public void setComponentCode(String componentCode) { this.componentCode = componentCode; }
    public Long getMatchedDivisionId() { return matchedDivisionId; }
    public void setMatchedDivisionId(Long matchedDivisionId) { this.matchedDivisionId = matchedDivisionId; }
    public Boolean getIsExcluded() { return isExcluded; }
    public void setIsExcluded(Boolean isExcluded) { this.isExcluded = isExcluded; }
}