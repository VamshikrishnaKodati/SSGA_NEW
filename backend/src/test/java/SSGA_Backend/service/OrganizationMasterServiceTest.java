package SSGA_Backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Pure unit checks for the Organization Master validation rules (no database).
 */
class OrganizationMasterServiceTest {

    @Test
    void acceptsKnownUnitTypesCaseInsensitively() {
        assertEquals("DIVISION", OrganizationMasterService.requireUnitType("division"));
        assertEquals("TRANSMISSION_CIRCLE",
                OrganizationMasterService.requireUnitType(" transmission_circle "));
    }

    @Test
    void rejectsUnknownOrMissingUnitType() {
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationMasterService.requireUnitType("NOT_A_TYPE"));
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationMasterService.requireUnitType("  "));
    }

    @Test
    void statusDefaultsToActiveOnlyWhenCreating() {
        assertEquals("ACTIVE", OrganizationMasterService.normalizeStatus(null, true));
        assertEquals("INACTIVE", OrganizationMasterService.normalizeStatus("inactive", false));
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationMasterService.normalizeStatus(null, false));
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationMasterService.normalizeStatus("ARCHIVED", false));
    }

    @Test
    void verificationDefaultsToConfirmedAndRejectsUnknown() {
        assertEquals("CONFIRMED",
                OrganizationMasterService.normalizeVerification(null, true));
        assertEquals("PENDING_VERIFICATION",
                OrganizationMasterService.normalizeVerification("pending_verification", false));
        assertThrows(IllegalArgumentException.class,
                () -> OrganizationMasterService.normalizeVerification("MAYBE", false));
    }
}
