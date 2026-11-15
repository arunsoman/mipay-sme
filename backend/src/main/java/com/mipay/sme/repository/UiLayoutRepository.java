package com.mipay.sme.repository;

import com.mipay.sme.domain.ui.UiLayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UiLayoutRepository extends JpaRepository<UiLayout, String> {
    Optional<UiLayout> findFirstByPortalAndStatus(String portal, UiLayout.Status status);

    List<UiLayout> findTop20ByPortalAndStatusInOrderByDecidedAtDesc(String portal, List<UiLayout.Status> statuses);

    List<UiLayout> findByPortalAndStatus(String portal, UiLayout.Status status);
}
