package SSGA_Backend.importer;

import java.util.List;

public class StagingPayrollDetailPageResponse {
    private List<StagingDetailRow> rows;
    private int total;
    private int page;
    private int size;
    private int totalPages;

    public StagingPayrollDetailPageResponse() {}

    public StagingPayrollDetailPageResponse(List<StagingDetailRow> rows, int total, int page, int size, int totalPages) {
        this.rows = rows;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = totalPages;
    }

    public List<StagingDetailRow> getRows() { return rows; }
    public void setRows(List<StagingDetailRow> rows) { this.rows = rows; }
    public int getTotal() { return total; }
    public void setTotal(int total) { this.total = total; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}