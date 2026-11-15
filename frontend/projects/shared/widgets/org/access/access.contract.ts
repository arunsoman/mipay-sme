/**
 * Contracts of org.access (AdminConsoleController#featuresOf / #setFeatures,
 * OrgFeatureService.AccessView). Hand-written: the generator does not read nested records.
 */
import { s, type Infer } from '../../core/shape';

/** GET /api/orgs/{id}/features and PUT answer: what an org can do (org.rights:read / :administer). */
export const ORG_FEATURES = s.object('AccessView', {
  orgId: s.string(),
  orgName: s.nullable(s.string()),
  orgType: s.string(),
  preset: s.nullable(s.string()),
  features: s.array(s.object('FeatureView', {
    id: s.string(),
    label: s.string(),
    screens: s.array(s.string()),
    on: s.boolean(),
    partial: s.boolean(),
  })),
  presets: s.array(s.object('Preset', {
    code: s.string(),
    label: s.string(),
    description: s.nullable(s.string()),
    features: s.array(s.string()),
  })),
  warnings: s.array(s.string()),
  role: s.string(),
  updatedAt: s.nullable(s.dateTime()),
  appliedAt: s.nullable(s.dateTime()),
  applyError: s.nullable(s.string()),
});
export type OrgFeatures = Infer<typeof ORG_FEATURES>;

/** PUT /api/orgs/{id}/features sends {preset?, features?}: features win; a preset alone means its features. */
export const ORG_FEATURES_SET_REQUEST = s.object('FeaturesBody', {
  preset: s.nullable(s.string()),
  features: s.nullable(s.array(s.string())),
});
export type OrgFeaturesSetRequest = Infer<typeof ORG_FEATURES_SET_REQUEST>;
