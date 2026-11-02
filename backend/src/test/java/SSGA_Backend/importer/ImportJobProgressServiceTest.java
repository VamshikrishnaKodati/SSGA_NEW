package SSGA_Backend.importer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import SSGA_Backend.security.RlsContext;
import SSGA_Backend.support.FakeJdbcTemplate;

class ImportJobProgressServiceTest {

    private FakeJdbcTemplate jdbc;
    private ImportJobProgressService service;

    @BeforeEach
    void setUp() {
        jdbc = new FakeJdbcTemplate();
        jdbc.answer("app.current_user_id", 42L);
        service = new ImportJobProgressService(jdbc, mock(RlsContext.class));
    }

    @Test
    void progressEventsPersistBackendCountsAndLogText() {
        service.recordProgress(
                17L, 400, 100, 90, 4, 6, "WARN",
                "Processed 100 of 400 records."
        );

        assertTrue(jdbc.executedSqlContaining(
                "INSERT INTO public.import_job_progress_events"));
        assertTrue(jdbc.calledWith(
                "[17, 42, WARN, Processed 100 of 400 records., 400, 100, 90, 4, 6]"
        ));
    }

    @Test
    void completionEventStoresTheBackendFinalStatusAndTotals() {
        service.recordCompletion(
                17L, "PARTIALLY_IMPORTED", 400, 400, 380, 5, 15,
                "Import completed."
        );

        assertTrue(jdbc.executedSqlContaining(
                "INSERT INTO public.import_job_progress_events"));
        assertTrue(jdbc.calledWith(
                "[17, 42, PARTIALLY_IMPORTED, Import completed., 400, 400, 380, 5, 15]"
        ));
    }
}
