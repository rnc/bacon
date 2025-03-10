package org.jboss.pnc.bacon.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.validation.ConstraintViolation;

public interface Validate {

    /**
     * WARNING: If validation fails, the method may stop the application
     */
    void validate();

    /**
     * WARNING: If validation fails, the method will stop the application
     *
     * Checks if url is null or empty and has the proper format
     */
    static void validateUrl(String url, String kind) {

        if (url == null || url.isEmpty()) {
            fail(kind + " Url is not specified in the config file!");
        }

        try {
            URI uri = new URI(url);

            checkIfNull(uri.getScheme(), "You need to specify the protocol of the " + kind + " URL in the config file");
            checkIfNull(uri.getHost(), "You need to specify the host of the " + kind + " URL in the config file");

        } catch (URISyntaxException e) {
            fail("Could not parse the " + kind + " Url at all! " + e.getMessage());
        }
    }

    static void checkIfNull(Object object, String reason) {
        if (object == null) {
            throw new ConfigMissingException(reason);
        }
    }

    static void fail(String reason) {
        checkIfNull(null, reason);
    }

    static <T> String prettifyConstraintViolation(Set<ConstraintViolation<T>> violations) {

        List<String> errorMsgs = new ArrayList<>();

        for (ConstraintViolation violation : violations) {
            errorMsgs.add("Field: " + violation.getPropertyPath() + " " + violation.getMessage());
        }
        return String.join("\n", errorMsgs);

    }

    class ConfigMissingException extends RuntimeException {
        public ConfigMissingException(String reason) {
            super("ConfigMissingException: " + reason);
        }
    }

}
