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

    @Test
    void badRowsAreSkippedNotFatal() {
        java.util.function.Function<String, com.mipay.sme.domain.org.Organization> org = id -> {
            var o = new com.mipay.sme.domain.org.Organization();
            o.setId(id);
            return o;
        };
        var out = OrgTreeSyncService.validOrgs(java.util.List.of(org.apply("a"), org.apply(""), org.apply(null),
                org.apply("b"), org.apply("c"), org.apply("c")));
        assertEquals(java.util.List.of("a", "b"), out.stream().map(com.mipay.sme.domain.org.Organization::getId).toList());
    }
}
