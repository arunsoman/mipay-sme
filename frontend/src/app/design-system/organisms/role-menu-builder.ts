import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import type { BusinessPortal } from '../../../../projects/shared/web-core/portal-permission-catalogue';
import {
  buildTree, CellChip, hideMenu, hideScreen, MenuNode, PaletteRow, ScreenNode, showMenu, showScreen,
} from '../../../../projects/shared/rbac/role-menu-model';

const VERB_WORD: Record<string, string> = {
  read: 'view', create: 'create', modify: 'edit', delete: 'delete', submit: 'submit', authorize: 'approve',
  execute: 'perform', export: 'export', administer: 'manage', simulate: 'simulate',
};

/**
 * Organism: the role builder (docs/plans/role-menu-builder.md). One portal's menus and
 * submenus, drawn like its sidebar. Each menu and submenu has a visible switch; inside a
 * visible submenu (a screen) the actions the role may take there.
 *
 * With menu cells (`nav`, 2026-11-14) every submenu has its own switch: on gives its menu
 * cell and the fewest permission cells the portal needs to draw it; off takes the menu cell
 * and the permission cells no other switched-on submenu needs. Without them visibility
 * follows permissions (decision 1a). Actions that drive other screens too say so ("also
 * affects", decision 2A). Planned screens sit behind "Show planned screens" (decision 3C).
 *
 * The component only edits `selected` (the role's own cells); `inherited` cells come
 * from roles it builds on and are shown but not changeable here.
 */
@Component({
  selector: 'mp-role-menu-builder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar">
      <span class="sum">{{ visibleMenus() }} of {{ liveMenus() }} menus · {{ visibleScreens() }} of {{ liveScreens() }} submenus visible</span>
      <span class="grow"></span>
      <label class="chk"><input type="checkbox" [checked]="showPlanned()" (change)="showPlanned.set(!showPlanned())" /> Show planned screens</label>
    </div>

    @for (m of shownMenus(); track m.id) {
      <section class="menu" [class.on]="m.visible" [class.planned]="!m.live">
        <div class="mhead">
          <button type="button" class="caret" (click)="toggleOpen(m.id)" [attr.aria-expanded]="isOpen(m)"
                  [attr.aria-label]="(isOpen(m) ? 'Collapse ' : 'Expand ') + m.label">{{ isOpen(m) ? '▾' : '▸' }}</button>
          <label class="sw" [class.dis]="disabled() || !m.live">
            <input type="checkbox" role="switch" [checked]="m.visible" [disabled]="disabled() || !m.live"
                   (change)="setMenu(m, !m.visible, $event)" [attr.aria-label]="'Show menu ' + m.label" />
            <span class="track"><span class="knob"></span></span>
          </label>
          <b class="mlabel" (click)="toggleOpen(m.id)">{{ m.label }}</b>
          @if (!m.live) {<span class="tag">Coming soon</span>}
          @else {<span class="muted small">{{ count(m) }}</span>}
        </div>

        @if (isOpen(m)) {
          @for (s of shownScreens(m); track s.key) {
            <div class="screen" [class.on]="s.visible" [class.planned]="s.status !== 'live'">
              <div class="shead">
                <label class="sw sm" [class.dis]="disabled() || s.status !== 'live' || s.lockedVisible">
                  <input type="checkbox" role="switch" [checked]="s.visible"
                         [disabled]="disabled() || s.status !== 'live' || s.lockedVisible"
                         (change)="setScreen(s, !s.visible, $event)" [attr.aria-label]="'Show submenu ' + s.label" />
                  <span class="track"><span class="knob"></span></span>
                </label>
                <span class="slabel">{{ s.label }}</span>
                @if (s.link && s.status === 'live') {<span class="mono muted">{{ s.link }}</span>}
                @if (s.status === 'planned') {<span class="tag">Coming soon</span>}
                @if (s.status === 'unavailable') {<span class="tag">Can't be given here</span>}
                @if (s.lockedVisible) {<span class="tag inh">visible through a role it builds on</span>}
              </div>
              @if (s.visible && s.chips.length) {
                <div class="acts">
                  @for (g of rowsOf(s); track g.row) {
                    <div class="arow">
                      <span class="rdesc">{{ g.desc }}</span>
                      <span class="chips">
                        @for (c of g.chips; track c.cell) {
                          <button type="button" class="chip-v {{ c.state }}" [disabled]="disabled() || c.state === 'inh'"
                                  [attr.aria-pressed]="c.state !== 'off'" [title]="chipTitle(c)" (click)="toggleCell(c)">
                            {{ c.state === 'own' ? '✓ ' : '' }}{{ word(c.verb) }}</button>
                        }
                      </span>
                    </div>
                  }
                  @if (s.sharesWith.length) {
                    <div class="shares">These actions also affect: {{ s.sharesWith.join(' · ') }}</div>
                  }
                </div>
              }
            </div>
          }
        }
      </section>
    } @empty {
      <div class="muted pad">This portal has no menus that can be given yet. Switch on "Show planned screens" to see what is coming.</div>
    }
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-2, 8px); }
    .bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; font-size: var(--fs-sm, 12.5px); color: var(--mp-text-dim, #a3a3a3); }
    .grow { flex: 1; }
    .chk { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; white-space: nowrap; }
    .chk input { width: auto; margin: 0; }
    .menu { border: 1px solid var(--mp-border, #2b2b2b); border-radius: 8px; overflow: hidden; }
    .menu.planned, .screen.planned { opacity: .6; }
    .mhead { display: flex; align-items: center; gap: 10px; padding: 9px 12px; background: var(--a-panel-2, var(--mp-surface)); flex-wrap: wrap; }
    .mlabel { cursor: pointer; margin-right: auto; }
    .caret { border: 0; background: transparent; color: var(--mp-text-dim, #a3a3a3); padding: 0 2px; min-height: 0; width: 18px; box-shadow: none; cursor: pointer; }
    .screen { border-top: 1px solid var(--mp-border, #2b2b2b); padding: 8px 12px 8px 40px; }
    .shead { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .slabel { margin-right: auto; }
    .mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: var(--fs-xs, 11px); }
    .muted { color: var(--mp-text-dim, #a3a3a3); }
    .small { font-size: var(--fs-xs, 11px); }
    .pad { padding: 16px; }
    .tag { font-size: var(--fs-xs, 11px); padding: 1px 8px; border-radius: 999px; border: 1px solid var(--mp-amber, #f7c66b); color: var(--mp-amber, #f7c66b); white-space: nowrap; }
    .tag.inh { border-style: dashed; }
    .acts { display: grid; gap: 6px; padding: 8px 0 2px 46px; }
    .arow { display: grid; grid-template-columns: minmax(160px, 280px) 1fr; gap: 10px; align-items: start; }
    .rdesc { font-size: var(--fs-sm, 12.5px); color: var(--mp-text-dim, #a3a3a3); padding-top: 3px; }
    .chips { display: flex; flex-wrap: wrap; gap: 4px; }
    .shares { font-size: var(--fs-xs, 11px); color: var(--mp-amber, #f7c66b); }
    /* switch */
    .sw { position: relative; display: inline-flex; flex: none; cursor: pointer; }
    .sw input { position: absolute; opacity: 0; width: 100%; height: 100%; margin: 0; cursor: inherit; }
    .sw .track { width: 34px; height: 18px; border-radius: 999px; background: var(--mp-border, #2b2b2b); position: relative; transition: background .15s; }
    .sw .knob { position: absolute; top: 2px; left: 2px; width: 14px; height: 14px; border-radius: 50%; background: var(--mp-text-dim, #a3a3a3); transition: transform .15s, background .15s; }
    .sw input:checked + .track { background: var(--mp-accent, #f0a10e); }
    .sw input:checked + .track .knob { transform: translateX(16px); background: var(--mp-accent-ink, #141004); }
    .sw input:focus-visible + .track { outline: 2px solid var(--mp-accent, #f0a10e); outline-offset: 2px; }
    .sw.dis { cursor: default; opacity: .55; }
    .sw.sm .track { width: 28px; height: 16px; }
    .sw.sm .knob { width: 12px; height: 12px; }
    .sw.sm input:checked + .track .knob { transform: translateX(12px); }
    /* action chips */
    .chip-v { display: inline-block; padding: 2px 10px; border-radius: 999px; font: inherit; font-size: 12.5px; line-height: 20px;
              min-height: 0; box-shadow: none; text-transform: none; letter-spacing: 0; cursor: pointer;
              border: 1px solid var(--mp-border, #2b2b2b); background: transparent; color: var(--mp-text-dim, #a3a3a3); }
    .chip-v.own, .chip-v.own:hover { background: var(--mp-accent, #f0a10e); border-color: var(--mp-accent, #f0a10e); color: var(--mp-accent-ink, #141004); font-weight: 600; }
    .chip-v.off:hover { border-color: var(--mp-accent, #f0a10e); color: var(--mp-text, #eee); background: transparent; }
    .chip-v.inh, .chip-v.inh:disabled { border: 1px dashed var(--mp-accent, #f0a10e); color: var(--mp-text-dim, #a3a3a3); background: transparent; opacity: 1; cursor: default; }
    .chip-v:disabled { cursor: default; }
    @media (max-width: 640px) {
      .screen { padding-left: 16px; }
      .acts { padding-left: 0; }
      .arow { grid-template-columns: 1fr; gap: 2px; }
    }
  `],
})
export class RoleMenuBuilder {
  readonly portal = input.required<BusinessPortal>();
  /** The rows (and their verbs) this builder may give. */
  readonly palette = input.required<PaletteRow[]>();
  /** The role's own cells. */
  readonly selected = input.required<Set<string>>();
  /** Cells from roles it builds on: shown, not changeable. */
  readonly inherited = input<Set<string>>(new Set());
  readonly disabled = input(false);
  /** Menu cells can be given (RBAC knows the nav.* rows): each submenu switches on its own. */
  readonly nav = input(false);
  readonly selectedChange = output<Set<string>>();

  readonly showPlanned = signal(false);
  /** Menus the user opened or closed; untouched menus open when visible. */
  private readonly opened = signal<Map<string, boolean>>(new Map());

  readonly tree = computed<MenuNode[]>(() => buildTree(this.portal(), this.palette(), this.selected(), this.inherited(), { nav: this.nav() }));
  readonly shownMenus = computed(() => this.tree().filter((m) => this.showPlanned() || m.live));
  readonly liveMenus = computed(() => this.tree().filter((m) => m.live).length);
  readonly visibleMenus = computed(() => this.tree().filter((m) => m.visible).length);
  readonly liveScreens = computed(() => this.tree().flatMap((m) => m.screens).filter((s) => s.status === 'live').length);
  readonly visibleScreens = computed(() => this.tree().flatMap((m) => m.screens).filter((s) => s.visible).length);

  shownScreens(m: MenuNode) { return m.screens.filter((s) => this.showPlanned() || s.status === 'live'); }
  count(m: MenuNode) {
    const live = m.screens.filter((s) => s.status === 'live');
    return `${live.filter((s) => s.visible).length}/${live.length} submenus`;
  }
  isOpen(m: MenuNode) { return this.opened().get(m.id) ?? m.visible; }
  toggleOpen(id: string) {
    const m = this.tree().find((x) => x.id === id);
    const next = new Map(this.opened());
    next.set(id, !(m ? this.isOpen(m) : false));
    this.opened.set(next);
  }

  /** A screen's chips grouped by row, for one line per backend permission. */
  rowsOf(s: ScreenNode) {
    const by = new Map<string, { row: string; desc: string; chips: CellChip[] }>();
    for (const c of s.chips) {
      const g = by.get(c.row) ?? { row: c.row, desc: c.rowDescription, chips: [] };
      g.chips.push(c);
      by.set(c.row, g);
    }
    return [...by.values()];
  }

  word(v: string) { return VERB_WORD[v] ?? v; }
  chipTitle(c: CellChip) {
    if (c.state === 'inh') return `${c.cell}: comes from a role this one builds on`;
    return `${c.cell}: click to ${c.state === 'own' ? 'take away' : 'give'}`;
  }

  /** The switch always shows the model: undo the native toggle, the new tree redraws it. */
  private resetSwitch(ev: Event | undefined, was: boolean) {
    const el = ev?.target as HTMLInputElement | undefined;
    if (el) el.checked = was;
  }

  setMenu(m: MenuNode, on: boolean, ev?: Event) {
    this.resetSwitch(ev, m.visible);
    if (this.disabled()) return;
    const p = this.portal(), pal = this.palette(), sel = this.selected();
    const o = { nav: this.nav() }, inh = this.inherited();
    this.emit(on ? showMenu(p, pal, sel, inh, m.id, o) : hideMenu(p, pal, sel, m.id, inh, o));
    const next = new Map(this.opened());
    next.set(m.id, on);
    this.opened.set(next);
  }

  setScreen(s: ScreenNode, on: boolean, ev?: Event) {
    this.resetSwitch(ev, s.visible);
    if (this.disabled()) return;
    const p = this.portal(), pal = this.palette(), sel = this.selected();
    const o = { nav: this.nav() }, inh = this.inherited();
    this.emit(on ? showScreen(p, pal, sel, inh, s.key, o) : hideScreen(p, pal, sel, s.key, inh, o));
  }

  toggleCell(c: CellChip) {
    if (this.disabled() || c.state === 'inh') return;
    const next = new Set(this.selected());
    if (next.has(c.cell)) next.delete(c.cell); else next.add(c.cell);
    this.emit(next);
  }

  private emit(next: Set<string>) {
    if (next === this.selected()) return;
    this.selectedChange.emit(next);
  }
}
