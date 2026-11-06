import type { Row } from "./api";

export default function RequestDiagnostic({ response }: { response: Row }) {
  const diagnostic = response.diagnostic;
  const status = Number(response.status);
  let message = "";
  if (status === 401) {
    message = diagnostic?.authorization === "none" && !diagnostic?.credentialHeader
      ? "The target API returned 401. No Authorization header or recognized credential header was sent. Check the saved environment and the API's required auth method."
      : "The target API returned 401. Check the credential, its expiry, and whether it is valid for this server.";
  } else if (status === 403) {
    message = "The target API returned 403. Check token scopes, account permissions, and the target's access policy.";
  } else if (status === 407) {
    message = "A proxy requires authentication. Check the server's network configuration.";
  } else if (status === 0 || response.error) {
    message = response.error || "The outbound request failed before an HTTP response was received.";
  }
  if (!message && !response.requestId) return null;
  return <div className="request-diagnostic" role={message ? "status" : undefined}>
    {message && <p>{message}</p>}
    {diagnostic?.challenge && diagnostic.challenge !== "none" && <p>Upstream authentication challenge <code>{diagnostic.challenge}</code></p>}
    {diagnostic?.challengeError && diagnostic.challengeError !== "none" && <p>Upstream auth error <code>{diagnostic.challengeError}</code></p>}
    {diagnostic?.cookieDropped && <p>The guarded executor does not forward Cookie headers.</p>}
    {response.requestId && <small>Request ID <code>{response.requestId}</code></small>}
  </div>;
}
