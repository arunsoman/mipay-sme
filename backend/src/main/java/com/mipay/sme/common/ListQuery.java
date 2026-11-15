package com.mipay.sme.common;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The one list contract every searchable list speaks (admin console, 2026-11-15):
 *
 * <pre>
 *   GET …?q=kalahari&amp;sort=name,asc&amp;status=PENDING,ACTIVE&amp;page=0&amp;size=20
 *   → {items: [...], total: 57, page: 0, size: 20}
 * </pre>
 *
 * <ul>
 *   <li>{@code q}: free text, case-insensitive "contains" over the list's searchable fields;</li>
 *   <li>{@code sort}: {@code field,asc|desc} on a declared sortable field;</li>
 *   <li>any other parameter is a filter on a declared filterable field; a comma means "any of";</li>
 *   <li>{@code page} (from 0) and {@code size} (1…{@link Fields#maxSize}).</li>
 * </ul>
 *
 * Fail-closed: an undeclared sort field or filter is a 400 ({@code BAD_SORT} / {@code BAD_FILTER}),
 * never silently ignored. A list declares its fields once ({@link Fields}) and then answers either
 * from the database ({@link #specification} + {@link #pageable}) or, for lists the server assembles
 * itself (RBAC-backed, workflow inbox), in memory with {@link #apply}. Either way the server does
 * the work; the browser only sends the parameters.
 */
public record ListQuery(String q, String sortField, boolean ascending, Map<String, List<String>> filters,
                        int page, int size) {

    private static final Set<String> RESERVED = Set.of("q", "sort", "page", "size");

    /** One page of a list. */
    public record Page<T>(List<T> items, long total, int page, int size) {
        public <R> Page<R> map(Function<T, R> f) {
            return new Page<>(items.stream().map(f).toList(), total, page, size);
        }

        public static <T> Page<T> of(org.springframework.data.domain.Page<T> p) {
            return new Page<>(p.getContent(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
    }

    /**
     * What a list allows. {@code field} names are the API's names (the row's JSON property);
     * {@code path} is the entity attribute behind it ({@code null} for in-memory lists).
     */
    public static final class Fields<T> {
        record Field<T>(String name, String path, Function<T, ?> value, boolean sortable, boolean filterable,
                        boolean searchable) { }

        private final Map<String, Field<T>> fields = new LinkedHashMap<>();
        private String defaultSort;
        private boolean defaultAscending = true;
        private int maxSize = 100;
        private int defaultSize = 20;

        public static <T> Fields<T> of() { return new Fields<>(); }

        /** Sortable and filterable. */
        public Fields<T> field(String name, String path, Function<T, ?> value) {
            fields.put(name, new Field<>(name, path, value, true, true, false));
            return this;
        }

        /** Sortable, filterable and searched by {@code q}. */
        public Fields<T> text(String name, String path, Function<T, ?> value) {
            fields.put(name, new Field<>(name, path, value, true, true, true));
            return this;
        }

        /** Searched by {@code q} only (not shown as a column, e.g. an email). */
        public Fields<T> searchOnly(String name, String path, Function<T, ?> value) {
            fields.put(name, new Field<>(name, path, value, false, false, true));
            return this;
        }

        public Fields<T> defaultSort(String name, boolean ascending) {
            this.defaultSort = name;
            this.defaultAscending = ascending;
            return this;
        }

        public Fields<T> sizes(int defaultSize, int maxSize) {
            this.defaultSize = defaultSize;
            this.maxSize = maxSize;
            return this;
        }

        public Set<String> names() { return fields.keySet(); }
    }

    /** Parses the request parameters against a list's declared fields (400 on anything undeclared). */
    public static <T> ListQuery parse(Map<String, String> params, Fields<T> spec) {
        Map<String, String> p = params == null ? Map.of() : params;
        String q = blankToNull(p.get("q"));
        if (q != null && q.length() > 100) q = q.substring(0, 100);

        String sortField = spec.defaultSort;
        boolean asc = spec.defaultAscending;
        String sort = blankToNull(p.get("sort"));
        if (sort != null) {
            String[] parts = sort.split(",", 2);
            Fields.Field<T> f = spec.fields.get(parts[0].trim());
            if (f == null || !f.sortable()) throw new BadRequestException("BAD_SORT", "cannot sort by " + parts[0].trim()
                    + "; sortable: " + String.join(", ", spec.fields.values().stream().filter(Fields.Field::sortable)
                    .map(Fields.Field::name).toList()));
            sortField = f.name();
            String dir = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
            if (!dir.equals("asc") && !dir.equals("desc")) throw new BadRequestException("BAD_SORT", "sort direction is asc or desc");
            asc = dir.equals("asc");
        }

        Map<String, List<String>> filters = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : p.entrySet()) {
            if (RESERVED.contains(e.getKey())) continue;
            String v = blankToNull(e.getValue());
            if (v == null) continue;
            Fields.Field<T> f = spec.fields.get(e.getKey());
            if (f == null || !f.filterable()) throw new BadRequestException("BAD_FILTER", "cannot filter by " + e.getKey()
                    + "; filterable: " + String.join(", ", spec.fields.values().stream().filter(Fields.Field::filterable)
                    .map(Fields.Field::name).toList()));
            filters.put(f.name(), Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        }

        int page = Math.max(0, intOr(p.get("page"), 0));
        int size = Math.min(spec.maxSize, Math.max(1, intOr(p.get("size"), spec.defaultSize)));
        return new ListQuery(q, sortField, asc, Map.copyOf(filters), page, size);
    }

    // ------------------------------------------------------------------ database

    /** {@code q} + filters as a JPA predicate over the entity behind {@code spec}. */
    public <E> Specification<E> specification(Fields<E> spec) {
        return (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            if (q != null) {
                String like = "%" + escape(q.toLowerCase(Locale.ROOT)) + "%";
                List<Predicate> any = new ArrayList<>();
                for (Fields.Field<E> f : spec.fields.values()) {
                    if (!f.searchable() || f.path() == null) continue;
                    any.add(cb.like(cb.lower(path(root, f.path()).as(String.class)), like, '\\'));
                }
                if (!any.isEmpty()) all.add(cb.or(any.toArray(Predicate[]::new)));
            }
            for (Map.Entry<String, List<String>> e : filters.entrySet()) {
                Fields.Field<E> f = spec.fields.get(e.getKey());
                if (f.path() == null) continue;
                Path<Object> col = path(root, f.path());
                Class<?> type = col.getJavaType();
                List<Object> values = new ArrayList<>();
                for (String v : e.getValue()) values.add(convert(type, v, e.getKey()));
                all.add(col.in(values));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
    }

    /** Page + sort for a repository call; sorts by the entity attribute behind the API field. */
    public <E> Pageable pageable(Fields<E> spec) {
        if (sortField == null) return PageRequest.of(page, size);
        Fields.Field<E> f = spec.fields.get(sortField);
        String path = f == null || f.path() == null ? sortField : f.path();
        Sort s = Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, path);
        // a stable tie-break so pages never repeat or skip rows
        if (!path.equals("id")) s = s.and(Sort.by("id"));
        return PageRequest.of(page, size, s);
    }

    // ------------------------------------------------------------------ in memory

    /** Search, filter, sort and page rows the server assembled itself. */
    public <T> Page<T> apply(Collection<T> rows, Fields<T> spec) {
        String needle = q == null ? null : q.toLowerCase(Locale.ROOT);
        List<T> kept = new ArrayList<>();
        for (T row : rows) {
            if (needle != null && spec.fields.values().stream().filter(Fields.Field::searchable)
                    .noneMatch(f -> text(f.value().apply(row)).contains(needle))) continue;
            boolean ok = true;
            for (Map.Entry<String, List<String>> e : filters.entrySet()) {
                Object v = spec.fields.get(e.getKey()).value().apply(row);
                if (!matches(v, e.getValue())) { ok = false; break; }
            }
            if (ok) kept.add(row);
        }
        if (sortField != null) {
            Function<T, ?> key = spec.fields.get(sortField).value();
            Comparator<T> c = Comparator.comparing(r -> sortKey(key.apply(r)), Comparator.nullsLast(Comparator.naturalOrder()));
            kept.sort(ascending ? c : c.reversed());
        }
        int from = Math.min(kept.size(), page * size);
        int to = Math.min(kept.size(), from + size);
        return new Page<>(List.copyOf(kept.subList(from, to)), kept.size(), page, size);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean matches(Object v, List<String> wanted) {
        if (v instanceof Collection<?> many) {
            for (Object one : many) if (matches(one, wanted)) return true;
            return false;
        }
        String s = v == null ? "" : (v instanceof Enum<?> en ? en.name() : String.valueOf(v));
        return wanted.stream().anyMatch(w -> w.equalsIgnoreCase(s));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable sortKey(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s.toLowerCase(Locale.ROOT);
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof Collection<?> c) return c.size();
        if (v instanceof Comparable c) return c;
        return String.valueOf(v);
    }

    private static String text(Object v) {
        if (v == null) return "";
        if (v instanceof Collection<?> c) return String.join(" ", c.stream().map(ListQuery::text).toList());
        return String.valueOf(v).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Class<?> type, String v, String field) {
        try {
            if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, v.toUpperCase(Locale.ROOT));
            if (type == Boolean.class || type == boolean.class) {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) throw new IllegalArgumentException();
                return Boolean.parseBoolean(v);
            }
            if (type == Integer.class || type == int.class) return Integer.parseInt(v);
            if (type == Long.class || type == long.class) return Long.parseLong(v);
            return v;
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("BAD_FILTER", "invalid value '" + v + "' for " + field);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E> Path<Object> path(Root<E> root, String dotted) {
        Path<?> p = root;
        for (String part : dotted.split("\\.")) p = p.get(part);
        return (Path<Object>) p;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int intOr(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { throw new BadRequestException("BAD_PAGE", "page and size are whole numbers"); }
    }
}
