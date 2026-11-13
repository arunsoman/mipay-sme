import { MpSpinner } from '../../../src/app/design-system/atoms/mp-spinner';
import { MpAlert } from '../../../src/app/design-system/molecules/alert';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { refusal } from '../kyc/kyc-api';
import { RBAC_HINTS, RoleCeilingView } from './rbac-api';
import { DataCol, DATA_TABLE } from '../../../src/app/design-system/organisms/data-table';

/**
 * An org's role ceiling (docs/plans/extended-rbac.md X1-X5): the roles its users may hold
 * and the ones it may hand down to organizations it onboards. Editable only by an org above
 * it (or MiPay staff); the server refuses everyone else.
 */
@Component({
  selector: 'mp-role-ceiling',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MpSpinner, MpAlert, ...DATA_TABLE],
  template: `
    <div class="block">
      <div class="btitle">Roles it may use</div>
      @if (error(); as e) {<mp-alert kind="bad">{{ e }}</mp-alert>}
      @if (view(); as v) {
        <div class="muted small">
          @if (v.explicit) { Set by the organization above it. }
          @else if (v.unrestricted) { No ceiling anywhere above it: every organization role is available. }
          @else { Inherited from an organization above it ({{ v.inheritedFrom }}). }
        </div>
        <mp-data-table [cols]="cols" [rows]="rows()" placeholder="Search roles" empty="No roles." noMatch="No role matches your search.">
          <ng-template mpCell="name" let-r><b>{{ r.name }}</b>
            @if (isBaseline(r.name)) {<span class="chip">always</span>}
            @if (r.description) {<div class="muted small">{{ r.description }}</div>}</ng-template>
          <ng-template mpCell="use" let-r>
            @if (editing()) {<input type="checkbox" [checked]="wRoles().has(r.name)" [disabled]="isBaseline(r.name) || !offered(r.name)" (change)="toggleRole(r.name)" />}
            @else {{{ v.roles.includes(r.name) ? '✓' : '—' }}}
          </ng-template>
          <ng-template mpCell="hand" let-r>
            @if (editing()) {<input type="checkbox" [checked]="wHand().has(r.name)" [disabled]="!wRoles().has(r.name)" (change)="toggleHand(r.name)" />}
            @else {{{ v.delegableRoles.includes(r.name) ? '✓' : '—' }}}
          </ng-template>
        </mp-data-table>
        @if (editable()) {
          <div class="row">
            @if (editing()) {
              <button class="primary" (click)="save()" [disabled]="busy()">Save</button>
              <button class="ghost" (click)="inherit()" [disabled]="busy() || !v.explicit">Inherit from above</button>
              <button class="ghost" (click)="editing.set(false)">Cancel</button>
            } @else {
              <button class="primary" (click)="startEdit()">Change the roles it may use</button>
            }
          </div>
          <div class="hint">Narrowing applies at once: people holding a role that falls outside the ceiling lose it on their
            next request, and organizations below are narrowed too. The administrator and KYC roles are always kept.</div>
        }
      } @else if (!error()) {
        <mp-spinner label="Loading…" />
      }
    </div>
  `,
  styles: [`
    .block { display: grid; gap: 8px; }
    .grid input { width: auto; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; }
    .small { font-size: var(--fs-xs); }
    .chip { margin-left: 6px; }
  `],
})
export class RoleCeiling {
  readonly orgId = input.required<string>();
  /** Show the editor (the caller is above this org); the server still decides. */
  readonly editable = input(false);
  readonly changed = output<RoleCeilingView>();

  private readonly http = inject(HttpClient);
  readonly view = signal<RoleCeilingView | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly editing = signal(false);
  readonly wRoles = signal<Set<string>>(new Set());
  readonly wHand = signal<Set<string>>(new Set());

  /** Every catalogue role, plus anything held outside it (shown so it can be removed). */
  readonly rows = computed(() => {
    const v = this.view();
    if (!v) return [];
    const desc = new Map(v.catalogue.map((c) => [c.name, c.description]));
    const names = new Set([...v.catalogue.map((c) => c.name), ...v.roles]);
    return [...names].sort().map((name) => ({ name, description: desc.get(name) ?? '' }));
  });
  readonly cols: DataCol<{ name: string; description: string }>[] = [
    { key: 'name', label: 'Role', search: (r) => [r.name, r.description] },
    { key: 'use', label: 'May use' },
    { key: 'hand', label: 'May hand down' },
  ];

  constructor() {
    effect(() => { const id = this.orgId(); if (id) void this.load(id); });
  }

  isBaseline(r: string) { return this.view()?.baselineRoles.includes(r) ?? false; }
  offered(r: string) { return this.view()?.parentHandDown.includes(r) || this.isBaseline(r); }

  async load(id: string) {
    this.error.set('');
    this.editing.set(false);
    try {
      this.view.set(await firstValueFrom(this.http.get<RoleCeilingView>(`/api/orgs/${id}/role-ceiling`)));
    } catch (e) {
      this.error.set(refusal(e, RBAC_HINTS));
    }
  }

  startEdit() {
    const v = this.view();
    if (!v) return;
    this.wRoles.set(new Set(v.roles.filter((r) => this.offered(r))));
    this.wHand.set(new Set(v.delegableRoles));
    this.editing.set(true);
  }

  toggleRole(r: string) {
    const s = new Set(this.wRoles());
    if (s.has(r)) {
      s.delete(r);
      const h = new Set(this.wHand());
      h.delete(r);
      this.wHand.set(h);
    } else s.add(r);
    this.wRoles.set(s);
  }

  toggleHand(r: string) {
    const h = new Set(this.wHand());
    if (h.has(r)) h.delete(r); else h.add(r);
    this.wHand.set(h);
  }

  save() { return this.put({ roles: [...this.wRoles()], delegableRoles: [...this.wHand()], inherit: false }); }

  inherit() { return this.put({ roles: [], delegableRoles: [], inherit: true }); }

  private async put(body: { roles: string[]; delegableRoles: string[]; inherit: boolean }) {
    this.busy.set(true);
    this.error.set('');
    try {
      const v = await firstValueFrom(this.http.put<RoleCeilingView>(`/api/orgs/${this.orgId()}/role-ceiling`, body));
      this.view.set(v);
      this.editing.set(false);
      this.changed.emit(v);
    } catch (e) {
      this.error.set(refusal(e, RBAC_HINTS));
    } finally {
      this.busy.set(false);
    }
  }
}
