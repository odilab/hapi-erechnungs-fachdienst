package ca.uhn.fhir.jpa.starter.custom.operation.processFlag;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class ProcessFlagInputValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFlagInputValidator.class);

    /**
     * Validiert die Eingabeparameter der $process-flag Operation.
     *
     * @param markierung          Die Art der Markierung als Coding.
     * @param zeitpunkt           Der Zeitpunkt der Markierung.
     * @param details             Optionale Details als Freitext zur Markierung.
     * @param gelesen             Gelesen-Status falls Markierung vom Typ 'gelesen' ist.
     * @param artDerArchivierung  Details zur Art der Archivierung falls Markierung vom Typ 'archiviert' ist.
     * @throws UnprocessableEntityException Wenn die Validierung fehlschlägt.
     */
    public void validateInputParameters(Coding markierung, DateTimeType zeitpunkt, StringType details,
                                        BooleanType gelesen, Coding artDerArchivierung) {
        LOGGER.debug("Validating input parameters for $process-flag operation...");

        if (markierung == null) {
            LOGGER.error("Validation failed: Parameter 'markierung' is missing.");
            throw new UnprocessableEntityException("Parameter 'markierung' ist erforderlich");
        }
        LOGGER.debug("Parameter 'markierung': System={}, Code={}", markierung.getSystem(), markierung.getCode());


        if (zeitpunkt == null || !zeitpunkt.hasValue()) {
            LOGGER.error("Validation failed: Parameter 'zeitpunkt' is missing or empty.");
            throw new UnprocessableEntityException("Parameter 'zeitpunkt' ist erforderlich und darf nicht leer sein");
        }
        LOGGER.debug("Parameter 'zeitpunkt': {}", zeitpunkt.getValueAsString());

        // Spezifische Validierungen je nach Markierungstyp
        String markierungCode = markierung.getCode();
        if ("gelesen".equals(markierungCode)) {
            if (gelesen == null) {
                LOGGER.error("Validation failed: Parameter 'gelesen' is missing for markierung 'gelesen'.");
                throw new UnprocessableEntityException("Parameter 'gelesen' ist für Markierungstyp 'gelesen' erforderlich");
            }
            LOGGER.debug("Parameter 'gelesen': {}", gelesen.getValue());
        } else if ("archiviert".equals(markierungCode)) {
            if (artDerArchivierung == null) {
                LOGGER.error("Validation failed: Parameter 'artDerArchivierung' is missing for markierung 'archiviert'.");
                throw new UnprocessableEntityException("Parameter 'artDerArchivierung' ist für Markierungstyp 'archiviert' erforderlich");
            }
            LOGGER.debug("Parameter 'artDerArchivierung': System={}, Code={}", artDerArchivierung.getSystem(), artDerArchivierung.getCode());
        } else {
            LOGGER.warn("Unbekannter oder nicht unterstützter Markierungscode '{}'. Keine spezifische Parameterprüfung durchgeführt.", markierungCode);
             // Hier könnten weitere bekannte Codes validiert oder eine Exception für unbekannte Codes geworfen werden.
        }

        if (details != null) {
            LOGGER.debug("Parameter 'details': {}", details.getValue());
        } else {
            LOGGER.debug("Parameter 'details' is not provided.");
        }


        LOGGER.debug("Input parameter validation successful.");
    }
} 