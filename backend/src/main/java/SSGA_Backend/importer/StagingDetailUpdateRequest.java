package SSGA_Backend.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public class StagingDetailUpdateRequest {
    private String componentCode;
    private BigDecimal amount;
    private String category;
    private LocalDate effectiveFrom;
    private Long matchedDivisionId;
    private Boolean isExcluded;

    public String getComponentCode() { return componentCode; }
    public void setComponentCode(String componentCode) { this.componentCode = componentCode; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public Long getMatchedDivisionId() { return matchedDivisionId; }
    public void setMatchedDivisionId(Long matchedDivisionId) { this.matchedDivisionId = matchedDivisionId; }
    public Boolean getIsExcluded() { return isExcluded; }
    public void setIsExcluded(Boolean isExcluded) { this.isExcluded = isExcluded; }
}