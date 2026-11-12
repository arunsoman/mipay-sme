import { Injectable, signal } from '@angular/core';

/**
 * App-wide light/dark theme. Toggles the `.mp-dark` class on <body>
 * (the dark TPTS look is the default) and persists the choice in localStorage.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly dark = signal(this.read());

  constructor() {
    this.apply();
  }

  toggle() {
    this.dark.update((d) => !d);
    this.apply();
  }

  private read(): boolean {
    try {
      return localStorage.getItem('mp-theme') !== 'light';
    } catch {
      return true;
    }
  }

  private apply() {
    window.document.body.classList.toggle('mp-dark', this.dark());
    try {
      localStorage.setItem('mp-theme', this.dark() ? 'dark' : 'light');
    } catch {
      // storage unavailable (private mode etc.) - theme stays per-session
    }
  }
}
