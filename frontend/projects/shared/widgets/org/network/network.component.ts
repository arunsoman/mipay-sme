import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { MiPayWidget } from '../../core/mipay-widget';
import { splitList, when, words } from '../../core/text';
import { DataCol, DataTable, MpCell } from '../../../../../src/app/design-system/organisms/data-table';
import { StatusBadge } from '../../../../../src/app/design-system/atoms/status-badge';
import { MpButton } from '../../../../../src/app/design-system/atoms/mp-button';
import { FormField } from '../../../../../src/app/design-system/molecules/form-field';
import { KvPair } from '../../../../../src/app/design-system/molecules/kv-pair';
import { MpAlert } from '../../../../../src/app/design-system/molecules/alert';
import { MpPerm } from '../../../web-core/perm-directive';
import { KYC_HINTS, refusal } from '../../../kyc/kyc-api';
import type { OrgNetworkConfig } from './network.widget';
import type { OrgHold, OrgNode, OrgSuspend, OrgSuspendRequest, OrgSuspensionLift } from './network.contract';

interface Row { node: OrgNode; depth: number }

/** Depth-first: each org followed by the orgs below it. */
function flatten(n: OrgNode, depth = 0, out: Row[] = []): Row[] {
  out.push({ node: n, depth });
  for (const c of n.children ?? []) flatten(c, depth + 1, out);
  return out;
}

@Component({
  selector: 'mpw-org-network',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DataTable, MpCell, StatusBadge, MpButton, FormField, KvPair, MpAlert, MpPerm],
  template: `
    <mp-data-table [cols]="cols()" [rows]="rows()" [pageSize]="config.pageSize" [selectable]="true"
                   placeholder="Search organizations" loading="Loading your network…" (rowClick)="pick($event)">
      <ng-template mpCell="name" let-r>
        <span class="name" [style.padding-left.px]="r.depth * 14">{{ r.depth ? '↳ ' : '' }}{{ r.node.name ?? 'Unnamed' }}</span>
      </ng-template>
      <ng-template mpCell="status" let-r><status-badge [status]="r.node.effectiveStatus ?? r.node.lifecycle ?? 'PENDING'" /></ng-template>
      <ng-template mpCell="act" let-r>
        @for (h of held(r.node); track h.id) {
          <mp-button *mpPerm="'org.suspension:administer'" data-action="lift" variant="ghost" (onClick)="lift(h, r.node)">Lift hold</mp-button>
        }
      </ng-template>
    </mp-data-table>

    @if (selected(); as n) {
      <section class="detail" data-selected-org>
        <div class="who"><b>{{ n.name }}</b> <span class="dim">{{ type(n.type) }}</span></div>
        <div class="kv">
          <kv-pair k="Status">{{ type(n.effectiveStatus) }}</kv-pair>
          <kv-pair k="Onboarded">{{ day(n.onboardedAt) }}</kv-pair>
          <kv-pair k="People">{{ n.userCount }}</kv-pair>
          <kv-pair k="Below it">{{ n.subtree?.descendants ?? 0 }} organizations</kv-pair>
          <kv-pair k="May do">{{ rights(n) }}</kv-pair>
        </div>
        @for (h of activeHolds(); track h.id) {
          <div class="hold" data-hold>
            <span>On hold since {{ day(h.createdAt) }}: {{ h.reason ?? 'no reason given' }}</span>
            <mp-button *mpPerm="'org.suspension:administer'" data-action="lift" variant="ghost" (onClick)="lift(h, n)">Lift</mp-button>
          </div>
        }
        @if (!isRoot(n)) {
          <ng-container *mpPerm="'org.suspension:administer'">
            <mp-form-field key="reason" label="Reason to suspend" [value]="reason()" (changed)="reason.set($event)"
                           hint="Suspending stops this organization and everything below it." />
            <mp-button data-action="suspend" variant="danger" [disabled]="!reason().trim()" (onClick)="suspend(n)">Suspend</mp-button>
          </ng-container>
        }
      </section>
    }
    @if (flash(); as f) {<mp-alert [kind]="f.kind" data-flash>{{ f.text }}</mp-alert>}
  `,
  styles: [`
    :host { display: grid; gap: var(--sp-3, 12px); min-width: 0; }
    .name { display: inline-block; overflow-wrap: anywhere; }
    .detail { display: grid; gap: var(--sp-2, 8px); padding-top: var(--sp-3, 12px); border-top: 1px solid var(--a-border, #2b2b2b); justify-items: start; }
    .who { display: flex; flex-wrap: wrap; gap: 4px 10px; align-items: baseline; overflow-wrap: anywhere; }
    .kv { display: grid; gap: var(--sp-1, 4px); }
    .hold { display: flex; flex-wrap: wrap; gap: var(--sp-2, 8px); align-items: center; color: var(--mp-amber, #f7c66b); }
    .dim { color: var(--a-dim, #a3a3a3); font-size: var(--fs-sm, 12.5px); }
  `],
})
export class OrgNetworkWidget extends MiPayWidget<OrgNetworkConfig> {
  readonly tree = signal<OrgNode | null>(null);
  readonly rows = computed(() => (this.tree() ? flatten(this.tree()!) : null));
  readonly selected = signal<OrgNode | null>(null);
  readonly activeHolds = computed(() => (this.selected() ? this.held(this.selected()!) : []));
  readonly reason = signal('');
  readonly flash = signal<{ kind: 'ok' | 'bad'; text: string } | null>(null);
  readonly type = words;
  readonly day = when;

  private readonly allCols: DataCol<Row>[] = [
    { key: 'name', label: 'Organization', search: (r) => r.node.name },
    { key: 'type', label: 'Type', text: (r) => words(r.node.type) },
    { key: 'people', label: 'People', text: (r) => r.node.userCount },
    { key: 'status', label: 'Status', text: (r) => words(r.node.effectiveStatus) },
    { key: 'act', label: '' },
  ];
  readonly cols = computed(() => (this.size().tier === 'xs'
    ? this.allCols.filter((c) => c.key === 'name' || c.key === 'status' || c.key === 'act') : this.allCols));

  constructor() {
    super();
    this.load();
  }

  private load(keep?: string | null) {
    this.data.query<OrgNode>('org.tree.get').subscribe({
      next: (t) => {
        this.tree.set(t);
        const again = keep ? flatten(t).find((r) => r.node.id === keep)?.node ?? null : null;
        this.selected.set(again);
      },
      error: () => undefined,
    });
  }

  /** The holds still on an org (not released). */
  held(n: OrgNode): OrgHold[] { return (n.holds ?? []).filter((h) => !h.releasedAt); }

  isRoot(n: OrgNode) { return n.id === this.tree()?.id; }
  rights(n: OrgNode) { return splitList(n.rights?.effDirect).map(words).join(', ') || '—'; }

  pick(r: Row) {
    // the flash stays: a Lift pressed in a row also lands here (the click reaches the row)
    this.selected.set(r.node);
    this.reason.set('');
    this.publish('orgName', r.node.name);
    this.publish('orgId', r.node.id);
  }

  suspend(n: OrgNode) {
    const body: OrgSuspendRequest = { reason: this.reason().trim() };
    this.data.command<OrgSuspend>('org.suspend', { id: n.id, body }).subscribe({
      next: () => { this.flash.set({ kind: 'ok', text: `${n.name} is suspended.` }); this.reason.set(''); this.load(n.id); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }

  lift(h: OrgHold, n: OrgNode) {
    const keep = this.selected()?.id ?? null;
    this.data.command<OrgSuspensionLift>('org.suspension.lift', { sid: h.id, body: {} }).subscribe({
      next: () => { this.flash.set({ kind: 'ok', text: `The hold on ${n.name} is lifted.` }); this.load(keep); },
      error: (e: unknown) => this.flash.set({ kind: 'bad', text: refusal(e, KYC_HINTS) }),
    });
  }
}
