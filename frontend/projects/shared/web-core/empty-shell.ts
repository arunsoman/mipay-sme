import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { AccessService } from './access';

/**
 * The empty shell (RBAC grid, fapi2-design App G): an authenticated user whose org has
 * granted them nothing. The portal chrome (brand, session, theme) still renders around
 * it - this component takes the content area. Every granted right turns into a nav
 * item the moment it exists; "Reload my rights" re-reads /api/orgs/me/access.
 */
@Component({
  selector: 'mpc-empty-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card">
      <div class="glyph">⚿</div>
      <h2>No rights assigned yet</h2>
      <p>
        You are signed in{{ access.orgName() ? ' to ' + access.orgName() : '' }} but nothing is
        unlocked for this account.
      </p>
      <p class="dim">
        Every feature appears the moment your organisation's administrator grants a right (Users
        &amp; rights). New sign-in needed after a grant? Use "Reload my rights".
      </p>
      <button type="button" (click)="reload()" [disabled]="busy()">
        {{ busy() ? 'Checking...' : 'Reload my rights' }}
      </button>
    </div>
  `,
  styles: [
    `
      .card {
        max-width: 460px; margin: 6vh auto 0; text-align: center;
        background: var(--mp-surface, #fff);
        border: 1px solid var(--mp-border, #dcdcd3);
        border-radius: var(--radius-2, 10px);
        box-shadow: var(--shadow-1, none);
        color: var(--mp-text, #111);
        padding: var(--sp-8, 48px) var(--sp-5, 24px);
        display: grid; gap: 12px; justify-items: center;
        font-family: var(--font, 'Figtree', sans-serif);
      }
      .glyph {
        width: 52px; height: 52px; border-radius: 50%;
        display: grid; place-items: center; font-size: 22px;
        background: var(--mp-accent, #f0a10e); color: var(--mp-accent-ink, #141004);
      }
      h2 { margin: 0; font-size: var(--fs-lg, 18px); font-weight: 700; }
      p { margin: 0; font-size: var(--fs-md, 14px); }
      .dim { color: var(--mp-text-dim, #6d6d67); font-size: var(--fs-sm, 12px); max-width: 40ch; }
      button {
        margin-top: 4px; padding: 8px 18px; font: inherit; font-weight: 600;
        border: 1px solid var(--mp-border, #dcdcd3); border-radius: var(--radius-1, 6px);
        background: transparent; color: var(--mp-text, #111); cursor: pointer;
      }
      button:disabled { opacity: 0.6; cursor: wait; }
    `,
  ],
})
export class EmptyAccessShell {
  readonly access = inject(AccessService);
  readonly busy = signal(false);

  reload() {
    this.busy.set(true);
    void this.access
      .load(true)
      .finally(() => this.busy.set(false));
  }
}
