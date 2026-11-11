package SSGA_Backend.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical SSGA column-mapping catalogue and the header-matching logic behind
 * intelligent auto-mapping. Pure and side-effect free so it can be unit tested
 * without a database or a workbook.
 *
 * Confidence is evidence based:
 *   HIGH      exact alias match and the sampled data type agrees with the field.
 *   REVIEW    a match was found but needs a human: ambiguous (more than one
 *             column matches), the sampled values disagree with the expected
 *             type, or the field is an identifier / classification field where a
 *             wrong guess is costly (employee code, HRMS ID, wing/division,
 *             salary component code, category).
 *   UNMATCHED no header matched any known alias.
 */
public final class ColumnMappingCatalog {

    public static final String HIGH = "HIGH";
    public static final String REVIEW = "REVIEW";
    public static final String UNMATCHED = "UNMATCHED";

    public static final String TEXT = "TEXT";
    public static final String NUMBER = "NUMBER";
    public static final String DATE = "DATE";
    public static final String UNKNOWN = "UNKNOWN";

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}"
            + "|\\d{4}[/\\-.]\\d{1,2}[/\\-.]\\d{1,2}"
            + "|\\d{1,2}[\\- ][A-Za-z]{3,9}[\\- ]\\d{2,4}"
            + "|[A-Za-z]{3,9}[\\- ]\\d{1,2},?\\s*\\d{2,4}"
            + "|[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{2,4})$"
    );

    private ColumnMappingCatalog() {
    }

    /** One canonical target field with its matching aliases. */
    public record FieldSpec(
            String key,
            String label,
            String type,
            boolean identifier,
            List<String> aliases
    ) {
    }

    /** The resolve result for one target field. */
    public record SheetField(
            String field,
            String label,
            String sourceHeader,
            String confidence,
            String reason,
            boolean identifier
    ) {
    }

    public static final Map<String, List<FieldSpec>> BY_TYPE = buildCatalogue();

    private static Map<String, List<FieldSpec>> buildCatalogue() {
        Map<String, List<FieldSpec>> map = new LinkedHashMap<>();

        map.put("EMPLOYEE_MASTER", List.of(
                f("employeeCode", "Employee code", TEXT, true,
                        "EMP CODE", "EMPCODE", "EMPLOYEE CODE", "EMPLOYEE CODE NO",
                        "EMP NO", "EMPNO", "EMPLOYEE NUMBER", "STAFF NO", "STAFF CODE"),
                f("hrmsId", "HRMS ID", TEXT, true,
                        "HRMS ID", "HRMSID", "HRMS NO", "HRMS", "HRMS CODE"),
                f("employeeName", "Employee name", TEXT, false,
                        "NAME", "EMPLOYEE NAME", "EMP NAME", "EMPLOYEENAME", "STAFF NAME",
                        "EMPLOYEE"),
                f("designation", "Designation", TEXT, false,
                        "DESIGNATION", "DESIG", "DESIGNATION NAME"),
                f("wing", "Wing", TEXT, true,
                        "WING", "WING NAME"),
                f("division", "Division", TEXT, true,
                        "DIVISION", "DIVISION NAME"),
                f("divisionId", "Division ID", NUMBER, true,
                        "DIVISION ID", "DIV ID", "DIVISION CODE", "DIV CODE"),
                f("dateOfJoining", "Date of joining", DATE, false,
                        "DATE OF JOINING", "DOJ", "JOINING DATE"),
                f("dateOfBirth", "Date of birth", DATE, false,
                        "DATE OF BIRTH", "DOB", "BIRTH DATE"),
                f("employmentType", "Employment type", TEXT, false,
                        "EMPLOYMENT TYPE", "EMP TYPE", "EMPLOYEE TYPE"),
                f("payScale", "Pay scale", TEXT, false,
                        "PAY SCALE", "PAYSCALE", "SCALE"),
                f("basicPay", "Basic pay", NUMBER, false,
                        "BASIC PAY", "BASICPAY", "BASIC"),
                f("contact", "Contact", TEXT, false,
                        "CONTACT", "CONTACT NO", "PHONE", "MOBILE", "MOBILE NO", "PHONE NO"),
                f("pan", "PAN", TEXT, false,
                        "PAN", "PAN NO", "PAN NUMBER"),
                f("aadhaar", "Aadhaar", TEXT, false,
                        "AADHAAR", "AADHAR", "AADHAAR NO", "AADHAR NO", "UID"),
                f("pf", "PF number", TEXT, false,
                        "PF", "PF NO", "PF NUMBER"),
                f("nps", "NPS number", TEXT, false,
                        "NPS", "NPS NO", "NPS NUMBER"),
                f("gpf", "GPF number", TEXT, false,
                        "GPF", "GPF NO", "GPF NUMBER"),
                f("email", "Email", TEXT, false,
                        "EMAIL", "EMAIL ID", "EMAIL ADDRESS"),
                f("employeeStatus", "Employee status", TEXT, false,
                        "EMPLOYEE STATUS", "EMP STATUS", "STATUS"),
                f("bankAccount", "Bank account", TEXT, false,
                        "BANK ACCOUNT", "BANK ACCOUNT NO", "ACCOUNT NO", "ACCOUNT NUMBER",
                        "BANK A/C", "BANK AC"),
                f("ifscCode", "IFSC code", TEXT, false,
                        "IFSC", "IFSC CODE"),
                f("bankName", "Bank name", TEXT, false,
                        "BANK NAME", "BANK"),
                f("branch", "Branch", TEXT, false,
                        "BRANCH", "BRANCH NAME")
        ));

        List<FieldSpec> employeeMasterAndComponents = List.of(
                f("employeeCode", "Employee code", TEXT, true,
                        "EMP CODE", "EMPCODE", "EMPLOYEE CODE", "EMPLOYEE CODE NO",
                        "EMP NO", "EMPNO", "EMPLOYEE NUMBER", "STAFF NO", "STAFF CODE"),
                f("hrmsId", "HRMS ID", TEXT, true,
                        "HRMS ID", "HRMSID", "HRMS NO", "HRMS", "HRMS CODE"),
                f("employeeName", "Employee name", TEXT, false,
                        "NAME", "EMPLOYEE NAME", "EMP NAME", "EMPLOYEENAME", "EMPLOYEE"),
                f("designation", "Designation", TEXT, false,
                        "DESIGNATION", "DESIG", "DESIGNATION NAME"),
                f("wing", "Wing", TEXT, true,
                        "WING", "WING NAME"),
                f("division", "Division", TEXT, true,
                        "DIVISION", "DIVISION NAME"),
                f("divisionId", "Division ID / code", TEXT, true,
                        "DIVISION ID", "DIV ID", "DIVISION CODE", "DIV CODE"),
                f("dateOfJoining", "Date of joining", DATE, false,
                        "DATE OF JOINING", "DOJ", "JOINING DATE"),
                f("dateOfBirth", "Date of birth", DATE, false,
                        "DATE OF BIRTH", "DOB", "BIRTH DATE"),
                f("employmentType", "Employment type", TEXT, false,
                        "EMPLOYMENT TYPE", "EMP TYPE", "EMPLOYEE TYPE"),
                f("payScale", "Pay scale", TEXT, false,
                        "PAY SCALE", "PAYSCALE", "SCALE"),
                f("contact", "Contact", TEXT, false,
                        "CONTACT", "CONTACT NO", "PHONE", "MOBILE", "MOBILE NO", "PHONE NO"),
                f("pan", "PAN", TEXT, false,
                        "PAN", "PAN NO", "PAN NUMBER"),
                f("aadhaar", "Aadhaar", TEXT, false,
                        "AADHAAR", "AADHAR", "AADHAAR NO", "AADHAR NO", "UID"),
                // Combined sheets also carry salary-component columns. A bare
                // PF/NPS/GPF header there is the deduction component, not the
                // identity number, so only explicit "... number" aliases are used.
                f("pf", "PF number", TEXT, false,
                        "PF NO", "PF NUMBER"),
                f("nps", "NPS number", TEXT, false,
                        "NPS NO", "NPS NUMBER"),
                f("gpf", "GPF number", TEXT, false,
                        "GPF NO", "GPF NUMBER"),
                f("email", "Email", TEXT, false,
                        "EMAIL", "EMAIL ID", "EMAIL ADDRESS"),
                f("employeeStatus", "Employee status", TEXT, false,
                        "EMPLOYEE STATUS", "EMP STATUS", "STATUS"),
                f("bankAccount", "Bank account", TEXT, false,
                        "BANK ACCOUNT", "BANK ACCOUNT NO", "ACCOUNT NO", "ACCOUNT NUMBER",
                        "BANK A/C", "BANK AC"),
                f("ifscCode", "IFSC code", TEXT, false,
                        "IFSC", "IFSC CODE"),
                f("bankName", "Bank name", TEXT, false,
                        "BANK NAME", "BANK"),
                f("branch", "Branch", TEXT, false,
                        "BRANCH", "BRANCH NAME"),
                f("effectiveFrom", "Effective from", DATE, false,
                        "EFFECTIVE FROM", "EFFECTIVE FROM DATE", "EFF FROM", "FROM DATE",
                        "EFFECTIVE DATE", "WEF", "DATE"),
                f("effectiveTo", "Effective to", DATE, false,
                        "EFFECTIVE TO", "EFF TO", "TO DATE"),
                f("componentCode", "Component code", TEXT, true,
                        "COMPONENT CODE", "COMP CODE", "COMPONENT", "COMPONENTCODE",
                        "SALARY COMPONENT", "SALARY COMPONENT CODE"),
                f("componentName", "Component name", TEXT, false,
                        "COMPONENT NAME", "COMP NAME", "SALARY COMPONENT NAME"),
                f("category", "Category", TEXT, true,
                        "CATEGORY", "COMPONENT CATEGORY", "EARNING DEDUCTION", "EARN DEDUCT"),
                f("amount", "Amount", NUMBER, false,
                        "AMOUNT", "VALUE", "SALARY AMOUNT", "AMT")
        );
        map.put("EMPLOYEE_MASTER_AND_COMPONENTS", employeeMasterAndComponents);

        map.put("SALARY_ENTITLEMENTS", List.of(
                f("employeeCode", "Employee code", TEXT, true,
                        "EMP CODE", "EMPCODE", "EMPLOYEE CODE", "EMPLOYEE CODE NO",
                        "EMP NO", "EMPNO", "EMPLOYEE NUMBER", "STAFF NO", "STAFF CODE"),
                f("hrmsId", "HRMS ID", TEXT, true,
                        "HRMS ID", "HRMSID", "HRMS NO", "HRMS", "HRMS CODE"),
                f("aadhaar", "Aadhaar", TEXT, true,
                        "AADHAAR", "AADHAR", "AADHAAR NO", "AADHAR NO", "UID"),
                f("pan", "PAN", TEXT, true,
                        "PAN", "PAN NO", "PAN NUMBER"),
                f("contact", "Contact", TEXT, true,
                        "CONTACT", "CONTACT NO", "PHONE", "MOBILE", "MOBILE NO", "PHONE NO"),
                f("email", "Email", TEXT, true,
                        "EMAIL", "EMAIL ID", "EMAIL ADDRESS"),
                f("pf", "PF number", TEXT, true,
                        "PF NO", "PF NUMBER"),
                f("nps", "NPS number", TEXT, true,
                        "NPS NO", "NPS NUMBER"),
                f("gpf", "GPF number", TEXT, true,
                        "GPF NO", "GPF NUMBER"),
                f("employeeName", "Employee name", TEXT, false,
                        "NAME", "EMPLOYEE NAME", "EMP NAME", "EMPLOYEENAME", "EMPLOYEE"),
                f("division", "Division", TEXT, true,
                        "DIVISION", "DIVISION NAME"),
                f("divisionId", "Division ID", NUMBER, true,
                        "DIVISION ID", "DIV ID", "DIVISION CODE", "DIV CODE"),
                f("effectiveFrom", "Effective from", DATE, false,
                        "EFFECTIVE FROM", "EFFECTIVE FROM DATE", "EFF FROM", "FROM DATE",
                        "EFFECTIVE DATE", "WEF", "DATE"),
                f("effectiveTo", "Effective to", DATE, false,
                        "EFFECTIVE TO", "EFF TO", "TO DATE"),
                f("componentCode", "Component code", TEXT, true,
                        "COMPONENT CODE", "COMP CODE", "COMPONENT", "COMPONENTCODE",
                        "SALARY COMPONENT", "SALARY COMPONENT CODE"),
                f("componentName", "Component name", TEXT, false,
                        "COMPONENT NAME", "COMP NAME", "SALARY COMPONENT NAME"),
                f("category", "Category", TEXT, true,
                        "CATEGORY", "COMPONENT CATEGORY", "EARNING DEDUCTION", "EARN DEDUCT"),
                f("amount", "Amount", NUMBER, false,
                        "AMOUNT", "VALUE", "SALARY AMOUNT", "AMT")
        ));

        map.put("PAYROLL_HISTORY", List.of(
                f("employeeCode", "Employee code", TEXT, true,
                        "EMP CODE", "EMPCODE", "EMPLOYEE CODE", "EMPLOYEE CODE NO",
                        "EMP NO", "EMPNO", "EMPLOYEE NUMBER", "STAFF NO", "STAFF CODE"),
                f("hrmsId", "HRMS ID", TEXT, true,
                        "HRMS ID", "HRMSID", "HRMS NO", "HRMS", "HRMS CODE"),
                f("aadhaar", "Aadhaar", TEXT, true,
                        "AADHAAR", "AADHAR", "AADHAAR NO", "AADHAR NO", "UID"),
                f("pan", "PAN", TEXT, true,
                        "PAN", "PAN NO", "PAN NUMBER"),
                f("contact", "Contact", TEXT, true,
                        "CONTACT", "CONTACT NO", "PHONE", "MOBILE", "MOBILE NO", "PHONE NO"),
                f("email", "Email", TEXT, true,
                        "EMAIL", "EMAIL ID", "EMAIL ADDRESS"),
                f("pf", "PF number", TEXT, true,
                        "PF NO", "PF NUMBER"),
                f("nps", "NPS number", TEXT, true,
                        "NPS NO", "NPS NUMBER"),
                f("gpf", "GPF number", TEXT, true,
                        "GPF NO", "GPF NUMBER"),
                f("employeeName", "Employee name", TEXT, false,
                        "NAME", "EMPLOYEE NAME", "EMP NAME", "EMPLOYEENAME", "EMPLOYEE"),
                f("division", "Division", TEXT, true,
                        "DIVISION", "DIVISION NAME"),
                f("divisionId", "Division ID", NUMBER, true,
                        "DIVISION ID", "DIV ID", "DIVISION CODE", "DIV CODE"),
                f("period", "Period", TEXT, false,
                        "PERIOD", "PAY PERIOD", "SALARY PERIOD"),
                f("payrollMonth", "Payroll month", NUMBER, false,
                        "PAYROLL MONTH", "MONTH", "SALARY MONTH"),
                f("payrollYear", "Payroll year", NUMBER, false,
                        "PAYROLL YEAR", "YEAR", "SALARY YEAR"),
                f("componentCode", "Component code", TEXT, true,
                        "COMPONENT CODE", "COMP CODE", "COMPONENT", "SALARY COMPONENT"),
                f("componentName", "Component name", TEXT, false,
                        "COMPONENT NAME", "COMP NAME"),
                f("category", "Category", TEXT, true,
                        "CATEGORY", "COMPONENT CATEGORY"),
                f("amount", "Amount", NUMBER, false,
                        "AMOUNT", "VALUE", "AMT"),
                f("gross", "Gross", NUMBER, false,
                        "GROSS", "GROSS SALARY", "GROSS PAY"),
                f("totalDeductions", "Total deductions", NUMBER, false,
                        "TOTAL DEDUCTIONS", "TOTAL DEDUCTION", "DEDUCTIONS", "TOTAL DED"),
                f("net", "Net", NUMBER, false,
                        "NET", "NET SALARY", "NET PAY"),
                f("transactionStatus", "Transaction status", TEXT, false,
                        "TRANSACTION STATUS", "TXN STATUS", "STATUS")
        ));

        map.put("SALARY_REVISIONS", List.of(
                f("hrmsId", "HRMS ID", TEXT, true,
                        "HRMS ID", "HRMSID", "HRMS NO", "HRMS"),
                f("aadhaar", "Aadhaar", TEXT, true,
                        "AADHAAR", "AADHAR", "AADHAAR NO", "AADHAR NO", "UID"),
                f("pan", "PAN", TEXT, true,
                        "PAN", "PAN NO", "PAN NUMBER"),
                f("contact", "Contact", TEXT, true,
                        "CONTACT", "CONTACT NO", "PHONE", "MOBILE", "MOBILE NO", "PHONE NO"),
                f("email", "Email", TEXT, true,
                        "EMAIL", "EMAIL ID", "EMAIL ADDRESS"),
                f("pf", "PF number", TEXT, true,
                        "PF NO", "PF NUMBER"),
                f("nps", "NPS number", TEXT, true,
                        "NPS NO", "NPS NUMBER"),
                f("gpf", "GPF number", TEXT, true,
                        "GPF NO", "GPF NUMBER"),
                f("employeeName", "Employee name", TEXT, false,
                        "NAME", "EMPLOYEE NAME", "EMP NAME", "EMPLOYEE"),
                f("effectiveFrom", "Effective from", DATE, false,
                        "EFFECTIVE FROM", "EFF FROM", "FROM DATE", "EFFECTIVE DATE", "WEF"),
                f("componentCode", "Component code", TEXT, true,
                        "COMPONENT CODE", "COMP CODE", "COMPONENT", "SALARY COMPONENT"),
                f("componentName", "Component name", TEXT, false,
                        "COMPONENT NAME", "COMP NAME"),
                f("oldAmount", "Old amount", NUMBER, false,
                        "OLD AMOUNT", "PREVIOUS AMOUNT", "OLD AMT"),
                f("newAmount", "New amount", NUMBER, false,
                        "NEW AMOUNT", "REVISED AMOUNT", "NEW AMT"),
                f("amount", "Amount", NUMBER, false,
                        "AMOUNT", "VALUE", "AMT"),
                f("reason", "Reason", TEXT, false,
                        "REASON", "REMARKS", "COMMENTS")
        ));

        return Map.copyOf(map);
    }

    private static FieldSpec f(
            String key,
            String label,
            String type,
            boolean identifier,
            String... aliases
    ) {
        List<String> normalized = new ArrayList<>(aliases.length);

        for (String alias : aliases) {
            normalized.add(normalize(alias));
        }

        return new FieldSpec(key, label, type, identifier, List.copyOf(normalized));
    }

    public static boolean supportsType(String type) {
        return BY_TYPE.containsKey(type);
    }

    /**
     * Resolve every canonical field for the given sheet type against the
     * detected headers and (optional) sampled cell values.
     *
     * @param type    confirmed or suggested sheet type
     * @param headers detected source headers in column order
     * @param samples header -> a handful of non-blank cell values from that column
     */
    public static List<SheetField> suggestFields(
            String type,
            List<String> headers,
            Map<String, List<String>> samples
    ) {
        List<FieldSpec> specs = BY_TYPE.get(type);

        if (specs == null) {
            return List.of();
        }

        Map<String, String> headerByNormalized = new LinkedHashMap<>();
        Map<String, Integer> normalizedCounts = new LinkedHashMap<>();
        Map<String, String> normalizedToOriginal = new LinkedHashMap<>();

        for (String header : headers) {
            if (header == null || header.isBlank()) {
                continue;
            }

            String normalized = normalize(header);

            if (normalized.isEmpty()) {
                continue;
            }

            normalizedCounts.merge(normalized, 1, Integer::sum);
            normalizedToOriginal.putIfAbsent(normalized, header);
        }

        List<SheetField> result = new ArrayList<>(specs.size());

        for (FieldSpec spec : specs) {
            LinkedHashSet<String> matches = new LinkedHashSet<>();

            for (String alias : spec.aliases()) {
                Integer count = normalizedCounts.get(alias);

                if (count == null || count == 0) {
                    continue;
                }

                if (count > 1) {
                    matches.clear();
                    // Ambiguous: multiple headers normalize to this alias
                    matches = null;
                    break;
                }

                String header = normalizedToOriginal.get(alias);

                if (header != null) {
                    matches.add(header);
                }
            }

            if (matches == null || matches.size() > 1) {
                result.add(new SheetField(
                        spec.key(), spec.label(), null, REVIEW,
                        "More than one column matches this field — confirm the correct source column.",
                        spec.identifier()
                ));
                continue;
            }

            if (matches.isEmpty()) {
                result.add(new SheetField(
                        spec.key(), spec.label(), null, UNMATCHED,
                        "No source column matched a known alias.", spec.identifier()
                ));
                continue;
            }

            String header = matches.iterator().next();
            String observed = observedType(samples.getOrDefault(header, List.of()));
            boolean typeCompatible = compatible(spec.type(), observed);

            String confidence;
            String reason;

            if (!typeCompatible) {
                confidence = REVIEW;
                reason = "Header matched, but sampled values look like " + observed
                        + " while the field expects " + spec.type() + ".";
            } else if (spec.identifier()) {
                confidence = REVIEW;
                reason = "Identifier or classification field — human confirmation required.";
            } else {
                confidence = HIGH;
                reason = "Exact alias match; sampled values look like " + spec.type() + ".";
            }

            result.add(new SheetField(
                    spec.key(), spec.label(), header, confidence, reason, spec.identifier()
            ));
        }

        return result;
    }

    /** Classify a column's sampled values as NUMBER, DATE, TEXT or UNKNOWN. */
    public static String observedType(List<String> samples) {
        int total = 0;
        int numeric = 0;
        int dates = 0;

        for (String sample : samples) {
            if (sample == null || sample.isBlank()) {
                continue;
            }

            total++;

            if (looksNumeric(sample)) {
                numeric++;
            }

            if (looksDate(sample)) {
                dates++;
            }
        }

        if (total == 0) {
            return UNKNOWN;
        }

        if (numeric * 10 >= total * 7) {
            return NUMBER;
        }

        if (dates * 10 >= total * 7) {
            return DATE;
        }

        return TEXT;
    }

    public static boolean compatible(String fieldType, String observed) {
        if (UNKNOWN.equals(observed)) {
            return true;
        }

        if (NUMBER.equals(fieldType)) {
            return NUMBER.equals(observed);
        }

        if (DATE.equals(fieldType)) {
            return DATE.equals(observed);
        }

        // TEXT and identifiers accept any observed shape.
        return true;
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * Fuzzy key for salary-component matching. Source headers such as
     * {@code BASIC_PAY} or {@code OVERTIME} resolve to master codes
     * {@code BASIC} / {@code OT} when no exact match exists.
     */
    public static String componentKey(String value) {
        String key = normalize(value);

        if (key.isEmpty()) {
            return key;
        }

        if (key.equals("OVERTIME") || key.equals("OVERTIME_PAY")) {
            return "OT";
        }

        if (key.equals("DEARNESS_ALLOWANCE") || key.equals("DEARNESS_PAY")) {
            return "DA";
        }

        if (key.equals("HOUSE_RENT_ALLOWANCE")
                || key.equals("HOUSE_RENT_PAY")
                || key.equals("HOUSE_RENT")) {
            return "HRA";
        }

        String stripped = key.replaceAll(
                "_(PAY|AMOUNT|VALUE|ALLOWANCE)$", "");

        return stripped.isEmpty() ? key : stripped;
    }

    private static boolean looksNumeric(String value) {
        String trimmed = value.trim();

        if (trimmed.isEmpty()) {
            return false;
        }

        String cleaned = trimmed
                .replace("\u00A0", "")
                .replace(",", "")
                .replace("\u20B9", "")
                .replace("$", "")
                .replace("\u20AC", "")
                .trim();

        boolean parentheses = cleaned.startsWith("(") && cleaned.endsWith(")");

        if (parentheses) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }

        if (cleaned.endsWith("%")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        cleaned = cleaned.trim();

        if (cleaned.isEmpty()) {
            return false;
        }

        int digits = 0;

        for (int index = 0; index < cleaned.length(); index++) {
            char character = cleaned.charAt(index);

            if (Character.isDigit(character)) {
                digits++;
                continue;
            }

            if (character == '.'
                    || character == '-'
                    || character == '+'
                    || character == 'e'
                    || character == 'E') {
                continue;
            }

            return false;
        }

        return digits > 0;
    }

    private static boolean looksDate(String value) {
        return DATE_PATTERN.matcher(value.trim()).matches();
    }
}
