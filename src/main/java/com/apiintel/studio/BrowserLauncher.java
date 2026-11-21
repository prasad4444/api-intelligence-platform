package com.apiintel.studio;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Abstraction over launching the controlled Chromium window used by
 * Connection Studio. Production uses {@link PlaywrightBrowserLauncher};
 * tests inject a fake so studio flows run without a browser.
 */
public interface BrowserLauncher {

  /**
   * @param connection runtime connection (profile dir derives from ownerId/connectionId)
   * @param headless   false for interactive sign-in sessions
   * @param onUrl      navigation callback (current URL changes)
   * @param onCapture  captured xhr/fetch request, same shape as discovery captures
   * @param onAction   recorded user action from the injected recorder script
   * @param cancelled  cooperative cancellation
   */
  BrowserHandle open(OpenRequest request) throws Exception;

  record OpenRequest(
    ObjectNode connection,
    boolean headless,
    Consumer<String> onUrl,
    Consumer<ObjectNode> onCapture,
    Consumer<ObjectNode> onAction,
    BooleanSupplier cancelled
  ) {}

  interface BrowserHandle extends AutoCloseable {
    String currentUrl();
    Object evaluate(String script);
    void installRecorder(String initScript);
    String storageState();
    String identity();
    @Override void close();
  }
}
