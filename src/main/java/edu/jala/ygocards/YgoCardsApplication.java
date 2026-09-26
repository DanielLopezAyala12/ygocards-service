package edu.jala.ygocards;

import edu.jala.ygocards.admin.AdminRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point for both process types this application has.
 *
 * <p>Every value the service needs at run time is read from the environment through
 * {@code application.properties}, never from a file the process writes or from a value compiled
 * into the code. See {@code edu.jala.ygocards.config} for the typed holders.
 *
 * <p>Two ways in, one artefact. Started with no arguments it is the long-running web process.
 * Started with {@code --admin=<task>} it runs that task and exits with a meaningful code, which
 * is factor XII: the same code, the same configuration and the same release, as a one-off
 * process. The web server is not started for an admin run, because a one-off task that bound a
 * port would collide with the instance already serving traffic.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class YgoCardsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(YgoCardsApplication.class);
        boolean adminRun = AdminRunner.isAdminInvocation(args);

        if (adminRun) {
            application.setWebApplicationType(WebApplicationType.NONE);
        }

        ConfigurableApplicationContext context = application.run(args);

        if (adminRun) {
            // Only for an admin run. Exiting here in server mode would stop the web process the
            // moment it finished starting.
            System.exit(SpringApplication.exit(context));
        }
    }
}
