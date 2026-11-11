package SSGA_Backend.importer;

import java.util.List;
import java.util.Map;

public record ImportJobCorrectionRequest(List<RowCorrection> rows) {

    public record RowCorrection(
            int sourceRowNumber,
            Map<String, String> values,
            String errorCode
    ) {
    }
}
