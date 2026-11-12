package io.github.returnaction.fitness.gateway.readiness;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class ManagementPort {

    private final Environment environment;

    public ManagementPort(Environment environment) {
        Integer apiPort = environment.getRequiredProperty("server.port", Integer.class);
        Integer managementPort = environment.getRequiredProperty("management.server.port", Integer.class);
        if (managementPort < 0 || (apiPort != 0 && apiPort.equals(managementPort))) {
            throw new IllegalArgumentException("Management requires a separate enabled port");
        }
        this.environment = environment;
    }

    public boolean matches(HttpServletRequest request) {
        Integer management = environment.getProperty("local.management.port", Integer.class);
        Integer application = environment.getProperty("local.server.port", Integer.class);
        return management != null && management > 0 && !management.equals(application)
                && request.getLocalPort() == management;
    }
}
