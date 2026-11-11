package com.mipay.sme.repository;

import com.mipay.sme.domain.org.Organization;

/** Projection for closure queries: the org plus its depth relative to the anchor. */
public interface OrgSubtreeRow {

    Organization getOrg();

    int getDepth();
}
