# YAML workspace fix — November 2, 2026

Fixed stale API selection after import, added live YAML endpoint previews, loaded selected contracts into the editor, added Save selected contract, and rejected spec records missing a valid OpenAPI document. Malformed YAML and legacy records missing document no longer crash the endpoint preview.

## Apply in IntelliJ

1. Stop the application and back up your project.
2. Copy frontend/src/App.tsx, frontend/src/pages.tsx, frontend/src/workspace.test.tsx, src/main/java/com/apiintel/api/PlatformController.java, and src/test/java/com/apiintel/PlatformIntegrationTest.java from this ZIP into the same paths in your project. Preserve your existing data folder and saved VAULT_KEY.
3. Run Maven clean verify from IntelliJ. Restart with the same environment variables. Hard refresh the browser (Ctrl+Shift+R).
4. Paste or open a contract: endpoints preview immediately. Import contract creates and selects a new record; Save selected contract updates the currently selected record as DRAFT.
5. If an earlier edit already saved a broken record, select it, paste the original valid YAML, and use Save selected contract to repair it. Do not delete your database.

Validation: frontend production build passed. Seven workspace regression tests cover paste, upload, selection changes, import selection, document-preserving save, invalid YAML, and missing document; the existing frontend suite is also included. Backend regression test added but not executed in the repair environment because Maven is unavailable. No live IntelliJ/browser/backend verification was performed.

APPLY_CHANGES.patch at the ZIP root is the original historical patch; it does not include this repair. Use the source files above.
