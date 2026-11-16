/**
 * The built-in portal layouts: what a portal draws when nothing is published in the portal
 * builder, and what the builder starts from. They replace the coded menus (2026-11-16) and hold
 * only Onboarding, Users and Access; other menus come back as their widgets are built.
 *
 * Every screen is made of registered widgets. Widgets on one screen share the page selection, so
 * a list on the left (organizations, the network, a queue) drives the widgets next to it.
 *
 * Pure TypeScript (type-only imports), like layout.ts: the portals, the builder and CI read it.
 */
import type { BusinessPortal } from '../../web-core/portal-permission-catalogue';
import type { LayoutMenu, LayoutScreen, PlacedWidget, PortalLayout } from './layout';

type Item = [widgetId: string, config?: Record<string, unknown>];
type Screen = [id: string, label: string, items: Item[]];
type Menu = [id: string, icon: string, label: string, screens: Screen[]];

/** Widgets whose current major is 2 (the rest are at 1). */
const MAJOR_2 = new Set(['kyc.tasks', 'kyc.orgs', 'rbac.my-access']);

function build(portal: BusinessPortal, menus: Menu[]): PortalLayout {
  const p = portal.toLowerCase();
  return {
    format: 1, portal, version: 1, locales: ['en'],
    menus: menus.map(([mid, icon, mlabel, screens]): LayoutMenu => ({
      id: mid, icon,
      label: { key: `portal.${p}.${mid}`, messages: { en: mlabel } },
      screens: screens.map(([sid, slabel, items]): LayoutScreen => ({
        uid: `${mid}-${sid}`, id: sid,
        label: { key: `portal.${p}.${mid}.${sid}`, messages: { en: slabel } },
        items: items.map(([widgetId, config], i): PlacedWidget => ({
          instanceId: `${mid}-${sid}-${i + 1}`, widgetId, major: MAJOR_2.has(widgetId) ? 2 : 1,
          config: config ?? {},
        })),
      })),
    })),
  };
}

const REVIEW: Item = ['kyc.application', { mode: 'review' }];

/** MiPay staff: every organization, and the platform's own roles and access. */
const PLATFORM: Menu[] = [
  ['onboarding', 'onboarding', 'Onboarding', [
    ['requests', 'Onboarding requests', [['org.onboarding-queue'], REVIEW]],
    ['onboard', 'Onboard an organization', [['org.onboard-child']]],
    ['kyc-applications', 'KYC applications', [['kyc.orgs'], REVIEW]],
    ['kyc-review', 'KYC review queue', [['kyc.tasks'], REVIEW]],
    ['kyc-checks', 'KYC checks', [['admin.organizations'], ['ops.kyc-checks']]],
    ['network', 'Organization network', [['org.network'], ['org.rights'], ['org.role-ceiling']]],
  ]],
  ['users', 'users', 'Users', [
    ['org-users', 'Organization users', [['users.organization-users']]],
    ['people', 'People by organization', [['admin.organizations'], ['org.people'], ['users.sign-in']]],
    ['new-admin', 'New administrator', [['admin.organizations'], ['org.admin-recovery']]],
    ['sign-in', 'Sign-in access', [['admin.platform-access']]],
  ]],
  ['access', 'access', 'Access', [
    ['roles', 'Roles', [['rbac.roles']]],
    ['new-role', 'New role', [['rbac.new-role']]],
    ['assignments', 'Role assignments', [['rbac.platform-assignments']]],
    ['org-roles', 'Roles by organization', [['admin.organizations'], ['rbac.assignments'], ['rbac.role-approvals']]],
    ['org-limits', 'Organization limits', [['admin.organizations'], ['org.access'], ['org.rights'], ['org.role-ceiling']]],
    ['catalogue', 'Permission catalogue', [['rbac.resources']]],
    ['reconciliation', 'Reconciliation', [['admin.organizations'], ['rbac.reconciliation']]],
    ['my-access', 'My access', [['rbac.my-access']]],
    ['audit', 'Audit trail', [['ops.audit']]],
  ]],
];

/** An organization portal: its own KYC, the organizations below it, its people and roles. */
const ORG: Menu[] = [
  ['onboarding', 'onboarding', 'Onboarding', [
    ['my-kyc', 'My KYC', [['kyc.status'], ['kyc.application']]],
    ['onboard', 'Onboard an organization', [['org.onboard-child']]],
    ['requests', 'Onboarding requests', [['org.onboarding-queue'], REVIEW]],
    ['kyc-applications', 'KYC applications', [['kyc.orgs'], REVIEW]],
    ['kyc-review', 'KYC review queue', [['kyc.tasks'], REVIEW]],
    ['network', 'Our network', [['org.network'], ['org.rights'], ['org.role-ceiling']]],
  ]],
  ['users', 'users', 'Users', [
    ['org-users', 'Organization users', [['users.organization-users']]],
    ['sign-in', 'Sign-in and sessions', [['users.sign-in']]],
    ['new-admin', 'New administrator', [['org.network'], ['org.admin-recovery']]],
  ]],
  ['access', 'access', 'Access', [
    ['roles', 'Roles', [['rbac.roles']]],
    ['new-role', 'New role', [['rbac.new-role']]],
    ['assignments', 'Role assignments', [['org.people'], ['rbac.assignments']]],
    ['approvals', 'Role approvals', [['rbac.role-approvals']]],
    ['my-access', 'My access', [['rbac.my-access']]],
  ]],
];

export const DEFAULT_LAYOUTS: Partial<Record<BusinessPortal, PortalLayout>> = {
  PLATFORM: build('PLATFORM', PLATFORM),
  MANUFACTURER: build('MANUFACTURER', ORG),
  DISTRIBUTOR: build('DISTRIBUTOR', ORG),
  SUPPLIER: build('SUPPLIER', ORG),
};

/** A fresh copy of the portal's built-in layout (null when it has none). */
export function defaultLayout(portal: BusinessPortal): PortalLayout | null {
  const l = DEFAULT_LAYOUTS[portal];
  return l ? structuredClone(l) : null;
}
