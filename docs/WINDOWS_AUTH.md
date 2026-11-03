# Optional Windows authentication per integration

Every connector can independently choose NONE, Bearer, Basic, API key, OAuth2 client credentials, or WINDOWS. Choosing Windows does not change platform login or other connectors. Existing connections retain their current authentication type.

WINDOWS supports HTTP Negotiate and NTLM through an installed Windows curl.exe build with SSPI. Authentication runs on the Spring Boot host. In current-account mode, that is the Windows account running IntelliJ/the JAR, not the account of a remote browser user. Native Windows policy and destination permissions still apply; an HTML login page or Microsoft browser SSO is not an HTTP Negotiate/NTLM endpoint.

## IntelliJ configuration

1. On the Windows computer running Spring Boot, run `curl.exe --version`. Its feature list must contain SSPI; NTLM mode also requires NTLM. Specify the system curl.exe rather than a Git Bash curl build that lacks SSPI.
2. Add these environment variables to the existing IntelliJ run configuration, using your real approved hostnames:

   ```text
   INTEGRATION_HOSTS=api.github.com,tool.company.example
   WINDOWS_AUTH_HOSTS=tool.company.example
   WINDOWS_CURL=C:\Windows\System32\curl.exe
   ```

   Keep the existing ADMIN_PASSWORD and VAULT_KEY. Both host lists use exact hostnames, without schemes, paths or wildcards.
3. Restart the application. Open Integration Hub → Manage connection for the chosen tool.
4. Set its HTTPS API base URL and Authentication to WINDOWS. Choose NEGOTIATE (the usual Windows/Kerberos negotiation) or NTLM according to the service.
5. Choose identity:
   - Current account: no password or vault reference is needed. The connector uses the app process account's SSPI credentials.
   - Configured account: enter `DOMAIN\username` or a UPN in Windows username, choose a secret reference, and enter only the password in Windows password. The password is encrypted in the existing vault.
6. Save the connection. Reopen it and use Check saved Windows configuration to verify local SSPI availability. This check does not contact or authenticate the remote service. Use the connector read console with a documented read-only API path to check actual access.

## Behavior and boundaries

- Only exact WINDOWS_AUTH_HOSTS targets receive native Windows credentials. HTTPS/certificate verification remains enabled. There is no silent fallback to Basic or another account.
- Credentials are passed to curl over stdin, never command-line arguments or temporary credential files. curl runs directly through ProcessBuilder; no Bash, PowerShell or cmd shell is invoked. User curl configuration and proxies are disabled for this transport.
- DNS results are validated and pinned. The deployment's exact Windows host allowlist permits private RFC1918/ULA addresses for internal tools. Loopback, link-local/metadata, multicast and unspecified addresses remain blocked. The generic non-Windows transport keeps its existing network rules.
- Redirects are not followed, HTTP(S) is limited to HTTPS, request timeouts are bounded, and responses are limited to 2 MB. Configuration values are escaped before entering curl's stdin config.
- Reads and approved connector actions use the same authentication choice. AI JSON requests can use it when their API supports Windows authentication. SSE/streaming through the Windows transport is not implemented and returns an explicit error; use a supported JSON API or approved gateway instead.
- This is not inbound Windows desktop login, browser SAML/OIDC SSO, or delegation of each remote user's Windows identity. Those need separate identity-provider and deployment work.

Live domain authentication must be verified on the user's domain-connected Windows machine. Automated tests use a simulated SSPI runtime and DNS results; they do not establish access to any Citi, Friday, Jira, GitHub Enterprise or Microsoft tenant.

References: https://curl.se/docs/manpage.html (Negotiate, NTLM, user, config, disable, resolve).

## Verification

60 backend tests passed, including 13 Windows transport tests with simulated SSPI/DNS, credential/config escaping, host restrictions, response handling and native-process failure behavior. Maven clean verify passed with 87.57% backend line coverage. The 39-test frontend suite and production UI build passed. Eight existing browser regression checks passed; the Windows configuration browser check passed after adding explicit accessible field names. No live Windows domain was available for authentication testing.
