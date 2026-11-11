/**
 * The master-table condition grammar (design §3.5), evaluated in the browser
 * so the form shows and requires exactly the fields the server will:
 *
 *   {"field": "businessProfile.entityType", "in": [...]}   eq / notEq / in / notIn / contains / gt / gte / lt / lte / exists
 *   {"all": [...]}  {"any": [...]}  {"not": {...}}
 *   {"field": "item.identificationType", "eq": "NATIONAL_ID"}   current list item (also "ubos[].x")
 *   {"context": "orgType", "in": ["MERCHANT"]}                  orgType / country / licenceType
 *
 * Paths starting with "item." or "<section>[]." read the current list item;
 * any other path is absolute from the document root (falling back to the
 * current item for a bare field key).
 */
export type Doc = Record<string, unknown>;

export function evaluateCondition(cond: unknown, doc: Doc, item: Doc | null, context: Doc): boolean {
  if (cond == null) return true;
  if (typeof cond !== 'object' || Array.isArray(cond)) return false;
  const c = cond as Record<string, unknown>;

  if ('all' in c) return Array.isArray(c['all']) && c['all'].every((x) => evaluateCondition(x, doc, item, context));
  if ('any' in c) return Array.isArray(c['any']) && c['any'].some((x) => evaluateCondition(x, doc, item, context));
  if ('not' in c) return !evaluateCondition(c['not'], doc, item, context);
  if ('context' in c) return testOps(c, context[String(c['context'])]);
  if ('field' in c) return testOps(c, resolvePath(String(c['field']), doc, item));
  return true;
}

export function resolvePath(path: string, doc: Doc, item: Doc | null): unknown {
  const segs = path.split('.');
  if (path.startsWith('item.')) return walk(item ?? doc, segs.slice(1));
  if (segs[0].endsWith('[]')) return item ? walk(item, segs.slice(1)) : undefined;
  const absolute = walk(doc, segs);
  if (absolute !== undefined) return absolute;
  return segs.length === 1 && item ? item[segs[0]] : undefined;
}

function walk(root: unknown, segs: string[]): unknown {
  let cur: unknown = root;
  for (const s of segs) {
    if (cur == null || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
    cur = (cur as Doc)[s];
    if (cur === undefined) return undefined;
  }
  return cur;
}

const present = (v: unknown) => v !== null && v !== undefined && v !== '';
const str = (v: unknown) => (v === null || v === undefined ? '' : String(v));

function testOps(c: Record<string, unknown>, left: unknown): boolean {
  if ('eq' in c) return present(left) && c['eq'] != null && str(left) === str(c['eq']);
  if ('notEq' in c) return present(left) && str(left) !== str(c['notEq']);
  if ('in' in c) return Array.isArray(c['in']) && present(left) && c['in'].map(str).includes(str(left));
  if ('notIn' in c) return !Array.isArray(c['notIn']) || !present(left) || !c['notIn'].map(str).includes(str(left));
  if ('contains' in c) {
    if (Array.isArray(left)) return left.map(str).includes(str(c['contains']));
    return present(left) && str(left) === str(c['contains']);
  }
  for (const op of ['gt', 'gte', 'lt', 'lte'] as const) {
    if (op in c) {
      const l = Number(left), r = Number(c[op]);
      if (!present(left) || Number.isNaN(l) || Number.isNaN(r)) return false;
      return op === 'gt' ? l > r : op === 'gte' ? l >= r : op === 'lt' ? l < r : l <= r;
    }
  }
  if ('exists' in c) return (c['exists'] === true || c['exists'] === 'true') === present(left);
  return present(left);
}
