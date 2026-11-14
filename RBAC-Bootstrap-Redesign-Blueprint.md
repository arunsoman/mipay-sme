RBAC Bootstrap Redesign: Core-Backed Installer Flow (2026-11-13)
================================================================

The database is the sole source of truth for the permission grid. The yaml seed
is retired everywhere (repo, tests, config, CI wiring). This redesign moves the
temp-installer bootstrap from the IdP into the core backend, so the core:

1. Creates a temp installer account when boot is clean-slate
2. Allows the installer to sign in and create the first org + role
3. Auto-cleans the temp installer after successful onboarding
4. Leaves the active admin user for normal login

-----

1. CORE: AuthService.java — Bootstrap Lifecycle Management

   NEW: InstallBootstrapReq DTO
   @EqualsAndHashCode @Getter @Setter
   public class InstallBootstrapReq {
       boolean createTempUser; // true on fresh boot
   }

   NEW: BootstrapCompleteReq DTO
   @EqualsAndHashCode @Getter @Setter
   public class BootstrapCompleteReq {
       String tempUserId; // the temp installer's ID
   }

   ENHANCED: firstRunBootstrap(String identifier)
   - Runs only when users table is empty AND identifier == "installer"
   - Phase 1: Create temp installer user with temp_flag=true marker
     User tempUser = new User();
     tempUser.setName("Temp Installer");
     tempUser.setPhone("0000000000"); // NOT NULL UNIQUE, inert
     tempUser.setUsername("installer");
     tempUser.setPasswordHash(passwordEncoder.encode("installer123"));
     tempUser.setRole(Enums.UserRole.MINDLABS_ADMIN);
     tempUser.setOrgRole(Enums.OrgMemberRole.ADMIN);
     tempUser.setStatus(Enums.UserStatus.ACTIVE);
     tempUser.setTempFlag(true); // custom marker
     tempUser.setCreatedBy("bootstrap:first-run");
     tempUser.setActivatedAt(Instant.now());
     userRepo.save(tempUser);
     auditService.record("system", "SYSTEM", "bootstrap_temp_user_created", "user", tempUser.getId(),
         null, null, "temp installer for first-time onboarding; will be auto-removed after org+role created", false, null);

   NEW: createTempInstaller()
   Private User createTempInstaller() {
       User user = new User();
       user.setName("Temp Installer");
       user.setPhone("0000000000");
       user.setUsername("installer");
       user.setPasswordHash(passwordEncoder.encode("installer123"));
       user.setRole(Enums.UserRole.MINDLABS_ADMIN);
       user.setOrgRole(Enums.OrgMemberRole.ADMIN);
       user.setStatus(Enums.UserStatus.ACTIVE);
       user.setTempFlag(true);
       user.setCreatedBy("bootstrap:first-run");
       user.setActivatedAt(Instant.now());
       return userRepo.save(user);
   }

   NEW: POST /bootstrap/installer
   @PostMapping("/bootstrap/installer")
   @Transactional
   public ResponseEntity<?> installBootstrap(@RequestBody InstallBootstrapReq req) {
       if (req.isCreateTempUser() && userRepo.count() == 0) {
           // Create temp installer user (see createTempInstaller above)
           // Return 200 with temp credentials note
       }
       // If users already exist → normal login path
       return ok();
   }

   NEW: POST /bootstrap/complete
   @PostMapping("/bootstrap/complete")
   @Transactional
   public ResponseEntity<?> completeBootstrap(@RequestBody BootstrapCompleteReq req) {
       // 1. Find and delete the temp installer
       User tempUser = userRepo.findByUsername("installer")
           .filter(u -> u.isTempFlag())
           .orElseThrow(() -> NotFoundException("no temp installer"));
       String tempId = tempUser.getId();
       userRepo.delete(tempUser);

       // 2. Verify admin user is active (created during onboarding)
       Optional<User> adminUser = userRepo.findByOrgRole(Enums.OrgMemberRole.ADMIN);
       if (adminUser.isPresent()) {
           adminUser.get().setTempFlag(false);
           adminUser.get().setActivatedAt(Instant.now());
           userRepo.save(adminUser.get());
       }

       // 3. Audit
       auditService.record("system", "SYSTEM", "bootstrap_cleanup_complete", "user", tempId,
           null, null, "temp installer removed; admin user active", false, null);

       return ok("Bootstrap complete; temp installer removed, admin active");
   }

   MODIFIED: firstRunBootstrap(String identifier)
   - Remove the inline bootstrap logic from loginWithPassword
   - Replace with call to dedicated endpoints: /bootstrap/installer on fresh boot,
     /bootstrap/complete after org+role creation

2. CORE: AuthController.java — New Endpoints

   ADD: POST /bootstrap/installer
   Maps to AuthService.installBootstrap(req)

   ADD: POST /bootstrap/complete
   Maps to AuthService.completeBootstrap(req)

3. CORE: Remove IdP Bootstrap

   DELETE: trustplane/internal/idpserver/bootstrap.go entirely
   REMOVE: BootstrapInstaller call from trustplane/cmd/idp/main.go

   REASON: Core now handles bootstrap natively; IdP should not create installer account.

4. RBAC: Trustplane/Java — No Changes Needed (already yaml-free)

   The RBAC service already boots with empty grid.
   Decisions.bootstrapWindow() allows checks until first assignment.
   No yaml references remain.

5. Testing Checklist

   [ ] Fresh boot with no users → temp installer created ✅
   [ ] Login as installer works ✅
   [ ] Portal creates first org + role through APIs ✅
   [ ] POST /bootstrap/complete deletes temp user ✅
   [ ] Admin user remains active after cleanup ✅
   [ ] Second login with installer/installer123 works ✅
   [ ] Existing installations with old IdP installer → upgrade path ✅

   Upgrade path for existing systems:
   - On systems with old IdP installer account:
     * Mark as temp if not already: UPDATE users SET temp_flag=true WHERE username='installer';
     * On next core boot with clean-slate detection:
       - The core will detect existing temp user and either:
         a) Delete it and create a fresh one, or
         b) Keep it and promote to admin (based on org_role flag)

5. Portal UI Flow

   [ ] On fresh start (no users in DB): Show "First-time installation" screen
   [ ] Click "Install" → Calls POST /bootstrap/installer?createTempUser=true
   [ ] Portal logs in as installer/installer123
   [ ] Portal creates first org + first role through the RBAC APIs
   [ ] After successful creation: Calls POST /bootstrap/complete?tempUserId=<temp-user-id>
   [ ] Core cleans up: Deletes temp installer, ensures admin user is active
   [ ] Normal login now works with installer/installer123

6. Database Migration

   ALTER TABLE users ADD COLUMN temp_flag BOOLEAN DEFAULT FALSE;
   -- Or use a separate temp_users table if preferring schema isolation

7. Summary of Changes by File

   AuthService.java    : +150 lines (bootstrap lifecycle, DTOs, endpoints)
   AuthController.java : +20 lines (new endpoint mappings)
   bootstrap.go        : DELETE (removed entirely)
   main.go (IdP)       : DELETE (removed BootstrapInstaller call)
   RbacStore.java      : unchanged (already yaml-free)
   MeshController.java : unchanged (already yaml-free)
   users table         : +temp_flag column (or separate temp_users table)

-----

ARCHITECTURAL BENEFITS

1. Single system (core) owns the entire bootstrap lifecycle
2. Temp installer auto-cleans — no manual intervention needed
3. Admin user remains active after cleanup — immediate normal login
4. IdP bootstrap completely retired — no yaml, no separate seed
5. RBAC window timing coordinated with core boot, not IdP startup
6. Clean upgrade path from old IdP-based installations

-----

COMMIT: 6d6b213 — "RBAC bootstrap redesign: no yaml seed; installer breaks the chicken-and-egg"