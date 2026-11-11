import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { AdminAuthService } from '../../core/auth';
import {
  RbacAssignmentView, RbacMatrixCell, RbacMatrixData, RbacMatrixRow,
  RbacRoleUpdate, RbacRoleView, RbacUserView, RBAC_VERBS, RbacVerb,
  readProblem,
} from '../../core/api';

/**
 * Permission matrix UI (§16 / Appendix G): a resource × verb grid where each
 * cell is a checkbox that grants or revokes that permission for the selected
 * role. Clicking a cell toggles it; unsaved changes show a save button.
 *
 * Rows are RBAC roles. Columns are the 10 fixed verbs. Clicking a row focuses
 * that role's permissions; clicking a cell toggles just that cell.
 */
@Component({
  selector: 'mpa-permission-matrix',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    <div class="head">
      <h2>Permission Matrix</h2>
      <button class="ghost" (click)="reload()">Refresh</button>
    </div>

    <div class="cols">
      <div class="panel roles">
        <div class="ptitle">Roles</div>
        @if (matrixLoading()) {<div class="muted">Loading…</div>}
        @if (problem(); as p) {<div class="err">{{ p }}</div>}
        @for (r of roles(); track r.name) {
          <div class="role"
               [class.selected]="selectedRole() === r.name"
               (click)="selectRole(r.name)"
               role="button" tabindex="0" (keydown.enter)="selectRole(r.name)">
            <span class="nm">{{ r.name }}</span>
            <span class="chip small">{{ r.permissions.length }} perms</span>
          </div>
        } @empty {
          <div class="muted small">No roles found.</div>
        }
      </div>

      <div class="panel matrix">
        @if (selectedRole(); as roleName) {
          <div class="ptitle">Permissions · {{ roleName }}
            <span class="muted small">({{ data()?.rows?.length ?? 0 }} resources × {{ data()?.verbs?.length ?? 0 }} verbs)</span>
          </div>

          @if (matrixLoading()) {<div class="muted">Loading matrix…</div>}
          @if (matrixError(); as e) {<div class="err">{{ e }}</div>}

          <div class="matrix-wrap">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="resource-col">Resource</th>
                  @for (v of verbs(); track v) {
                    <th class="verb-col" title="{{ v }}">{{ v.charAt(0) }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of data()?.rows ?? []; track row.resource) {
                  <tr class="resource-row">
                    <td class="resource-cell">{{ row.resource }}</td>
                    @for (v of verbs(); track v) {
                      <td class="verb-cell">
                        <label class="cell"
                               [class.granted]="cellValue(row.resource, v) === 'granted'"
                               [class.not-granted]="cellValue(row.resource, v) === 'not-granted'"
                               [class.na]="cellValue(row.resource, v) === 'n/a'"
                               [class.dirty]="isDirty(row.resource, v)"
                               [class.locked]="isLocked(row.resource, v)"
                               (click)="toggleCell(row.resource, v)">
                          <input type="checkbox"
                                 [checked]="cellValue(row.resource, v) === 'granted'"
                                 [disabled]="isLocked(row.resource, v)"
                                 (change)="toggleCell(row.resource, v, $event)" />
                        <span class="visually-hidden">{{ v }}</span>
                      </label>
                    </td>
                  }
                </tr>
              }   <- close row loop
              </tbody>
            </table>
          </div>

          @if (dirty()) {
            <div class="actions">
              <button class="ghost" (click)="resetChanges()">Discard changes</button>
              <button class="primary" [disabled]="busy()" (click)="saveChanges()">Save changes</button>
            </div>
          }
        } @else {
          <div class="muted pad">Select a role on the left to edit its permissions.</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
    h2 { margin: 0; font-size: 20px; line-height: 1.1; }
    .cols { display: grid; grid-template-columns: 340px 1fr; gap: 14px; align-items: start; }
    .panel { padding: 12px; border: 1px solid var(--a-border); border-radius: 8px; }
    .ptitle { font-size: 13px; font-weight: 600; margin-bottom: 10px; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .roles { max-height: 70vh; overflow: auto; }
    .role { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 6px; cursor: pointer; border: 1px solid transparent; }
    .role:hover { background: var(--a-panel-2); }
    .role.selected { background: color-mix(in srgb, var(--a-teal) 12%, transparent); border-color: var(--a-teal); outline: 1px solid var(--a-teal); }
    .nm { font-size: 13px; font-weight: 600; }
    .chip.small { font-size: 10px; padding: 1px 7px; }
    .matrix { overflow: auto; }
    .matrix-wrap { overflow-x: auto; }
    .matrix-table { border-collapse: collapse; width: 100%; min-width: 900px; font-size: 12px; }
    .matrix-table th { background: var(--a-panel-2); border: 1px solid var(--a-border); padding: 6px 4px; text-align: center; font-size: 10px; font-weight: 600; color: var(--a-dim); text-transform: uppercase; letter-spacing: 0.5px; }
    .matrix-table td { border: 1px solid var(--a-border); padding: 4px; text-align: center; height: 34px; }
    .resource-col { text-align: left; font-weight: 600; white-space: nowrap; }
    .verb-col { width: 36px; }
    .cell { display: inline-flex; align-items: center; justify-content: center; width: 28px; height: 28px; border-radius: 6px; cursor: pointer; position: relative; }
    .cell:hover { background: var(--a-panel-2); }
    .cell input { width: 18px; height: 18px; cursor: pointer; accent-color: var(--a-teal); }
    .cell.granted { background: color-mix(in srgb, var(--a-green) 15%, transparent); }
    .cell.not-granted { background: var(--a-panel); }
    .cell.na { background: color-mix(in srgb, var(--a-dim) 10%, transparent); }
    .cell.na input { opacity: 0.3; cursor: not-allowed; }
    .cell.dirty { outline: 2px solid var(--a-amber, #d97706); outline-offset: -2px; }
    .cell.locked input { opacity: 0.2; cursor: not-allowed; }
    .visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
    .actions { margin-top: 14px; display: flex; gap: 10px; }
    .muted { color: var(--a-dim); font-size: var(--fs-sm); }
    .pad { padding: 20px; }
    .err { color: var(--a-red); font-size: var(--fs-sm); margin: 6px 0; }
  `],
})
export class PermissionMatrix implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AdminAuthService);

  readonly roles = signal<RbacRoleView[]>([]);
  readonly selectedRole = signal<string | null>(null);
  readonly matrix = signal<RbacMatrixData | null>(null);
  readonly matrixLoading = signal(false);
  readonly matrixError = signal('');
  readonly problem = signal('');
  readonly busy = signal(false);

  readonly dirty = computed(() => {
    for (const [resource, workMap] of this.matrixWork.entries()) {
      const baseMap = this.baseMatrix.get(resource);
      if (!baseMap) return true;
      for (const [verb, value] of workMap.entries()) {
        if (baseMap.get(verb) !== value) return true;
      }
    }
    return false;
  });

  // working copy: resource -> verb -> 'granted' | 'not-granted' | 'n/a'
  private matrixWork = new Map<string, Map<RbacVerb, 'granted' | 'not-granted' | 'n/a'>>();
  private baseMatrix = new Map<string, Map<RbacVerb, 'granted' | 'not-granted' | 'n/a'>>();

  readonly data = computed(() => this.matrix());
  readonly verbs = computed(() => RBAC_VERBS);

  async ngOnInit() {
    await this.loadRoles();
    // If a role was previously selected (not possible on first load), load its matrix
  }

  async reload() {
    this.matrix.set(null);
    this.selectedRole.set(null);
    this.matrixWork.clear();
    this.baseMatrix.clear();
    await this.loadRoles();
  }

  private async loadRoles() {
    this.matrixLoading.set(true);
    this.matrixError.set('');
    try {
      const roles = await firstValueFrom(this.http.get<RbacRoleView[]>('/api/admin/rbac/roles'));
      this.roles.set(roles);
    } catch (e) {
      this.matrixError.set(readProblem(e).detail || 'Failed to load roles');
    } finally {
      this.matrixLoading.set(false);
    }
  }

  selectRole(name: string) {
    if (this.selectedRole() === name) return;
    this.selectedRole.set(name);
    this.matrixWork.clear();
    this.baseMatrix.clear();
    this.loadRoleMatrix(name);
  }

  private async loadRoleMatrix(roleName: string) {
    this.matrixLoading.set(true);
    this.matrixError.set('');
    try {
      const role = await firstValueFrom(this.http.get<RbacRoleView>(`/api/admin/rbac/roles/${name}`));

      // Build the matrix: for every resource mentioned in any role's permissions,
      // create a row. For each verb, check if the permission exists in this role.
      const allResources = new Set<string>();
      const allPerms = new Set<string>();
      for (const r of this.roles()) {
        for (const p of r.permissions) {
          allPerms.add(p);
          // permission format: "resource:verb"
          const parts = p.split(':');
          if (parts.length === 2) {
            allResources.add(parts[0]);
          }
        }
      }

      const sortedResources = [...allResources].sort();
      const rows: RbacMatrixRow[] = sortedResources.map(resource => {
        const cells: Record<RbacVerb, 'granted' | 'not-granted' | 'n/a'> = {} as Record<RbacVerb, 'granted' | 'not-granted' | 'n/a'>;
        const verbMap = new Map<RbacVerb, 'granted' | 'not-granted' | 'n/a'>();

        for (const v of RBAC_VERBS) {
          const perm = `${resource}:${v}`;
          const granted = role.permissions.includes(perm);
          const val: 'granted' | 'not-granted' | 'n/a' = granted ? 'granted' : 'not-granted';
          cells[v] = val;
          verbMap.set(v, val);
        }

        this.matrixWork.set(resource, verbMap);
        this.baseMatrix.set(resource, new Map(verbMap));

        return { resource, cells };
      });

      this.matrix.set({ rows, verbs: RBAC_VERBS });
    } catch (e) {
      this.matrixError.set(readProblem(e).detail || 'Failed to load role matrix');
    } finally {
      this.matrixLoading.set(false);
    }
  }

  cellValue(resource: string, verb: RbacVerb): 'granted' | 'not-granted' | 'n/a' {
    return this.matrixWork.get(resource)?.get(verb) ?? 'not-granted';
  }

  isDirty(resource: string, verb: RbacVerb): boolean {
    const base = this.baseMatrix.get(resource)?.get(verb);
    const current = this.matrixWork.get(resource)?.get(verb);
    return base !== current;
  }

  isLocked(resource: string, verb: RbacVerb): boolean {
    // All cells are editable in the admin portal; platform staff can set any permission
    return false;
  }

  toggleCell(resource: string, verb: RbacVerb, event?: Event) {
    if (event) {
      event.preventDefault();
      event.stopPropagation();
    }
    const verbMap = this.matrixWork.get(resource);
    if (!verbMap) return;
    const current = verbMap.get(verb) ?? 'not-granted';
    const next: 'granted' | 'not-granted' | 'n/a' = current === 'granted' ? 'not-granted' : 'granted';
    verbMap.set(verb, next);
  }

  async saveChanges() {
    const roleName = this.selectedRole();
    if (!roleName) return;

    // Build the permissions list from the working copy
    const permissions: string[] = [];
    for (const [resource, verbMap] of this.matrixWork.entries()) {
      for (const [verb, value] of verbMap.entries()) {
        if (value === 'granted') {
          permissions.push(`${resource}:${verb}`);
        }
      }
    }

    this.busy.set(true);
    try {
      const update: RbacRoleUpdate = { permissions };
      const updated = await firstValueFrom(
        this.http.put<RbacRoleView>(`/api/admin/rbac/roles/${roleName}`, update)
      );

      // Update base to match work
      for (const [resource, workMap] of this.matrixWork.entries()) {
        const baseMap = this.baseMatrix.get(resource)!;
        for (const [verb, value] of workMap.entries()) {
          baseMap.set(verb, value);
        }
      }

      this.roles.update(roles =>
        roles.map(r => r.name === roleName ? { ...r, permissions: updated.permissions } : r)
      );
      this.busy.set(false);
    } catch (e) {
      this.busy.set(false);
      alert(readProblem(e).detail || 'Failed to save role permissions');
    }
  }

  resetChanges() {
    // Restore work from base
    for (const [resource, baseMap] of this.baseMatrix.entries()) {
      const workMap = this.matrixWork.get(resource)!;
      for (const [verb, value] of baseMap.entries()) {
        workMap.set(verb, value);
      }
    }
  }
}
