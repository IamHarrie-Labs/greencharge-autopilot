package eu.enact.greencharge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GreenCharge, a tiny carbon-aware EV charger routing service.
 *
 * The whole app is deliberately small: three endpoints + one static page.
 * The ENACT hackathon challenge is the SDK workflow around it, not the code.
 */
@SpringBootApplication
public class GreenChargeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GreenChargeApplication.class, args);
    }
}
