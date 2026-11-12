import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AccessService } from './access';
import { MpPerm } from './perm-directive';
import { PERMS, PERM_AREAS, PERMISSION_CELL_COUNT } from './permissions';

/**
 * Permission Inventory (platform QA surface, gated by admin.access:read).
 *
 * Exactly ONE live UI element per permission cell - 136 in total. Each element
 * is the same self-hiding pattern the feature screens use (*mpPerm): it renders
 * on the moment the signed-in principal holds the exact cell, and is removed
 * from the DOM the moment the right is revoked. The dim "○ not held" marker is
 * only a caption for the gap; the gated element itself simply isn't there.
 *
 * Grant a role in the RBAC matrix, reload the rights, and the corresponding
 * tiles light up without any code change - which is the acceptance test of the
 * whole permission-driven UI model.
 */
@Component({
  selector: 'mpc-perm-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MpPerm],
  template: `
    <div class="head">
      <div>
        <h2>All permissions</h2>
        <p class="dim">
          {{ PERMISSION_CELL_COUNT }} cells = one live UI element each ·
          {{ heldCount() }} held in this session ·
          {{ shown().length }} shown by search
        </p>
      </div>
      <input class="q" placeholder="Filter by id, verb, persona..." [(ngModel)]="q" />
    </div>

    @for (area of visibleAreas(); track area) {
      <section>
        <header class="arow">
          <h3>{{ area.toUpperCase() }}</h3>
          <span class="dim">{{ areaPersona(area) }}</span>
        </header>
        <div class="grid">
          @for (cell of byArea()[area]; track cell.id) {
            <div class="tile" [class.held]="access.can(cell.id)">
              <div class="id">{{ cell.id }}</div>
              <div class="lbl">{{ cell.label }}</div>
              <!-- THE gate: one element per permission, live while the cell is held -->
              <span class="on" *mpPerm="cell.id">● element live for this session</span>
              @if (!access.can(cell.id)) {
                <span class="off">○ not held - element removed from the DOM</span>
              }
            </div>
          }
        </div>
      </section>
    } @empty {
      <p class="dim">No cells match "{{ q() }}".</p>
    }
  `,
  styles: [
    `
      .head { display: flex; gap: var(--sp-4, 16px); align-items: center; justify-content: space-between; flex-wrap: wrap; }
      h2 { margin: 0 0 2px; font-size: var(--fs-lg, 18px); }
      .dim { color: var(--mp-text-dim, #a3a3a3); font-size: var(--fs-sm, 12px); }
      .q { flex: none; width: 320px; max-width: 60vw; padding: 9px 12px; font: inherit; font-size: var(--fs-sm, 12px);
           color: var(--mp-text, #ededed); background: var(--mp-surface, #fff);
           border: 1px solid var(--mp-border, #dcdcd3); border-radius: var(--radius-1, 12px); }
      .arow { display: flex; align-items: baseline; gap: 10px; margin: var(--sp-5, 24px) 0 var(--sp-2, 8px); }
      .arow h3 { margin: 0; font-size: var(--fs-md, 14px); letter-spacing: 0.08em; }
      .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: var(--sp-2, 8px); }
      .tile { padding: 10px 12px; border: 1px solid var(--mp-border, #dcdcd3); border-radius: var(--radius-1, 12px);
              background: var(--mp-surface, #fff); display: grid; gap: 3px; }
      .tile.held { border-color: var(--mp-accent, #f0a10e); }
      .id { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11.5px; font-weight: 700;
            color: var(--mp-text, #ededed); letter-spacing: 0.01em; }
      .lbl { color: var(--mp-text-dim, #a3a3a3); font-size: 11px; }
      .on { color: var(--mp-accent-deep, #f7bc55); font-size: 11px; font-weight: 700; }
      .off { color: var(--mp-text-dim, #a3a3a3); font-size: 11px; opacity: 0.75; }
    `,
  ],
})
export class PermInventory {
  readonly access = inject(AccessService);
  readonly PERMISSION_CELL_COUNT = PERMISSION_CELL_COUNT;
  readonly PERM_AREAS = PERM_AREAS;

  readonly q = signal('');
  readonly shown = computed(() => {
    const q = this.q().toLowerCase().trim();
    if (!q) return PERMS;
    return PERMS.filter((p) => `${p.id} ${p.label} ${p.persona}`.toLowerCase().includes(q));
  });
  readonly visibleAreas = computed(() =>
    this.PERM_AREAS.filter((a) => this.byArea()[a]?.length ?? false),
  );
  readonly byArea = computed(() => {
    const out: Record<string, typeof PERMS> = {};
    for (const p of this.shown()) (out[p.area] ??= []).push(p);
    return out;
  });
  readonly heldCount = computed(() => {
    let n = 0;
    for (const p of PERMS) if (this.access.can(p.id)) n++;
    return n;
  });
  areaPersona(area: string) {
    return PERMS.find((p) => p.area === area)?.persona ?? '';
  }
}
