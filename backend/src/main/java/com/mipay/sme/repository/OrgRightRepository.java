package com.mipay.sme.repository;

import com.mipay.sme.domain.Enums;
import com.mipay.sme.domain.Enums.OrgType;
import com.mipay.sme.domain.org.OrgRight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgRightRepository extends JpaRepository<OrgRight, String> {

    List<OrgRight> findByOrgId(String orgId);

    List<OrgRight> findByOrgIdIn(List<String> orgIds);

    boolean existsByOrgIdAndTargetTypeAndKind(String orgId, OrgType targetType, Enums.OrgRightKind kind);

    List<OrgRight> findByOrgIdAndKind(String orgId, Enums.OrgRightKind kind);

    List<OrgRight> findByOrgIdInAndKindIn(List<String> orgIds, List<Enums.OrgRightKind> kinds);
}
