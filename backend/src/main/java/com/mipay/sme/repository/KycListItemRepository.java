package com.mipay.sme.repository;

import com.mipay.sme.domain.kyc.KycListItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KycListItemRepository extends JpaRepository<KycListItem, String> {
    Optional<KycListItem> findByListCodeAndValue(String listCode, String value);


    List<KycListItem> findByListCodeAndActiveTrueOrderBySortAsc(String listCode);

    List<KycListItem> findByListCodeAndParentValueAndActiveTrueOrderBySortAsc(String listCode, String parentValue);

    /** Used by the legacy migration to match region labels where possible. */
    @Query("select i from KycListItem i where i.listCode = :listCode and i.active = true and lower(i.label) = lower(:label)")
    List<KycListItem> findByLabelInsensitive(@Param("listCode") String listCode, @Param("label") String label);
}
