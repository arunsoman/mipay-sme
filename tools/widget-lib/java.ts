/**
 * Reads the MiPay backend's Java sources (no compiler needed) to answer, for one endpoint:
 * which controller serves it, which @Perm cell guards it, its path / query parameters, its
 * request body type and its response type - and turns that response type into a widget
 * contract (frontend/projects/shared/widgets/core/shape.ts), following Jackson's defaults
 * in this backend: record component names as fields, nulls included.
 *
 * Used by `tools/widgets.ts endpoint|contract` and the mipay-aruns-ui skill.
 */
import fs from 'node:fs';
import path from 'node:path';

export interface JavaParam { name: string; type: string; required: boolean }
export interface JavaEndpoint {
  method: string;
  path: string;
  perm: string | null;
  /** The @Perm description: what this endpoint does. */
  description: string;
  /** PATH | SELF | LOOKUP | NONE: where the org it acts on comes from. */
  tenantSource: string | null;
  publicReason: string | null;
  handler: string;
  file: string;
  line: number;
  returnType: string;
  pathVars: JavaParam[];
  query: JavaParam[];
  body: { type: string; required: boolean } | null;
}

const MAPPING = /@(Get|Post|Put|Delete|Patch)Mapping\b(\s*\()?/g;

function walk(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/** The text between the bracket at `open` and its partner (exclusive). */
function balanced(src: string, open: number, o = '(', c = ')'): { inner: string; end: number } {
  let depth = 0;
  let inStr = false;
  for (let i = open; i < src.length; i++) {
    const ch = src[i];
    if (ch === '"' && src[i - 1] !== '\\') inStr = !inStr;
    if (inStr) continue;
    if (ch === o) depth++;
    else if (ch === c && --depth === 0) return { inner: src.slice(open + 1, i), end: i };
  }
  return { inner: src.slice(open + 1), end: src.length };
}

/** Split on commas that are not inside <>, (), "" . */
export function splitTop(s: string): string[] {
  const out: string[] = [];
  let depth = 0; let inStr = false; let cur = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '"' && s[i - 1] !== '\\') inStr = !inStr;
    if (!inStr) {
      if (ch === '<' || ch === '(') depth++;
      else if (ch === '>' || ch === ')') depth--;
      else if (ch === ',' && depth === 0) { out.push(cur); cur = ''; continue; }
    }
    cur += ch;
  }
  if (cur.trim()) out.push(cur);
  return out.map((x) => x.trim()).filter(Boolean);
}

const stripComments = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/[^\n]*/g, ' ');

/** Remove annotations (with their arguments) from a declaration. */
function stripAnnotations(s: string): string {
  let out = s;
  for (;;) {
    const m = /@[\w.]+/.exec(out);
    if (!m) return out.trim();
    let end = m.index + m[0].length;
    const rest = out.slice(end);
    const ws = /^\s*/.exec(rest)![0].length;
    if (rest[ws] === '(') end = balanced(out, end + ws).end + 1;
    out = out.slice(0, m.index) + ' ' + out.slice(end);
  }
}

function param(raw: string): { kind: 'path' | 'query' | 'body' | 'other'; p: JavaParam } {
  const ann = (name: string) => {
    const m = new RegExp(`@${name}\\b(\\s*\\(([^)]*)\\))?`).exec(raw);
    return m ? (m[2] ?? '') : null;
  };
  const decl = stripAnnotations(raw).replace(/\bfinal\b/, '').trim();
  const sp = decl.lastIndexOf(' ');
  const type = decl.slice(0, sp).trim();
  const pname = decl.slice(sp + 1).trim();
  const named = (args: string) => /(?:name|value)\s*=\s*"([^"]+)"/.exec(args)?.[1] ?? /^\s*"([^"]+)"/.exec(args)?.[1] ?? pname;
  const required = (args: string) => !/required\s*=\s*false/.test(args) && !/defaultValue/.test(args);
  const pv = ann('PathVariable');
  if (pv !== null) return { kind: 'path', p: { name: named(pv), type, required: true } };
  const rp = ann('RequestParam');
  if (rp !== null) return { kind: 'query', p: { name: named(rp), type, required: required(rp) } };
  const rb = ann('RequestBody');
  if (rb !== null) return { kind: 'body', p: { name: pname, type, required: required(rb) } };
  return { kind: 'other', p: { name: pname, type, required: false } };
}

export function scanEndpoints(webDir: string): JavaEndpoint[] {
  const out: JavaEndpoint[] = [];
  for (const file of walk(webDir)) {
    const src = fs.readFileSync(file, 'utf8');
    const cls = /\bclass\s+(\w+)/.exec(src);
    if (!cls) continue;
    const head = src.slice(0, cls.index);
    const base = /@RequestMapping\s*\(\s*(?:value\s*=\s*|path\s*=\s*)?\{?\s*"([^"]+)"/.exec(head)?.[1] ?? '';
    for (const m of src.matchAll(MAPPING)) {
      if (m.index! < cls.index) continue;
      const method = m[1].toUpperCase();
      let after = m.index! + m[0].length;
      let paths = [''];
      if (m[2]) {
        const b = balanced(src, after - 1);
        paths = [...b.inner.matchAll(/"([^"]*)"/g)].map((x) => x[1]).filter((p) => p === '' || p.startsWith('/'));
        if (!paths.length) paths = [''];
        after = b.end + 1;
      }
      // the annotations above the mapping, back to the end of the previous member
      const before = src.slice(Math.max(0, m.index! - 1500), m.index!);
      const cut = Math.max(before.lastIndexOf('\n    }'), before.lastIndexOf(';\n'));
      const anns = before.slice(cut + 1);
      const perm = /@Perm\s*\(([\s\S]*?)\)\s*(?=@|$)/.exec(anns + '@');
      let permCell: string | null = null;
      const description = perm ? (/description\s*=\s*"([^"]*)"/.exec(perm[1])?.[1] ?? '') : '';
      const tenantSource = perm ? (/tenantSource\s*=\s*TenantSource\.(\w+)/.exec(perm[1])?.[1] ?? 'NONE') : null;
      if (perm) {
        const res = /resource\s*=\s*"([^"]+)"/.exec(perm[1])?.[1];
        const verb = /verb\s*=\s*Verb\.(\w+)/.exec(perm[1])?.[1];
        if (res && verb) permCell = `${res}:${verb.toLowerCase()}`;
      }
      const pub = /@(?:[\w.]+\.)?PublicEndpoint\s*\(\s*reason\s*=\s*"([^"]*)"/.exec(anns)?.[1] ?? null;
      const sigStart = src.indexOf('public ', after);
      const nameM = /public\s+([\s\S]+?)\s+(\w+)\s*\(/.exec(src.slice(sigStart));
      if (!nameM) continue;
      const open = sigStart + nameM.index + nameM[0].length - 1;
      const params = splitTop(stripComments(balanced(src, open).inner)).map(param);
      const line = src.slice(0, m.index).split('\n').length;
      for (const p of paths) {
        out.push({
          method, path: base + p, perm: permCell, description, tenantSource, publicReason: pub,
          handler: `${cls[1]}#${nameM[2]}`, file, line,
          returnType: nameM[1].replace(/\s+/g, ' ').trim(),
          pathVars: params.filter((x) => x.kind === 'path').map((x) => x.p),
          query: params.filter((x) => x.kind === 'query').map((x) => x.p),
          body: params.find((x) => x.kind === 'body') ? { type: params.find((x) => x.kind === 'body')!.p.type, required: params.find((x) => x.kind === 'body')!.p.required } : null,
        });
      }
    }
  }
  return out;
}

const norm = (p: string) => p.replace(/\{[^}]+\}/g, '{}').replace(/\/+$/, '');

export function findEndpoint(all: JavaEndpoint[], method: string, p: string): JavaEndpoint | null {
  return all.find((e) => e.method === method.toUpperCase() && norm(e.path) === norm(p)) ?? null;
}

// ------------------------------------------------------------------ types → contracts

interface TypeIndex { records: Map<string, string>; enums: Map<string, string[]> }

export function indexTypes(srcDir: string): TypeIndex {
  const records = new Map<string, string>();
  const enums = new Map<string, string[]>();
  for (const file of walk(srcDir)) {
    const src = stripComments(fs.readFileSync(file, 'utf8'));
    for (const m of src.matchAll(/\brecord\s+(\w+)\s*(<[^>]*>)?\s*\(/g)) {
      if (!records.has(m[1])) records.set(m[1], balanced(src, m.index! + m[0].length - 1).inner);
    }
    for (const m of src.matchAll(/\benum\s+(\w+)\s*(?:implements[^{]+)?\{/g)) {
      const body = balanced(src, m.index! + m[0].length - 1, '{', '}').inner.split(';')[0];
      const values = splitTop(body).map((v) => /^([A-Z][A-Z0-9_]*)/.exec(v.trim())?.[1]).filter(Boolean) as string[];
      if (values.length && !enums.has(m[1])) enums.set(m[1], values);
    }
  }
  return { records, enums };
}

export interface Built { code: string; untyped: string[] }

/** A shape builder expression for a Java type (top-level: not nullable). */
export function contractFor(javaType: string, idx: TypeIndex): Built {
  const untyped: string[] = [];
  const seen: string[] = [];

  const gen = (t: string, indent: string, nullable: boolean, where: string): string => {
    t = stripAnnotations(t).trim();
    const g = /^([\w.]+)\s*(?:<([\s\S]*)>)?(\[\])?$/.exec(t);
    if (!g) { untyped.push(`${where}: ${t}`); return 's.any()'; }
    const base = g[1].split('.').pop()!;
    const args = g[2] ? splitTop(g[2]) : [];
    const wrap = (code: string) => (nullable ? `s.nullable(${code})` : code);
    if (g[3]) return wrap(`s.array(${gen(base, indent, true, where + '[]')})`);
    switch (base) {
      case 'int': case 'long': case 'short': case 'byte': return 's.integer()';
      case 'double': case 'float': return 's.number()';
      case 'boolean': return 's.boolean()';
      case 'char': return 's.string()';
      case 'Integer': case 'Long': case 'Short': case 'Byte': return wrap('s.integer()');
      case 'Double': case 'Float': case 'BigDecimal': case 'BigInteger': case 'Number': return wrap('s.number()');
      case 'Boolean': return wrap('s.boolean()');
      case 'String': case 'UUID': case 'CharSequence': case 'URI': return wrap('s.string()');
      case 'Instant': case 'OffsetDateTime': case 'ZonedDateTime': case 'LocalDateTime': case 'Date': return wrap('s.dateTime()');
      case 'LocalDate': return wrap('s.date()');
      case 'Optional': return gen(args[0] ?? 'Object', indent, true, where);
      case 'ResponseEntity': return gen(args[0] ?? 'Object', indent, nullable, where);
      case 'List': case 'Set': case 'Collection': case 'Iterable': case 'ArrayList': case 'LinkedHashSet':
        return wrap(`s.array(${gen(args[0] ?? 'Object', indent, false, where + '[]')})`);
      case 'Map': case 'HashMap': case 'LinkedHashMap': case 'TreeMap':
        if (!args[1] || /^(Object|\?)$/.test(args[1].trim())) { untyped.push(`${where || '$'}: ${t}`); return wrap('s.any()'); }
        return wrap(`s.map(${gen(args[1], indent, true, where + '.*')})`);
      case 'JsonNode': case 'ObjectNode': case 'ArrayNode': case 'Object': case 'Void': case 'void':
        untyped.push(`${where || '$'}: ${base}`);
        return wrap('s.any()');
    }
    const values = idx.enums.get(base);
    if (values) return wrap(`s.enum(${values.map((v) => `'${v}'`).join(', ')})`);
    const comps = idx.records.get(base);
    if (comps !== undefined && !seen.includes(base) && seen.length < 8) {
      seen.push(base);
      const inner = indent + '  ';
      const fields = splitTop(comps).map((c) => {
        const d = stripAnnotations(c);
        const sp = d.lastIndexOf(' ');
        const fname = d.slice(sp + 1).trim();
        // bean validation on request records: these may not be null
        const required = /@(?:[\w.]+\.)?(NotNull|NotBlank|NotEmpty)\b/.test(c);
        return `${inner}${/^[A-Za-z_$][\w$]*$/.test(fname) ? fname : `'${fname}'`}: ${gen(d.slice(0, sp), inner, !required, `${where}.${fname}`)},`;
      });
      seen.pop();
      return wrap(`s.object('${base}', {\n${fields.join('\n')}\n${indent}})`);
    }
    untyped.push(`${where || '$'}: ${base} (not a record or enum the tool can read)`);
    return wrap('s.any()');
  };

  return { code: gen(javaType, '', false, ''), untyped };
}


// ------------------------------------------------------------------ compact type notation (API catalogue)

/**
 * A readable type for prompts: `KycTaskRow[]`, `string|null`, `integer`, `date-time|null`,
 * `map<string, any>`. Records and enums are collected in `defs` (name → body) and referenced by name.
 */
export function notation(javaType: string, idx: TypeIndex, defs: Map<string, string>, nullable = false): string {
  const t = stripAnnotations(javaType).trim();
  const g = /^([\w.]+)\s*(?:<([\s\S]*)>)?(\[\])?$/.exec(t);
  const n = (x: string) => (nullable ? `${x}|null` : x);
  if (!g) return n('any');
  const base = g[1].split('.').pop()!;
  const args = g[2] ? splitTop(g[2]) : [];
  if (g[3]) return n(`${notation(base, idx, defs)}[]`);
  switch (base) {
    case 'int': case 'long': case 'short': case 'byte': return 'integer';
    case 'double': case 'float': return 'number';
    case 'boolean': return 'boolean';
    case 'char': return 'string';
    case 'Integer': case 'Long': case 'Short': case 'Byte': return n('integer');
    case 'Double': case 'Float': case 'BigDecimal': case 'BigInteger': case 'Number': return n('number');
    case 'Boolean': return n('boolean');
    case 'String': case 'UUID': case 'CharSequence': case 'URI': return n('string');
    case 'Instant': case 'OffsetDateTime': case 'ZonedDateTime': case 'LocalDateTime': case 'Date': return n('date-time');
    case 'LocalDate': return n('date');
    case 'Optional': return notation(args[0] ?? 'Object', idx, defs, true);
    case 'ResponseEntity': return notation(args[0] ?? 'Object', idx, defs, nullable);
    case 'List': case 'Set': case 'Collection': case 'Iterable': case 'ArrayList': case 'LinkedHashSet':
      return n(`${notation(args[0] ?? 'Object', idx, defs)}[]`);
    case 'Map': case 'HashMap': case 'LinkedHashMap': case 'TreeMap':
      return n(`map<string, ${args[1] ? notation(args[1], idx, defs) : 'any'}>`);
    case 'JsonNode': case 'ObjectNode': case 'ArrayNode': case 'Object': return n('any');
    case 'Void': case 'void': return 'nothing';
  }
  const values = idx.enums.get(base);
  if (values) { if (!defs.has(base)) defs.set(base, values.map((v) => `'${v}'`).join(' | ')); return n(base); }
  const comps = idx.records.get(base);
  if (comps !== undefined) {
    if (!defs.has(base)) {
      defs.set(base, '…'); // guard recursion
      const fields = splitTop(comps).map((c) => {
        const d = stripAnnotations(c);
        const sp = d.lastIndexOf(' ');
        const required = /@(?:[\w.]+\.)?(NotNull|NotBlank|NotEmpty)\b/.test(c);
        return `${d.slice(sp + 1).trim()}: ${notation(d.slice(0, sp), idx, defs, !required)}`;
      });
      defs.set(base, `{ ${fields.join('; ')} }`);
    }
    return n(base);
  }
  return n(`any /* ${base} */`);
}
