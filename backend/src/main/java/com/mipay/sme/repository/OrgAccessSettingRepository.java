package com.mipay.sme.repository;

import com.mipay.sme.domain.org.OrgAccessSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrgAccessSettingRepository extends JpaRepository<OrgAccessSetting, String> {
}
