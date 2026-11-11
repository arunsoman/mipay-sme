package com.mipay.sme.domain.org;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Closure-table row: ancestor is at `depth` levels above descendant (self row = depth 0). */
@Getter
@Setter
@Entity
@Table(name = "org_closures")
@IdClass(OrgClosure.Pk.class)
public class OrgClosure {

    @Id
    @Column(name = "ancestor_id", length = 36)
    private String ancestorId;

    @Id
    @Column(name = "descendant_id", length = 36)
    private String descendantId;

    @Column(name = "depth", nullable = false)
    private int depth;

    public static OrgClosure of(String ancestorId, String descendantId, int depth) {
        OrgClosure c = new OrgClosure();
        c.ancestorId = ancestorId;
        c.descendantId = descendantId;
        c.depth = depth;
        return c;
    }

    public static class Pk implements Serializable {
        private String ancestorId;
        private String descendantId;

        public Pk() { }

        public Pk(String ancestorId, String descendantId) {
            this.ancestorId = ancestorId;
            this.descendantId = descendantId;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(ancestorId, pk.ancestorId) && Objects.equals(descendantId, pk.descendantId);
        }

        @Override public int hashCode() { return Objects.hash(ancestorId, descendantId); }
    }
}
