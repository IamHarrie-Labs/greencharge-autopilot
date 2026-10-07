package eu.enact.greencharge;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Opens the GreenCharge web page in the default browser as soon as the app is
 * ready, so "Run" in the IDE immediately shows the frontend.
 *
 * Disable in headless environments (containers, CI) with app.open-browser=false.
 */
@Component
class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private final Environment env;

    BrowserLauncher(Environment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    void openBrowser() {
        if (!env.getProperty("app.open-browser", Boolean.class, true)) {
            return;
        }
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String url = "http://localhost:" + port + "/";
        try {
            if (!GraphicsEnvironment.isHeadless()
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                log.info("GreenCharge UI opened at {}", url);
            } else {
                log.info("GreenCharge UI available at {}", url);
            }
        } catch (Exception e) {
            log.warn("Could not open browser automatically. Open {} manually.", url);
        }
    }
}
