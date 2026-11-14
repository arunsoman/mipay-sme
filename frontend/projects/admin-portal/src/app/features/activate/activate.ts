import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Account passwords are set at the IdP through its one-time link. */
@Component({
  selector: 'mpa-activate',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="stage">
      <div class="card">
        <h1>Set up your account</h1>
        <p>Ask your organization administrator for a new set-up link. Open that link to set your password securely at the identity provider.</p>
        <p>If your link has expired or was already used, ask the administrator to issue another.</p>
        <a routerLink="/login">Back to sign in</a>
      </div>
    </div>
  `,
  styles: [`
    .stage { max-width: 440px; margin: 12vh auto 0; padding: 16px; }
    .card { background: var(--a-panel); border: 1px solid var(--a-border); border-radius: var(--radius); padding: 18px; }
  `],
})
export class Activate {}
