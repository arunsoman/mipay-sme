package com.mipay.sme.repository;

import com.mipay.sme.domain.ops.TabEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TabEntryRepository extends JpaRepository<TabEntry, String> {

    List<TabEntry> findByTabIdOrderByCreatedAtDesc(String tabId);
}
