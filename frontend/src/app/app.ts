import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { EnvironmentService } from './core/env.service';

/** App root: boots environment posture, then renders routes only. */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: `<router-outlet />`,
})
export class App implements OnInit {
  private readonly env = inject(EnvironmentService);
  ngOnInit() { void this.env.bootstrap(); }
}
