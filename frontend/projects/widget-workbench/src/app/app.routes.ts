import { Routes } from '@angular/router';
import { WIDGETS } from '../../../shared/widgets/registry.generated';
import { WidgetPage } from './widget-page';
import { RegistryPage } from './registry-page';
import { BuilderPage } from './builder/builder-page';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: WIDGETS.length ? `w/${WIDGETS[0].def.id}` : 'registry' },
  { path: 'w/:id', component: WidgetPage },
  { path: 'registry', component: RegistryPage },
  { path: 'builder', component: BuilderPage },
  { path: '**', redirectTo: 'registry' },
];
