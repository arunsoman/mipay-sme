package com.mipay.sme.security.rbac;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrgTreeSyncServiceTest {

    @Test
    void syncSnapshotCountMustMatchDatabaseCount() {
        assertTrue(OrgTreeSyncService.snapshotCountMatches(12, 12));
        assertTrue(OrgTreeSyncService.snapshotCountMatches(0, 0));
        assertFalse(OrgTreeSyncService.snapshotCountMatches(12, 11));
        assertFalse(OrgTreeSyncService.snapshotCountMatches(-1, 0));
    }
}
