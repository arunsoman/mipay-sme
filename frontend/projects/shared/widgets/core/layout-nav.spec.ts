import { findScreen, labelOf, layoutNavigation, screenVisible, type PublishedLayout } from '../../web-core/portal-layout';

/** A published PLATFORM layout: one builder screen with widgets, one carried-over coded screen. */
const LAYOUT: PublishedLayout = {
  format: 1, portal: 'PLATFORM', version: 3, locales: ['en', 'af'],
  menus: [
    { id: 'reviews', icon: '', label: { key: 'portal.platform.reviews', messages: { en: 'Reviews', af: 'Hersienings' } }, screens: [
      { uid: 's1', id: 'board', label: { key: 'portal.platform.reviews.board', messages: { en: 'Review board', af: 'Bord' } },
        items: [{ instanceId: 'i1', widgetId: 'kyc.tasks', major: 2, config: {} }, { instanceId: 'i2', widgetId: 'kyc.application', major: 1, config: {} }] },
    ] },
    { id: 'people', icon: '', label: { key: 'portal.platform.people', messages: { en: 'People' } }, screens: [
      // seeded from today's menus, no widgets: keeps today's page (/rbac) and today's rule (rbac.role)
      { uid: 'PLATFORM/access/roles', legacy: true, id: 'roles', label: { key: 'portal.platform.people.roles', messages: { en: 'Roles' } }, items: [] },
      // an empty new screen has nowhere to go: never shown
      { uid: 's9', id: 'empty', label: { key: 'portal.platform.people.empty', messages: { en: 'Soon' } }, items: [] },
    ] },
  ],
};
const holds = (...cells: string[]) => (rows: string[]) => rows.some((r) => cells.some((c) => c === r || c.startsWith(r + ':')));

describe('published layouts in the portals', () => {
  it('draws the builder’s menus: widget screens at /p/<menu>/<screen>, carried-over screens at today’s page', () => {
    const nav = layoutNavigation(LAYOUT, holds('kyc.task:read', 'rbac.role:read'));
    expect(nav).toEqual([
      { id: 'reviews', label: 'Reviews', screens: [{ id: 'board', label: 'Review board', link: '/p/reviews/board' }] },
      { id: 'people', label: 'People', screens: [{ id: 'roles', label: 'Roles', link: '/rbac' }] },
    ]);
  });

  it('a screen shows only when one of its widgets may be used; a menu with nothing to show disappears', () => {
    const nav = layoutNavigation(LAYOUT, holds('rbac.role:read'));
    expect(nav.map((m) => m.id)).toEqual(['people']);
    expect(screenVisible(LAYOUT.menus[0].screens[0], holds('kyc.application:read', 'kyc.form:read'))).toBe(true);
    expect(screenVisible(LAYOUT.menus[0].screens[0], holds('kyc.application:read'))).toBe(false);
  });

  it('a carried-over screen also needs its route to be allowed', () => {
    const nav = layoutNavigation(LAYOUT, holds('rbac.role:read'), () => false);
    expect(nav).toEqual([]);
  });

  it('finds a screen by its menu and screen ids', () => {
    expect(findScreen(LAYOUT, 'reviews', 'board')?.uid).toBe('s1');
    expect(findScreen(LAYOUT, 'reviews', 'nope')).toBeNull();
    expect(findScreen(null, 'reviews', 'board')).toBeNull();
  });

  it('labels follow the browser language when the layout has it, else English', () => {
    expect(labelOf({ key: 'k', messages: { en: 'Reviews' } })).toBe('Reviews');
    expect(labelOf({ key: 'k', messages: {} })).toBe('k');
  });
});
