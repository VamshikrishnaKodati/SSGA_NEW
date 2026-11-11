package SSGA_Backend.importer;

import java.util.List;
import java.util.Map;

public class StagingPayrollWorkflowResponse {
    private java.util.UUID id;
    private String batchNumber;
    private String status;
    private String submittedAt;
    private String verifiedAt;
    private String approvedAt;
    private String rejectedAt;
    private String rejectionReason;
    private List<Map<String, Object>> events;

    public StagingPayrollWorkflowResponse() {}

    public StagingPayrollWorkflowResponse(java.util.UUID id, String batchNumber, String status,
            String submittedAt, String verifiedAt, String approvedAt, String rejectedAt,
            String rejectionReason, List<Map<String, Object>> events) {
        this.id = id;
        this.batchNumber = batchNumber;
        this.status = status;
        this.submittedAt = submittedAt;
        this.verifiedAt = verifiedAt;
        this.approvedAt = approvedAt;
        this.rejectedAt = rejectedAt;
        this.rejectionReason = rejectionReason;
        this.events = events;
    }

    public java.util.UUID getId() { return id; }
    public void setId(java.util.UUID id) { this.id = id; }
    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(String submittedAt) { this.submittedAt = submittedAt; }
    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String verifiedAt) { this.verifiedAt = verifiedAt; }
    public String getApprovedAt() { return approvedAt; }
    public void setApprovedAt(String approvedAt) { this.approvedAt = approvedAt; }
    public String getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(String rejectedAt) { this.rejectedAt = rejectedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public List<Map<String, Object>> getEvents() { return events; }
    public void setEvents(List<Map<String, Object>> events) { this.events = events; }
}