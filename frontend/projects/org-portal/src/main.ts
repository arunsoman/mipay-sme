import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';
import { PORTAL } from './app/portal.config';

document.title = `MiPay ${PORTAL.label} Portal`;
document.documentElement.style.setProperty('--portal-accent', PORTAL.accent);

bootstrapApplication(App, appConfig).catch((err) => console.error(err));
