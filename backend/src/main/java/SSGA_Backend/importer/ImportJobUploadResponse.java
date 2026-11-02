
package SSGA_Backend.importer;

import java.util.List;

public record ImportJobUploadResponse(
        long jobId,
        String status,
        String sourceType,
        String fileName,
        long fileSizeBytes,
        String sha256,
        List<String> sheetNames,
        String message
) {
}
