/**
 * The UI catalogue: every existing atom, molecule, organism and shared component a widget may
 * use, with its selector, class, import path and inputs / outputs. Widgets reuse these; they
 * never define their own components (widgets:check enforces it).
 *
 *   npm run widgets:ui            # a table to read
 *   npm run widgets:ui -- --json  # for tools
 */
import fs from 'node:fs';
import path from 'node:path';

export interface UiEntry {
  layer: string;
  selector: string;
  className: string;
  kind: 'component' | 'directive';
  importPath: string;
  inputs: string[];
  outputs: string[];
  summary: string;
}

const ROOTS = [
  { dir: 'src/app/design-system', layer: (rel: string) => rel.split('/')[0] },
  { dir: 'projects/shared', layer: (rel: string) => `shared/${rel.split('/')[0]}` },
];

function walk(dir: string, out: string[] = []): string[] {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { if (e.name !== 'widgets') walk(p, out); }
    else if (e.name.endsWith('.ts') && !e.name.endsWith('.spec.ts')) out.push(p);
  }
  return out;
}

export function scanCatalogue(fe: string): UiEntry[] {
  const out: UiEntry[] = [];
  for (const root of ROOTS) {
    const base = path.join(fe, root.dir);
    for (const file of walk(base)) {
      const src = fs.readFileSync(file, 'utf8');
      const rel = path.relative(base, file).split(path.sep).join('/');
      for (const m of src.matchAll(/@(Component|Directive)\s*\(\s*\{/g)) {
        const after = src.slice(m.index!);
        const selector = /selector:\s*'([^']+)'/.exec(after)?.[1];
        const cls = /export\s+class\s+(\w+)/.exec(after);
        if (!selector || !cls) continue;
        const next = after.slice(cls.index + cls[0].length);
        const stop = next.search(/\n(@Component|@Directive|@Injectable|export\s+(class|function|const|interface))/);
        const body = stop >= 0 ? next.slice(0, stop) : next;
        const inputs = [...body.matchAll(/^\s+(?:readonly\s+)?(\w+)\s*=\s*(input|model)(\.required)?\s*(<([^>]*(?:<[^>]*>)?[^>]*)>)?\s*\(([^)\n]*)\)/gm)]
          .map((x) => `${x[1]}${x[3] ? '*' : ''}${x[5] ? `: ${x[5].replace(/\s+/g, ' ')}` : ''}${!x[3] && x[6]?.trim() && !x[6].trim().startsWith('{') ? ` = ${x[6].trim().split(/,\s*\{/)[0]}` : ''}`);
        inputs.push(...[...body.matchAll(/@Input\(\)\s*(?:set\s+)?(\w+)/g)].map((x) => x[1]));
        const outputs = [...body.matchAll(/^\s+(?:readonly\s+)?(\w+)\s*=\s*output\s*(<[^>]*>)?\s*\(/gm)].map((x) => x[1]);
        const doc = src.slice(0, m.index).match(/\/\*\*([\s\S]*?)\*\/\s*$/);
        const summary = (doc?.[1] ?? '').replace(/^\s*\*\s?/gm, ' ').replace(/\s+/g, ' ').trim();
        out.push({
          layer: root.layer(rel), selector, className: cls[1], kind: m[1] === 'Directive' ? 'directive' : 'component',
          importPath: path.relative(fe, file).split(path.sep).join('/').replace(/\.ts$/, ''),
          inputs, outputs, summary: summary.length > 320 ? summary.slice(0, 317) + '…' : summary,
        });
      }
    }
  }
  return out.sort((a, b) => a.layer.localeCompare(b.layer) || a.selector.localeCompare(b.selector));
}

/** Element selectors (tags) a widget template may use. */
export function knownTags(entries: UiEntry[]): Set<string> {
  const tags = new Set<string>();
  for (const e of entries) for (const sel of e.selector.split(',')) {
    const t = sel.trim();
    if (/^[a-z][a-z0-9]*-[a-z0-9-]+$/.test(t)) tags.add(t);
  }
  return tags;
}

export function catalogueMarkdown(entries: UiEntry[]): string {
  const lines = ['| layer | selector | class | import (from frontend/) | inputs (* required) | outputs | what it is |', '|---|---|---|---|---|---|---|'];
  for (const e of entries) {
    const cell = (t: string) => t.replace(/\|/g, '\\|');
    lines.push(`| ${e.layer} | \`${e.selector}\` | ${e.className} | ${e.importPath} | ${cell(e.inputs.join('; ')) || '-'} | ${e.outputs.join(', ') || '-'} | ${cell(e.summary)} |`);
  }
  return lines.join('\n');
}
