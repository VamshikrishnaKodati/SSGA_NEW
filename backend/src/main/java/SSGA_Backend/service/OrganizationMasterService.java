package SSGA_Backend.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.controller.ParentGroupRequest;
import SSGA_Backend.controller.ParentGroupResponse;
import SSGA_Backend.controller.PayrollUnitDetailResponse;
import SSGA_Backend.controller.PayrollUnitRequest;
import SSGA_Backend.controller.PayrollUnitResponse;
import SSGA_Backend.controller.PayrollUnitStatusRequest;
import SSGA_Backend.security.RlsContext;

/**
 * Organization Master: Organization -> Parent Group -> Payroll Unit -> Employee.
 *
 * Reads are open to organizational-scope readers (see the controller); writes
 * are restricted to the organization.manage permission and audit every change.
 * Payroll-unit collaborators become "employees.payroll_unit_id"; the display
 * name can change freely without breaking those stable internal references.
 */
@Service
public class OrganizationMasterService {

    private static final Set<String> UNIT_TYPES = Set.of(
            "DIVISION", "CIRCLE", "PROJECT_DIVISION", "PCC",
            "TRANSMISSION_CIRCLE", "STP_CIRCLE", "ELECTRICAL_DIVISION",
            "QUALITY_VIGILANCE", "CENTRAL_STORES", "BOARD_OFFICE"
    );

    private static final String UNIT_SELECT = """
            SELECT pu.id,
                   pu.parent_group_id,
                   pg.name AS parent_group_name,
                   pu.code,
                   pu.name,
                   pu.unit_type,
                   pu.status,
                   pu.verification_status,
                   pu.verification_note,
                   (SELECT count(*) FROM public.employees e
                     WHERE e.payroll_unit_id = pu.id) AS employee_count
            FROM public.payroll_units pu
            JOIN public.parent_groups pg ON pg.id = pu.parent_group_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RlsContext rlsContext;

    public OrganizationMasterService(
            JdbcTemplate jdbcTemplate,
            RlsContext rlsContext
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rlsContext = rlsContext;
    }

    // =========================================================
    // PARENT GROUPS
    // =========================================================

    @Transactional(transactionManager = "dataSourceTransactionManager", readOnly = true)
    public List<ParentGroupResponse> listParentGroups() {

        rlsContext.setCurrentUser();

        return jdbcTemplate.query(
                """
                SELECT pg.id,
                       pg.organization_id,
                       o.name AS organization_name,
                       pg.code,
                       pg.name,
                       pg.status,
                       (SELECT count(*) FROM public.payroll_units pu
                         WHERE pu.parent_group_id = pg.id) AS unit_count
                FROM public.parent_groups pg
                JOIN public.organizations o ON o.id = pg.organization_id
                ORDER BY pg.id
                """,
                (rs, rowNum) -> new ParentGroupResponse(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("organization_name"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getLong("unit_count")
                )
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ParentGroupResponse createParentGroup(ParentGroupRequest request) {

        String code = requireNotBlank(request.code(), "Parent group code is required");
        String name = requireNotBlank(request.name(), "Parent group name is required");
        String status = normalizeStatus(request.status(), true);

        long id = jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            long organizationId = soleOrganizationId(connection);

            long newId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO public.parent_groups
                        (organization_id, name, code, status)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                    """
            )) {
                statement.setLong(1, organizationId);
                statement.setString(2, name);
                statement.setString(3, code);
                statement.setString(4, status);

                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Parent group was not created");
                    }
                    newId = rs.getLong(1);
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "A parent group with this code already exists."
                    );
                }
            }

            audit(connection, "PARENT_GROUP_CREATE", "PARENT_GROUP", newId, code, name, null);

            return newId;
        });

        return parentGroup(id);
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public ParentGroupResponse updateParentGroup(long id, ParentGroupRequest request) {

        String status = request.status() == null
                ? null
                : normalizeStatus(request.status(), false);

        jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE public.parent_groups
                    SET name = COALESCE(?, name),
                        code = COALESCE(?, code),
                        status = COALESCE(?, status),
                        updated_at = now()
                    WHERE id = ?
                    """
            )) {
                setNullableString(statement, 1, request.name());
                setNullableString(statement, 2, request.code());
                setNullableString(statement, 3, status);
                statement.setLong(4, id);

                try {
                    if (statement.executeUpdate() == 0) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Parent group not found"
                        );
                    }
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "A parent group with this code already exists."
                    );
                }
            }

            String[] codeName = readParentGroupCodeName(connection, id);
            audit(connection, "PARENT_GROUP_UPDATE", "PARENT_GROUP", id, codeName[0], codeName[1], null);

            return null;
        });

        return parentGroup(id);
    }

    private ParentGroupResponse parentGroup(long id) {

        return jdbcTemplate.query(
                """
                SELECT pg.id,
                       pg.organization_id,
                       o.name AS organization_name,
                       pg.code,
                       pg.name,
                       pg.status,
                       (SELECT count(*) FROM public.payroll_units pu
                         WHERE pu.parent_group_id = pg.id) AS unit_count
                FROM public.parent_groups pg
                JOIN public.organizations o ON o.id = pg.organization_id
                WHERE pg.id = ?
                """,
                (rs, rowNum) -> new ParentGroupResponse(
                        rs.getLong("id"),
                        rs.getLong("organization_id"),
                        rs.getString("organization_name"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getLong("unit_count")
                ),
                id
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Parent group could not be read back"
        ));
    }

    // =========================================================
    // PAYROLL UNITS
    // =========================================================

    @Transactional(transactionManager = "dataSourceTransactionManager", readOnly = true)
    public List<PayrollUnitResponse> listPayrollUnits(
            Long parentGroupId,
            String unitType,
            String status,
            String verificationStatus,
            String search
    ) {

        rlsContext.setCurrentUser();

        StringBuilder sql = new StringBuilder(UNIT_SELECT).append(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (parentGroupId != null) {
            sql.append(" AND pu.parent_group_id = ?");
            params.add(parentGroupId);
        }
        if (notBlank(unitType)) {
            sql.append(" AND pu.unit_type = ?");
            params.add(unitType.trim());
        }
        if (notBlank(status)) {
            sql.append(" AND pu.status = ?");
            params.add(status.trim());
        }
        if (notBlank(verificationStatus)) {
            sql.append(" AND pu.verification_status = ?");
            params.add(verificationStatus.trim());
        }
        if (notBlank(search)) {
            sql.append(" AND (pu.code ILIKE ? OR pu.name ILIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
        }

        sql.append(" ORDER BY pu.display_order, pu.id");

        return jdbcTemplate.query(
                sql.toString(),
                (rs, rowNum) -> mapUnit(rs),
                params.toArray()
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager", readOnly = true)
    public PayrollUnitDetailResponse getUnit(long id) {

        rlsContext.setCurrentUser();

        PayrollUnitResponse unit = jdbcTemplate.query(
                UNIT_SELECT + " WHERE pu.id = ?",
                (rs, rowNum) -> mapUnit(rs),
                id
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Payroll unit not found"
        ));

        List<PayrollUnitDetailResponse.EmployeeRef> employees = jdbcTemplate.query(
                """
                SELECT e.id,
                       e.hrms_id,
                       e.name,
                       des.name AS designation_name,
                       e.status
                FROM public.employees e
                LEFT JOIN public.designations des ON des.id = e.designation_id
                WHERE e.payroll_unit_id = ?
                ORDER BY e.name
                """,
                (rs, rowNum) -> new PayrollUnitDetailResponse.EmployeeRef(
                        rs.getLong("id"),
                        rs.getString("hrms_id"),
                        rs.getString("name"),
                        rs.getString("designation_name"),
                        rs.getString("status")
                ),
                id
        );

        return new PayrollUnitDetailResponse(
                unit.id(),
                unit.parentGroupId(),
                unit.parentGroupName(),
                unit.code(),
                unit.name(),
                unit.unitType(),
                unit.status(),
                unit.verificationStatus(),
                unit.verificationNote(),
                unit.employeeCount(),
                employees
        );
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public PayrollUnitDetailResponse createUnit(PayrollUnitRequest request) {

        if (request.parentGroupId() == null) {
            throw new IllegalArgumentException("A parent group is required");
        }

        String code = requireNotBlank(request.code(), "Unit code is required");
        String name = requireNotBlank(request.name(), "Unit name is required");
        String unitType = requireUnitType(request.unitType());
        String status = normalizeStatus(request.status(), true);
        String verification = normalizeVerification(request.verificationStatus(), true);
        String note = blankToNull(request.verificationNote());

        long id = jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            requireParentGroup(connection, request.parentGroupId());

            long newId;
            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO public.payroll_units
                        (parent_group_id, code, name, unit_type, status,
                         verification_status, verification_note)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    RETURNING id
                    """
            )) {
                statement.setLong(1, request.parentGroupId());
                statement.setString(2, code);
                statement.setString(3, name);
                statement.setString(4, unitType);
                statement.setString(5, status);
                statement.setString(6, verification);
                setNullableString(statement, 7, note);

                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Payroll unit was not created");
                    }
                    newId = rs.getLong(1);
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "A payroll unit with this code already exists."
                    );
                }
            }

            audit(connection, "PAYROLL_UNIT_CREATE", "PAYROLL_UNIT", newId, code, name, null);

            return newId;
        });

        return getUnit(id);
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public PayrollUnitDetailResponse updateUnit(long id, PayrollUnitRequest request) {

        String unitType = request.unitType() == null
                ? null
                : requireUnitType(request.unitType());
        String status = request.status() == null
                ? null
                : normalizeStatus(request.status(), false);
        String verification = request.verificationStatus() == null
                ? null
                : normalizeVerification(request.verificationStatus(), false);

        jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            if (request.parentGroupId() != null) {
                requireParentGroup(connection, request.parentGroupId());
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE public.payroll_units
                    SET parent_group_id = COALESCE(?, parent_group_id),
                        code = COALESCE(?, code),
                        name = COALESCE(?, name),
                        unit_type = COALESCE(?, unit_type),
                        status = COALESCE(?, status),
                        verification_status = COALESCE(?, verification_status),
                        verification_note = COALESCE(?, verification_note),
                        updated_at = now()
                    WHERE id = ?
                    """
            )) {
                setNullableLong(statement, 1, request.parentGroupId());
                setNullableString(statement, 2, request.code());
                setNullableString(statement, 3, request.name());
                setNullableString(statement, 4, unitType);
                setNullableString(statement, 5, status);
                setNullableString(statement, 6, verification);
                setNullableString(statement, 7, request.verificationNote());
                statement.setLong(8, id);

                try {
                    if (statement.executeUpdate() == 0) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Payroll unit not found"
                        );
                    }
                } catch (SQLException e) {
                    throw translateDuplicate(
                            e,
                            "A payroll unit with this code already exists."
                    );
                }
            }

            String[] codeName = readUnitCodeName(connection, id);
            audit(connection, "PAYROLL_UNIT_UPDATE", "PAYROLL_UNIT", id, codeName[0], codeName[1], null);

            return null;
        });

        return getUnit(id);
    }

    @Transactional(transactionManager = "dataSourceTransactionManager")
    public PayrollUnitDetailResponse changeUnitStatus(
            long id,
            PayrollUnitStatusRequest request
    ) {

        String status = normalizeStatus(request.status(), false);

        if ("INACTIVE".equals(status) && !notBlank(request.reason())) {
            throw new IllegalArgumentException(
                    "A reason is required to deactivate a payroll unit"
            );
        }

        String reason = blankToNull(request.reason());

        jdbcTemplate.execute((Connection connection) -> {

            rlsContext.setCurrentUser(connection);

            String[] codeName = readUnitCodeName(connection, id);

            try (PreparedStatement statement = connection.prepareStatement(
                    """
                    UPDATE public.payroll_units
                    SET status = ?, updated_at = now()
                    WHERE id = ?
                    """
            )) {
                statement.setString(1, status);
                statement.setLong(2, id);

                if (statement.executeUpdate() == 0) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payroll unit not found"
                    );
                }
            }

            audit(connection, "PAYROLL_UNIT_STATUS_CHANGE", "PAYROLL_UNIT",
                    id, codeName[0], codeName[1], reason);

            return null;
        });

        return getUnit(id);
    }

    // =========================================================
    // READ HELPERS
    // =========================================================

    private static PayrollUnitResponse mapUnit(ResultSet rs) throws SQLException {
        return new PayrollUnitResponse(
                rs.getLong("id"),
                rs.getLong("parent_group_id"),
                rs.getString("parent_group_name"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("unit_type"),
                rs.getString("status"),
                rs.getString("verification_status"),
                rs.getString("verification_note"),
                rs.getLong("employee_count")
        );
    }

    private long soleOrganizationId(Connection connection) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM public.organizations ORDER BY id LIMIT 1"
        ); ResultSet rs = statement.executeQuery()) {

            if (!rs.next()) {
                throw new IllegalStateException(
                        "No organization record exists to attach the parent group to"
                );
            }

            return rs.getLong(1);
        }
    }

    private void requireParentGroup(Connection connection, long parentGroupId)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM public.parent_groups WHERE id = ? AND status = 'ACTIVE'"
        )) {
            statement.setLong(1, parentGroupId);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "The selected parent group does not exist or is inactive"
                    );
                }
            }
        }
    }

    private String[] readUnitCodeName(Connection connection, long id)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, name FROM public.payroll_units WHERE id = ?"
        )) {
            statement.setLong(1, id);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Payroll unit not found"
                    );
                }
                return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
    }

    private String[] readParentGroupCodeName(Connection connection, long id)
            throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code, name FROM public.parent_groups WHERE id = ?"
        )) {
            statement.setLong(1, id);

            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Parent group not found"
                    );
                }
                return new String[]{rs.getString(1), rs.getString(2)};
            }
        }
    }

    // =========================================================
    // AUDIT
    // =========================================================

    private void audit(
            Connection connection,
            String action,
            String entityType,
            long entityId,
            String code,
            String name,
            String reason
    ) throws SQLException {

        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT app.write_audit_log(
                    ?::varchar, ?::varchar, ?::varchar,
                    NULL::bigint, NULL::bigint,
                    NULL::jsonb,
                    jsonb_build_object('code', ?::text, 'name', ?::text),
                    ?::text, NULL::uuid
                )
                """
        )) {
            statement.setString(1, action);
            statement.setString(2, entityType);
            statement.setString(3, String.valueOf(entityId));
            statement.setString(4, code);
            statement.setString(5, name);
            setNullableString(statement, 6, reason);
            statement.executeQuery();
        }
    }

    // =========================================================
    // VALIDATION HELPERS
    // =========================================================

    private static String requireNotBlank(String value, String message) {
        if (!notBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    static String requireUnitType(String value) {

        if (!notBlank(value)) {
            throw new IllegalArgumentException("Unit type is required");
        }

        String type = value.trim().toUpperCase();

        if (!UNIT_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown unit type: " + value);
        }

        return type;
    }

    static String normalizeStatus(String status, boolean defaultActive) {

        if (!notBlank(status)) {
            if (defaultActive) {
                return "ACTIVE";
            }
            throw new IllegalArgumentException("Status is required");
        }

        String value = status.trim().toUpperCase();

        if (!value.equals("ACTIVE") && !value.equals("INACTIVE")) {
            throw new IllegalArgumentException(
                    "Status must be ACTIVE or INACTIVE"
            );
        }

        return value;
    }

    static String normalizeVerification(String value, boolean defaultConfirmed) {

        if (!notBlank(value)) {
            if (defaultConfirmed) {
                return "CONFIRMED";
            }
            throw new IllegalArgumentException("Verification status is required");
        }

        String normalized = value.trim().toUpperCase();

        if (!normalized.equals("CONFIRMED")
                && !normalized.equals("PENDING_VERIFICATION")) {
            throw new IllegalArgumentException(
                    "Verification status must be CONFIRMED or PENDING_VERIFICATION"
            );
        }

        return normalized;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String blankToNull(String value) {
        return notBlank(value) ? value.trim() : null;
    }

    // =========================================================
    // JDBC HELPERS
    // =========================================================

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {

        if (!notBlank(value)) {
            statement.setNull(index, java.sql.Types.VARCHAR);
        } else {
            statement.setString(index, value.trim());
        }
    }

    private static void setNullableLong(
            PreparedStatement statement,
            int index,
            Long value
    ) throws SQLException {

        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static ResponseStatusException translateDuplicate(
            SQLException e,
            String message
    ) {

        if ("23505".equals(e.getSQLState())) {
            return new ResponseStatusException(HttpStatus.CONFLICT, message);
        }

        throw new IllegalStateException(
                "Unexpected database error: " + e.getMessage(),
                e
        );
    }
}
