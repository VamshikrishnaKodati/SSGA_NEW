package SSGA_Backend.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

/**
 * The guards that an annotation cannot express. These are the rules that stop a
 * caller escaping the permission model, so they are worth asserting directly.
 */
class AdminServiceTest {

    @Test
    void listRejectsOutOfRangePaging() {
        AdminUserService service = new AdminUserService(
                new FakeJdbcTemplate(), mock(RlsContext.class)
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> service.list(null, null, null, 0, 0)).getStatusCode()
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> service.list(null, null, null, 201, 0)).getStatusCode()
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                assertThrows(ResponseStatusException.class,
                        () -> service.list(null, null, null, 50, -1)).getStatusCode()
        );
    }

    @Test
    void theSystemAdministratorRoleIsImmutable() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        jdbc.row("SELECT code FROM public.roles", Map.of("code", "SYS_ADMIN"));

        AdminRoleService service = new AdminRoleService(jdbc, mock(RlsContext.class));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.replacePermissions(1L, List.of("payroll.view"))
        );

        assertEquals(HttpStatus.FORBIDDEN, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("System Administrator"));
    }

    @Test
    void anEmptyPermissionSetIsRefused() {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        jdbc.row("SELECT code FROM public.roles", Map.of("code", "DIV_ADMIN"));

        AdminRoleService service = new AdminRoleService(jdbc, mock(RlsContext.class));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.replacePermissions(2L, List.of())
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertTrue(thrown.getReason().contains("at least one permission"));
    }

    /**
     * The user page used to run three statements per row, so a page of 50 cost
     * 150 queries. The count must now stay fixed no matter how wide the page is.
     */
    @Test
    void listingUsersIssuesAFixedNumberOfQueries() {
        int pageOne = queryCountFor(1);
        int pageMany = queryCountFor(50);

        assertEquals(pageOne, pageMany, "query count grew with the page size");
        assertTrue(pageMany <= 6, "a user page should take at most 6 statements, took " + pageMany);
    }

    private static int queryCountFor(int limit) {
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate();
        // A non-zero count is needed, otherwise the method returns early.
        jdbc.answer("SELECT count(*) FROM public.users", 50L);

        new AdminUserService(jdbc, mock(RlsContext.class)).list(null, null, null, limit, 0);

        return jdbc.executed().size();
    }
}