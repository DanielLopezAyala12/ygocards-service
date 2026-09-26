package edu.jala.ygocards.admin;

import edu.jala.ygocards.config.UpstreamProperties;
import edu.jala.ygocards.upstream.UpstreamProbe;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * One-off administrative tasks, run from the same artefact as the long-running process.
 *
 * <p>Factor XII asks for admin work to run as a process in the same environment and from the same
 * release as everything else, rather than from a script that drifts away from the application it
 * maintains. That is what this is: the same jar, the same configuration, the same code that
 * serves traffic, started with an argument and exiting when the work is done.
 *
 * <pre>
 *   java -jar target/ygocards-service.jar --admin=upstream-check
 *   docker run --rm --env-file .env ygocards-service --admin=upstream-check
 * </pre>
 *
 * <p>No web server is started for an admin run. A one-off task that binds a port would collide
 * with the instance already serving traffic, and would not be one-off in any useful sense.
 *
 * <p>The exit code is the point of the exercise. A task whose result has to be read out of prose
 * cannot be used by anything, so the outcome is reported in the one channel every scheduler,
 * pipeline and shell already understands.
 */
@Component
public class AdminRunner implements ApplicationRunner, ExitCodeGenerator {

    /** Everything went as expected. */
    public static final int EXIT_OK = 0;
    /** The task ran and reported a problem. */
    public static final int EXIT_FAILED = 2;
    /** The requested task is not one this runner knows. */
    public static final int EXIT_UNKNOWN_TASK = 3;

    private static final String OPTION = "admin";
    private static final String UPSTREAM_CHECK = "upstream-check";

    private final UpstreamProbe probe;
    private final UpstreamProperties properties;
    private int exitCode = EXIT_OK;

    public AdminRunner(UpstreamProbe probe, UpstreamProperties properties) {
        this.probe = probe;
        this.properties = properties;
    }

    /** True when the command line asks for an admin task rather than for the server. */
    public static boolean isAdminInvocation(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--" + OPTION + "=")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }

        List<String> values = args.getOptionValues(OPTION);
        String task = (values == null || values.isEmpty()) ? "" : values.get(0);

        if (UPSTREAM_CHECK.equals(task)) {
            exitCode = upstreamCheck();
        } else {
            System.out.println("Unknown admin task: '" + task + "'");
            System.out.println("Known tasks: " + UPSTREAM_CHECK);
            exitCode = EXIT_UNKNOWN_TASK;
        }
    }

    private int upstreamCheck() {
        UpstreamProbe.Outcome outcome = probe.current();
        UpstreamProbe.Result result = outcome.result();

        System.out.println("ygocards-service admin task: " + UPSTREAM_CHECK);
        System.out.println("  upstream base url  : " + properties.baseUrl());
        System.out.println("  probe path         : " + properties.probePath());
        System.out.println("  reachable          : " + result.reachable());
        System.out.println("  latency            : " + result.latencyMs() + " ms");
        System.out.println("  detail             : " + result.detail());
        System.out.println("  database version   : " + valueOrDash(result.databaseVersion()));
        System.out.println("  database updated   : " + valueOrDash(result.databaseUpdatedAt()));
        System.out.println("  checked at         : " + result.checkedAt());

        if (result.reachable()) {
            System.out.println("RESULT: upstream reachable");
            return EXIT_OK;
        }
        System.out.println("RESULT: upstream NOT reachable");
        return EXIT_FAILED;
    }

    private static String valueOrDash(String value) {
        return value == null ? "not reported" : value;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
