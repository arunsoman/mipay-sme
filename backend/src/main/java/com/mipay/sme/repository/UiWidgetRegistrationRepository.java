package com.mipay.sme.repository;

import com.mipay.sme.domain.ui.UiWidgetRegistration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UiWidgetRegistrationRepository extends JpaRepository<UiWidgetRegistration, String> {
    List<UiWidgetRegistration> findByPortalAndStatusOrderByWidgetId(String portal, String status);
}
