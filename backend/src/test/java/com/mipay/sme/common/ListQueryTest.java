package com.mipay.sme.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ListQuery · the one search / sort / filter / page contract")
class ListQueryTest {

    enum Status { ACTIVE, PENDING }

    record Row(String name, Status status, int users, List<String> tags) { }

    static final ListQuery.Fields<Row> FIELDS = ListQuery.Fields.<Row>of()
            .text("name", null, Row::name)
            .field("status", null, Row::status)
            .field("users", null, Row::users)
            .field("tags", null, Row::tags)
            .defaultSort("name", true)
            .sizes(2, 3);

    static final List<Row> ROWS = List.of(
            new Row("Kalahari Supplies", Status.ACTIVE, 4, List.of("Can't add users")),
            new Row("Namib Distribution", Status.PENDING, 1, List.of()),
            new Row("oka store", Status.ACTIVE, 9, List.of()),
            new Row("Rundu Corner", Status.PENDING, 0, List.of("Access not set", "Can't add users")));

    @Test
    @DisplayName("defaults: default sort, default page size, page 0")
    void defaults() {
        ListQuery q = ListQuery.parse(Map.of(), FIELDS);
        ListQuery.Page<Row> p = q.apply(ROWS, FIELDS);
        assertThat(p.total()).isEqualTo(4);
        assertThat(p.size()).isEqualTo(2);
        assertThat(p.items()).extracting(Row::name).containsExactly("Kalahari Supplies", "Namib Distribution");
    }

    @Test
    @DisplayName("q is a case-insensitive contains over the searchable fields only")
    void search() {
        ListQuery.Page<Row> p = ListQuery.parse(Map.of("q", "OKA"), FIELDS).apply(ROWS, FIELDS);
        assertThat(p.items()).extracting(Row::name).containsExactly("oka store");
        // status is not searchable: "active" matches no name
        assertThat(ListQuery.parse(Map.of("q", "active"), FIELDS).apply(ROWS, FIELDS).total()).isZero();
    }

    @Test
    @DisplayName("filters: a comma means any of; a list field matches when any element matches")
    void filters() {
        assertThat(ListQuery.parse(Map.of("status", "pending"), FIELDS).apply(ROWS, FIELDS).items())
                .extracting(Row::name).containsExactly("Namib Distribution", "Rundu Corner");
        assertThat(ListQuery.parse(Map.of("tags", "Can't add users", "size", "3"), FIELDS).apply(ROWS, FIELDS).items())
                .extracting(Row::name).containsExactly("Kalahari Supplies", "Rundu Corner");
        assertThat(ListQuery.parse(Map.of("status", "ACTIVE,PENDING"), FIELDS).apply(ROWS, FIELDS).total()).isEqualTo(4);
    }

    @Test
    @DisplayName("sort: field,asc|desc; text ignores case; numbers sort as numbers")
    void sort() {
        assertThat(ListQuery.parse(Map.of("sort", "users,desc", "size", "3"), FIELDS).apply(ROWS, FIELDS).items())
                .extracting(Row::users).containsExactly(9, 4, 1);
        assertThat(ListQuery.parse(Map.of("sort", "name,desc", "size", "1"), FIELDS).apply(ROWS, FIELDS).items())
                .extracting(Row::name).containsExactly("Rundu Corner");
    }

    @Test
    @DisplayName("paging: size is capped; a page past the end is empty with the true total")
    void paging() {
        ListQuery q = ListQuery.parse(Map.of("size", "500", "page", "1"), FIELDS);
        assertThat(q.size()).isEqualTo(3);
        ListQuery.Page<Row> p = q.apply(ROWS, FIELDS);
        assertThat(p.items()).hasSize(1);
        assertThat(ListQuery.parse(Map.of("page", "9"), FIELDS).apply(ROWS, FIELDS))
                .satisfies(x -> { assertThat(x.items()).isEmpty(); assertThat(x.total()).isEqualTo(4); });
    }

    @Test
    @DisplayName("fail-closed: an undeclared sort field or filter is a 400, never ignored")
    void failClosed() {
        assertThatThrownBy(() -> ListQuery.parse(Map.of("sort", "password"), FIELDS))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_SORT"));
        assertThatThrownBy(() -> ListQuery.parse(Map.of("sort", "name,sideways"), FIELDS))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_SORT"));
        assertThatThrownBy(() -> ListQuery.parse(Map.of("secret", "x"), FIELDS))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_FILTER"));
        assertThatThrownBy(() -> ListQuery.parse(Map.of("page", "two"), FIELDS))
                .isInstanceOfSatisfying(BadRequestException.class, e -> assertThat(e.getCode()).isEqualTo("BAD_PAGE"));
    }
}
