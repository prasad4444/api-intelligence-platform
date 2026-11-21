package com.apiintel.studio;

/**
 * JavaScript injected into the studio browser page via context.addInitScript.
 * It reports click/change/submit metadata back through the
 * {@code __studioAction} binding. Sensitive fields (password inputs, OTP /
 * recovery / token-named fields) never transmit their value — only that an
 * interaction happened. Normal text fields report valueLength and whether the
 * value equals the user's demo prompt marker.
 */
public final class RecorderScript {

  private RecorderScript() {}

  public static final String BINDING = "__studioAction";

  public static String script(String promptMarker) {
    String marker = promptMarker == null ? "" : promptMarker;
    return """
      (() => {
        const MARKER = %s;
        const SENSITIVE = /otp|code|pin|recovery|secret|token|password|one-time/i;
        function sensitive(el) {
          if (!el) return false;
          if ((el.type || '').toLowerCase() === 'password') return true;
          const ac = (el.getAttribute && (el.getAttribute('autocomplete') || '')).toLowerCase();
          if (ac === 'one-time-code' || ac === 'current-password' || ac === 'new-password') return true;
          const name = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute && (el.getAttribute('aria-label') || ''))).toLowerCase();
          return SENSITIVE.test(name);
        }
        function label(el) {
          if (!el || !el.getAttribute) return '';
          const testid = el.getAttribute('data-testid') || el.getAttribute('data-test-id');
          if (testid) return '[data-testid="' + testid + '"]';
          const role = el.getAttribute('role');
          const name = el.getAttribute('aria-label') || (el.innerText || '').slice(0, 40);
          if (role && name) return role + '[name="' + name.trim() + '"]';
          const labelled = el.id && document.querySelector('label[for="' + el.id + '"]');
          if (labelled) return '#' + el.id;
          if (el.id) return '#' + el.id;
          if (el.name) return el.tagName.toLowerCase() + '[name="' + el.name + '"]';
          return el.tagName ? el.tagName.toLowerCase() : 'element';
        }
        function send(action) {
          try { window[BINDING](JSON.stringify(action)); } catch (e) {}
        }
        document.addEventListener('click', e => {
          const el = e.target.closest('button,a,[role="button"],input[type="submit"]') || e.target;
          send({type:'click', locator:label(el), description:'Click ' + (el.tagName||'element').toLowerCase() +
            ' "' + ((el.innerText||el.value||el.getAttribute&&el.getAttribute('aria-label')||'').slice(0,40)) + '"'});
        }, true);
        document.addEventListener('change', e => {
          const el = e.target;
          if (sensitive(el)) { send({type:'input', locator:label(el), description:'Enter sensitive value', sensitive:true}); return; }
          const v = el.value == null ? '' : String(el.value);
          send({type:'input', locator:label(el), description:'Type into ' + label(el),
            valueLength:v.length, isMarker: MARKER ? v === MARKER : false});
        }, true);
        document.addEventListener('submit', e => {
          send({type:'submit', locator:label(e.target), description:'Submit form ' + label(e.target)});
        }, true);
      })();
      """.formatted(marker.isEmpty() ? "null" : jsonQuote(marker)).replace("BINDING", "'" + BINDING + "'");
  }

  private static String jsonQuote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
