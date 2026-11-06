# One contract across API Workspace, Request Studio and Test Library

## Upload or edit

Use Upload & prepare in API Workspace to upload a complete OpenAPI 3 YAML/JSON file. The application saves the source text and parsed document, then creates its linked collection, built-in test cases, suites and plan in the same transaction. The collection and cases are available when you switch modules; no separate generation click is required. Nothing is executed against the API, and no document is sent to an AI provider automatically.

Pasted/typed YAML updates the endpoint preview immediately. Import contract saves it as a new API; Save selected contract updates the selected API. Both prepare the linked artifacts. Original uploaded/saved text, including comments and formatting, is retained for complete-file comparison. Older records without source text fall back to a YAML rendering of their stored JSON document.

Artifacts are keyed to the contract content. Repeating generation for unchanged content reuses them and preserves edits. Changed content creates a new linked collection/test package and retains the earlier drafts. Test Library filters generated cases, suites and plans to the selected contract hash; execution history remains available by API. Unsupported/unresolved external references must be bundled into the contract first. Review generated test payloads and expected results before running them.

## Endpoint browser

Search and click a method/path. Detail sections show overview, inherited and operation parameters, request headers, body content types, schemas/examples, every response code (including errors and default), response headers, and authentication requirements. Local component references are resolved; circular and external references show explanatory placeholders. Generated examples are labeled as illustrations, not observed responses.

## Workflow buttons

Actions use the saved selected API and its expected revision. Unsaved YAML must be saved/imported before review or collection actions.

- Edit as draft persists DRAFT status. Editing invalidates prior approval metadata.
- Request review moves a draft/rejected contract to IN_REVIEW.
- Approve requires IN_REVIEW, a reviewer/admin role, and no ERROR lint findings.
- Create collection reuses the prepared collection for unchanged content and offers Open Request Studio.

A stale action returns a conflict; reload the API before retrying. Auto-generated test drafts are not approval or execution evidence.

## Complete-file comparison

Current content comes from the editor, with its version read from the YAML. Choose a saved contract, load this API's saved revisions, paste the previous file, or upload it. Compare complete files opens a side-by-side review with line numbers, red removals, green additions, a changes-only toggle, endpoint changes, parsed document changes and patch export. Metadata, schemas, parameters, responses and security are compared in addition to version labels. Comment/formatting changes appear in the file view even when parsed data is identical.

## Collection management

Collection menus include rename, duplicate, delete, add request/folder, and v2.1 export. Folder/request menus include rename, duplicate, delete and move within the collection. Deletion requires confirmation. Deleting a collection requires an administrator. Open deleted requests stay as unsaved tabs. Stable item IDs rebind surviving tabs after folder moves/deletions, so saving does not overwrite the request now occupying an old array index. Documented response examples are retained during request save and sanitized v2.1 export/import.

This does not claim complete Postman parity. Cross-collection moves, arbitrary Postman JavaScript, binary/multipart file handling, cloud monitors and real-time collaboration remain outside the implemented workbench. External AI generation remains an explicit, separately configured action.

Saving an open request into a different collection creates a fresh request identity and retains its documented response examples. For a generated operation with one explicit success status, its initial Request Studio status assertion uses that documented code (for example 201 rather than a blanket 200); operations with multiple or unspecified success codes do not receive an invented single-status assertion.

## Revision explorer and guarded execution

API Workspace opens in Explore. Source keeps the original text, Changes opens the full comparison and revision evidence, and Governance shows contract workflow actions. Split source opens the editor beside Explore. Load revision history to inspect an older snapshot. Older revisions are read-only and execution uses the latest saved revision. A revision change resets request inputs and response evidence while preserving the selected operation when it still exists.

Try it out uses the selected operation and server from the saved contract. Spring Boot builds the request and resolves credential references from a saved Request Studio environment scoped to the destination host. Configure secret variables there before selecting a security alternative. Bearer, Basic, and header/query API keys are supported, including pre-issued OAuth/OIDC bearer tokens. Supported requests include scalar path and header parameters, common query array styles and flat deepObject values, and JSON, XML, text and form-urlencoded bodies. Unsupported encodings or cookie requirements return a validation error before network execution.

Changes shows linked collections, case coverage and recorded execution results for the selected revision. The exported review summary includes metadata and lint findings without request or response payloads. Test Library can compare two saved runs of the same case.
