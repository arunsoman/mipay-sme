package com.mipay.sme.repository;

import com.mipay.sme.domain.org.OrgClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrgClosureRepository extends JpaRepository<OrgClosure, OrgClosure.Pk> {

    boolean existsByAncestorIdAndDescendantId(String ancestorId, String descendantId);

    /** Whole subtree in ONE closure query (org-hierarchy spec [D4]). */
    @Query("""
            select o as org, c.depth as depth
            from OrgClosure c join Organization o on o.id = c.descendantId
            where c.ancestorId = :id
            order by c.depth asc, o.createdAt asc
            """)
    List<OrgSubtreeRow> findSubtree(@Param("id") String id);

    /** Ancestor chain, root first. */
    @Query("""
            select o as org, c.depth as depth
            from OrgClosure c join Organization o on o.id = c.ancestorId
            where c.descendantId = :id
            order by c.depth desc
            """)
    List<OrgSubtreeRow> findAncestors(@Param("id") String id);

    @org.springframework.data.jpa.repository.Query("select c.ancestorId from OrgClosure c "
            + "where c.descendantId = :id and c.depth = 1")
    String findDirectParent(@Param("id") String id);

    Optional<OrgClosure> findByAncestorIdAndDescendantId(String ancestorId, String descendantId);
}
