
package SSGA_Backend.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.json.JsonMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.security.SsgaPrincipal;

@Service
public class ImportJobService {

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;
    private final JsonMapper jsonMapper;
    private final Path storageRoot;
    private final long maxFileSizeBytes;

    public ImportJobService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext,
            JsonMapper jsonMapper,
            @Value("${ssga.import.storage-dir:}") String storageDirectory,
            @Value("${ssga.import.max-file-size-bytes:26214400}")
                    long maxFileSizeBytes
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
        this.jsonMapper = jsonMapper;
        this.maxFileSizeBytes = maxFileSizeBytes;

        Path configuredPath;

        if (storageDirectory == null || storageDirectory.isBlank()) {
            configuredPath = Paths.get(
                    System.getProperty("user.home"),
                    "ssga-data",
                    "imports"
            );
        } else {
            configuredPath = Paths.get(storageDirectory);
        }

        this.storageRoot = configuredPath.toAbsolutePath().normalize();
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ImportJobUploadResponse upload(MultipartFile file)
            throws IOException {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Please select an Excel file."
            );
        }

        if (file.getSize() > maxFileSizeBytes) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "The uploaded file exceeds the configured size limit."
            );
        }

        if (file.getSize() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The uploaded file is empty."
            );
        }

        String fileName = safeFileName(file.getOriginalFilename());
        String extension = getExtension(fileName);
        boolean csv = ".csv".equals(extension);

        if (!List.of(".xlsx", ".xlsm", ".xls", ".csv").contains(extension)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only .xlsx, .xlsm, .xls and .csv files are supported."
            );
        }

        // Basic content sniffing
        try {
            byte[] header = file.getBytes();
            if (header.length >= 4) {
                // Excel files are ZIP (PK) for xlsx/xlsm
            if (".xlsx".equals(extension) || ".xlsm".equals(extension)) {
                if (!(header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Excel file format.");
                }
            } else if (".xls".equals(extension)) {
                // OLE2 magic D0 CF 11 E0
                if (!(header[0] == (byte)0xD0 && header[1] == (byte)0xCF && header[2] == (byte)0x11 && header[3] == (byte)0xE0)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Excel file format.");
                }
            } else if (".csv".equals(extension)) {
                // basic check - no null bytes
                for (int i = 0; i < Math.min(header.length, 8192); i++) {
                    if (header[i] == 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CSV file format.");
                    }
                }
            }
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File validation failed.", ex);
        }

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !(authentication.getPrincipal()
                        instanceof SsgaPrincipal user)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated user not found."
            );
        }

        Path savedFile = null;

        try {
            // Apply RLS context inside the current database transaction.
            rlsContext.setCurrentUser();

            String monthFolder = YearMonth.now().toString();

            Path destinationFolder =
                    storageRoot.resolve(monthFolder).normalize();

            if (!destinationFolder.startsWith(storageRoot)) {
                throw new IllegalStateException(
                        "Invalid import storage path."
                );
            }

            Files.createDirectories(destinationFolder);

            // CSV uploads are normalised to a single-sheet .xlsx so every
            // downstream step can read them with the Excel code paths.
            String storedExtension = csv ? ".xlsx" : extension;

            // Generate a server-side name instead of trusting the uploaded name.
            String storedName = UUID.randomUUID() + storedExtension;
            savedFile = destinationFolder.resolve(storedName).normalize();

            if (fileName.contains("..") || !savedFile.startsWith(storageRoot)) {
                throw new IllegalStateException(
                        "Invalid import file path."
                );
            }

            if (csv) {
                byte[] csvBytes = file.getBytes();
                // Basic CSV formula injection guard at upload time (store as-is, sanitized on export)
                CsvWorkbookConverter.writeWorkbook(
                        csvBytes, fileName, savedFile);
            } else {
                try (InputStream input = file.getInputStream()) {
                    Files.copy(input, savedFile);
                }
            }

            // Confirm the uploaded file can be opened as an Excel workbook.
            List<String> sheetNames = readSheetNames(savedFile);
            enforceRowLimits(savedFile);
            String sha256 = calculateSha256(savedFile);

            // Basic filename/path validation
            if (fileName.contains("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name.");
            }

            // Reject an identical file previously uploaded by this user.
            List<Long> duplicates = jdbcTemplate.query(
                    """
                    SELECT id
                    FROM public.import_jobs
                    WHERE file_sha256 = ?
                      AND uploaded_by = ?
                      AND status NOT IN ('FAILED', 'REJECTED')
                    ORDER BY uploaded_at DESC
                    LIMIT 1
                    """,
                    (rs, rowNum) -> rs.getLong("id"),
                    sha256,
                    user.getUserId()
            );

            if (!duplicates.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "This workbook was already uploaded as import job "
                                + duplicates.get(0)
                                + ". Review the existing job first."
                );
            }

            String fileKey = storageRoot.relativize(savedFile)
                    .toString()
                    .replace('\\', '/');
            if (fileKey.contains("..")) {
                throw new IllegalStateException("Invalid import file path.");
            }

            Map<String, Object> sourceInfo = new LinkedHashMap<>();
            sourceInfo.put("sheetNames", sheetNames);
            sourceInfo.put("fileSizeBytes", file.getSize());
            sourceInfo.put("extension", extension);
            sourceInfo.put("convertedFromCsv", csv);

            String sourceMapping =
                    jsonMapper.writeValueAsString(sourceInfo);

            String detectedFormat = csv
                    ? "CSV_WORKBOOK"
                    : "EXCEL_WORKBOOK";

            Long jobId = jdbcTemplate.queryForObject(
                    """
                    INSERT INTO public.import_jobs (
                        source_type,
                        status,
                        file_name,
                        file_key,
                        file_sha256,
                        detected_format,
                        source_mapping,
                        uploaded_by,
                        notes
                    )
                    VALUES (
                        'UNCLASSIFIED',
                        'UPLOADED',
                        ?, ?, ?, ?::varchar, ?::jsonb, ?, ?
                    )
                    RETURNING id
                    """,
                    Long.class,
                    fileName,
                    fileKey,
                    sha256,
                    detectedFormat,
                    sourceMapping,
                    user.getUserId(),
                    "Workbook stored. Data classification and validation "
                            + "are pending."
            );

            if (jobId == null) {
                throw new IllegalStateException(
                        "Unable to create the import job."
                );
            }

            return new ImportJobUploadResponse(
                    jobId,
                    "UPLOADED",
                    "UNCLASSIFIED",
                    fileName,
                    file.getSize(),
                    sha256,
                    List.copyOf(sheetNames),
                    "Workbook stored successfully. No salary data has been imported."
            );

        } catch (IOException | RuntimeException ex) {
            // Avoid leaving a file behind when upload processing fails.
            if (savedFile != null) {
                try {
                    Files.deleteIfExists(savedFile);
                } catch (IOException cleanupError) {
                    ex.addSuppressed(cleanupError);
                }
            }

            throw ex;
        } finally {
            // ensure bytes not held unnecessarily
        }
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public List<ImportJobSummaryResponse> list() {
        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    status,
                    source_type,
                    file_name,
                    total_rows,
                    valid_rows,
                    error_rows,
                    warning_rows,
                    uploaded_at::text AS uploaded_at
                FROM public.import_jobs
                ORDER BY uploaded_at DESC
                LIMIT 100
                """,
                (rs, rowNum) -> new ImportJobSummaryResponse(
                        rs.getLong("id"),
                        rs.getString("status"),
                        rs.getString("source_type"),
                        rs.getString("file_name"),
                        rs.getInt("total_rows"),
                        rs.getInt("valid_rows"),
                        rs.getInt("error_rows"),
                        rs.getInt("warning_rows"),
                        rs.getString("uploaded_at")
                )
        );
    }

    private List<String> readSheetNames(Path filePath) throws IOException {
        try (Workbook workbook =
                     WorkbookFactory.create(filePath.toFile())) {

            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "The workbook contains no sheets."
                );
            }

            List<String> names = new ArrayList<>();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                names.add(workbook.getSheetName(i));
            }

            return names;

        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The uploaded file is not a readable Excel workbook.",
                    ex
            );
        }
    }

    private void enforceRowLimits(Path filePath) {
        final int maxTotalRows = 10000;
        try (Workbook workbook = WorkbookFactory.create(filePath.toFile())) {
            long total = 0;
            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                var sheet = workbook.getSheetAt(s);
                if (sheet == null) {
                    continue;
                }
                total += Math.max(0, sheet.getLastRowNum() + 1);
            }
            if (total > maxTotalRows) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "The workbook exceeds the maximum allowed rows (" + maxTotalRows + ")."
                );
            }
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to validate workbook row limits.",
                    ex
            );
        }
    }

    static String calculateSha256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (InputStream input = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            return HexFormat.of().formatHex(digest.digest());

        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable in this Java runtime.",
                    ex
            );
        }
    }

    private String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The original file name is missing."
            );
        }

        String name = originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();

        if (name.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "The file name is invalid."
            );
        }

        String extension = getExtension(name);

        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name.");
        }

        if (name.length() > 255) {
            int maximumBaseLength = 255 - extension.length();
            String base = name.substring(
                    0,
                    name.length() - extension.length()
            );

            name = base.substring(
                    0,
                    Math.min(base.length(), maximumBaseLength)
            ) + extension;
        }

        return name;
    }

    private String getExtension(String name) {
        int dotIndex = name.lastIndexOf('.');

        if (dotIndex < 0) {
            return "";
        }

        return name.substring(dotIndex).toLowerCase(Locale.ROOT);
    }
}
