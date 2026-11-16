import { WIDGETS } from '../registry.generated';
import { DEFAULT_LAYOUTS, defaultLayout } from './default-layouts';
import { seedLayout, validateLayout } from './layout';
import { layoutNavigation, type PublishedLayout } from '../../web-core/portal-layout';
import { requiredList } from './manifest';

const DEFS = new Map(WIDGETS.map((w) => [w.def.id, w.def]));
const PORTALS = ['PLATFORM', 'MANUFACTURER', 'DISTRIBUTOR', 'SUPPLIER'] as const;

describe('built-in portal layouts', () => {
  for (const portal of PORTALS) {
    const layout = DEFAULT_LAYOUTS[portal]!;

    it(`${portal}: only Onboarding, Users and Access`, () => {
      expect(layout.menus.map((m) => m.label.messages['en'])).toEqual(['Onboarding', 'Users', 'Access']);
    });

    it(`${portal}: valid for the builder, every widget registered for this portal at its current major`, () => {
      const findings = validateLayout(layout, DEFS);
      expect(findings.filter((f) => f.level === 'error')).toEqual([]);
      expect(findings.filter((f) => /placed at major/.test(f.text))).toEqual([]);
    });

    it(`${portal}: someone holding every cell sees every screen, as /p/<menu>/<screen> links`, () => {
      const nav = layoutNavigation(layout as unknown as PublishedLayout, () => true);
      const links = nav.flatMap((m) => m.screens.map((s) => s.link));
      expect(links.length).toBe(layout.menus.reduce((n, m) => n + m.screens.length, 0));
      expect(links.every((l) => l.startsWith('/p/'))).toBe(true);
    });
  }

  it('a person who reads only KYC sees only the KYC screens of an org portal', () => {
    const held = new Set(['kyc.application:read', 'kyc.form:read']);
    const can = (rows: string[]) => rows.some((r) => held.has(r));
    const nav = layoutNavigation(DEFAULT_LAYOUTS.SUPPLIER as unknown as PublishedLayout, can);
    expect(nav.map((m) => m.id)).toEqual(['onboarding']);
    expect(nav[0].screens.map((s) => s.id)).toContain('my-kyc');
    // and the screen's widgets are what decides it
    const myKyc = DEFAULT_LAYOUTS.SUPPLIER!.menus[0].screens.find((s) => s.id === 'my-kyc')!;
    expect(myKyc.items.some((it) => requiredList(DEFS.get(it.widgetId)!).some((r) => held.has(r)))).toBe(true);
  });

  it('the builder starts from the built-in layout, as a copy', () => {
    const seeded = seedLayout('SUPPLIER');
    expect(seeded).toEqual(DEFAULT_LAYOUTS.SUPPLIER);
    seeded.menus.pop();
    expect(DEFAULT_LAYOUTS.SUPPLIER!.menus.length).toBe(3);
    expect(defaultLayout('LENDER')).toBeNull();
  });
});
