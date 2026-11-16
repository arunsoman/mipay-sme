import { booleanAttribute, ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { MpCheckbox } from '../atoms/mp-checkbox';
import {
  GridMenu, GridWidget, OPERATION_LABEL, opState, ROLE_OPERATIONS, RoleOperation, toggleOperation,
} from '../../../../projects/shared/rbac/role-operations-model';

/**
 * Organism: what a role may do, widget by widget (shared/rbac/role-operations-model.ts). A portal's
 * menus › submenus › widgets, each widget with the four operations View / Create / Modify /
 * Approve as tick boxes. The four column titles stay visible no matter what: the header row is
 * sticky while the grid scrolls, and every widget shows all four - only the operations it
 * supports can be ticked; the others are greyed and not clickable. Cells inherited from the
 * roles it builds on show ticked and locked. With `readonly` nothing can be ticked: the grid only
 * shows what a role (or a person) may do.
 *
 * It only edits `selected` (the role's own cells):
 * <mp-operations-grid [menus]="tree()" [selected]="cells()" [inherited]="fromBases()" (selectedChange)="cells.set($event)" />
 * <mp-operations-grid [menus]="tree()" [selected]="held()" readonly />
 */
@Component({
  selector: 'mp-operations-grid',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpCheckbox],
  template: `
    <div class="scroll" [style.max-height]="maxHeight()" role="region" aria-label="Operations by widget" tabindex="0">
      <table>
        <thead>
          <tr>
            <th scope="col" class="what">Menu › submenu › widget</th>
            @for (op of ops; track op) {<th scope="col" class="op" [attr.data-op]="op">{{ label[op] }}</th>}
          </tr>
        </thead>
        @for (m of menus(); track m.id) {
          <tbody>
            <tr class="menu"><th scope="rowgroup" [attr.colspan]="ops.length + 1">{{ m.label }}</th></tr>
            @for (s of m.submenus; track s.key) {
              <tr class="sub"><th scope="rowgroup" [attr.colspan]="ops.length + 1">{{ s.label }}</th></tr>
              @for (w of s.widgets; track w.id) {
                <tr class="w" [attr.data-widget]="w.id" [attr.data-submenu]="s.key">
                  <th scope="row" class="what"><span class="wl">{{ w.label }}</span>
                    @if (w.description) {<span class="wd">{{ w.description }}</span>}</th>
                  @for (op of ops; track op) {
                    @let st = state(w, op);
                    <td class="op" [attr.data-op]="op" [attr.data-state]="st">
                      <mp-checkbox [checked]="st === 'own' || st === 'inherited'" [disabled]="disabled() || st === 'unsupported'"
                                   [locked]="!readonly() && st === 'inherited'" [readonly]="readonly() && st !== 'unsupported'" [ariaLabel]="label[op] + ' on ' + w.label"
                                   [title]="hint(w, op, st)" (checkedChange)="set(w, op, $event)" />
                    </td>
                  }
                </tr>
              }
            }
          </tbody>
        }
      </table>
      @if (!menus().length) {<div class="none">{{ empty() }}</div>}
    </div>
  `,
  styles: [`
    :host { display: block; min-width: 0; container-type: inline-size; }
    .scroll { overflow: auto; border: 1px solid var(--mp-border); border-radius: var(--radius-2, 8px); }
    table { width: 100%; table-layout: fixed; border-collapse: separate; border-spacing: 0; font-size: var(--fs-sm, 13px); }
    th, td { overflow-wrap: anywhere; }
    thead th {
      position: sticky; top: 0; z-index: 2; background: var(--mp-surface-2, var(--mp-surface));
      font-weight: 700; text-align: left; padding: 10px 12px; border-bottom: 1px solid var(--mp-border);
    }
    th.op, td.op { width: 84px; text-align: center; }
    .menu th { text-align: left; padding: 10px 12px 4px; font-weight: 700; color: var(--mp-accent-deep, var(--mp-accent)); background: var(--mp-bg); }
    .sub th { text-align: left; padding: 4px 12px 4px 24px; font-weight: 600; color: var(--mp-text-dim, #a3a3a3); background: var(--mp-bg); }
    .w th, .w td { padding: 6px 12px; border-top: 1px solid var(--mp-border); vertical-align: middle; }
    .w th { padding-left: 40px; font-weight: 400; text-align: left; }
    .wl { display: block; }
    .wd { display: block; font-size: var(--fs-xs, 11px); color: var(--mp-text-dim, #a3a3a3); }
    .none { padding: 16px; color: var(--mp-text-dim, #a3a3a3); }
    @container (max-width: 520px) {
      th.op, td.op { width: 50px; padding-left: 0; padding-right: 0; }
      thead th { font-size: var(--fs-xs, 11px); padding-left: 8px; padding-right: 8px; }
      thead th.op { padding-left: 0; padding-right: 0; overflow-wrap: normal; }
      .w th { padding-left: 16px; }
      .sub th { padding-left: 12px; }
      .wd { display: none; }
    }
  `],
})
export class OperationsGrid {
  readonly menus = input.required<GridMenu[]>();
  readonly selected = input.required<Set<string>>();
  readonly inherited = input<Set<string>>(new Set());
  readonly disabled = input(false);
  /** Show only: no box can be changed. */
  readonly readonly = input(false, { transform: booleanAttribute });
  /** The grid scrolls inside this height; its header stays on top. */
  readonly maxHeight = input('min(70vh, 640px)');
  readonly empty = input('No widgets on this portal yet.');
  readonly selectedChange = output<Set<string>>();

  readonly ops = ROLE_OPERATIONS;
  readonly label = OPERATION_LABEL;

  state(w: GridWidget, op: RoleOperation) { return opState(w, op, this.selected(), this.inherited()); }

  hint(w: GridWidget, op: RoleOperation, st: string): string {
    if (st === 'unsupported') return `${w.label} has no ${OPERATION_LABEL[op]} operation`;
    if (this.readonly()) return st === 'off' ? `No ${OPERATION_LABEL[op]} on ${w.label}` : `${OPERATION_LABEL[op]} on ${w.label}`;
    if (st === 'inherited') return 'From a role it builds on';
    return w.cells[op].join(', ');
  }

  set(w: GridWidget, op: RoleOperation, on: boolean) {
    if (this.readonly()) return;
    this.selectedChange.emit(toggleOperation(w, op, on, this.selected(), this.inherited()));
  }
}
