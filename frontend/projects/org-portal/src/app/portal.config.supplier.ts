/**
 * Build-time portal identity. The three org portals share one codebase; each
 * angular.json project swaps this file in via fileReplacements:
 *   manufacturer-portal -> portal.config.ts (this default)
 *   distributor-portal  -> portal.config.distributor.ts
 *   supplier-portal     -> portal.config.supplier.ts
 */
export interface PortalConfig {
  /** Org type whose ADMINs may sign in here (matches /api/me orgType). */
  orgType: 'MANUFACTURER' | 'DISTRIBUTOR' | 'SUPPLIER';
  label: string;
  accent: string;
  /** localStorage key; distinct per portal so sessions never bleed across. */
  storageKey: string;
}

export const PORTAL: PortalConfig = {
  orgType: 'SUPPLIER',
  label: 'Supplier',
  accent: '#5ab4f0',
  storageKey: 'mp.supplier.token',
};
