package ca.uhn.fhir.jpa.starter.custom.operation.processFlag;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessTokenService;
//import ca.uhn.fhir.jpa.starter.custom.interceptor.audit.AuditService;
// Importiere die neuen Services
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.DocumentRetrievalService;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.context.FhirContext;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Provider für die Process-Flag-Operation, die das Markieren von Rechnungsdokumenten ermöglicht.
 * Diese Operation ist als FHIR-Operation $process-flag auf dem Endpunkt /DocumentReference/{id}/ implementiert.
 * Der Provider orchestriert die Aufrufe an spezialisierte Services für Validierung, Autorisierung und Logik.
 */
@Component
public class ProcessFlagOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessFlagOperationProvider.class);

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private AccessTokenService accessTokenService; // Behalten wir vorerst, falls direkt benötigt

    // Injiziere die neuen Services
    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private DocumentRetrievalService documentRetrievalService;

    // @Autowired
    // private AuditService auditService;

    // Injiziere die neuen Services und den Validator
    private final ProcessFlagService processFlagService;
    private final FhirContext ctx;

    @Autowired
    public ProcessFlagOperationProvider(FhirContext ctx,
                                      AuthorizationService authorizationService,
                                      ProcessFlagService processFlagService,
                                      DocumentRetrievalService documentRetrievalService) {
        this.ctx = ctx;
        this.authorizationService = authorizationService;
        this.processFlagService = processFlagService;
        this.documentRetrievalService = documentRetrievalService;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    /**
     * Implementierung der $process-flag Operation gemäß der Spezifikation.
     * Diese Operation ermöglicht das Markieren von Rechnungsdokumenten mit verschiedenen Flags.
     *
     * @param id                  Die ID des zu markierenden Dokuments (repräsentiert durch den ergToken).
     * @param markierung          Die Art der Markierung als Coding.
     * @param zeitpunkt           Der Zeitpunkt der Markierung.
     * @param details             Optionale Details als Freitext zur Markierung.
     * @param gelesen             Gelesen-Status falls Markierung vom Typ 'gelesen' ist.
     * @param artDerArchivierung  Details zur Art der Archivierung falls Markierung vom Typ 'archiviert' ist.
     * @param theRequestDetails   Request-Details für die Autorisierung.
     * @return Parameters mit dem aktualisierten Meta-Element des Dokuments.
     */
    @Operation(name = "$process-flag", idempotent = false)
    public Parameters processFlagOperation(
            @IdParam IdType id,
            @OperationParam(name = "markierung") Coding markierung,
            @OperationParam(name = "zeitpunkt") DateTimeType zeitpunkt,
            @OperationParam(name = "details") StringType details,
            @OperationParam(name = "gelesen") BooleanType gelesen,
            @OperationParam(name = "artDerArchivierung") Coding artDerArchivierung,
            RequestDetails theRequestDetails
    ) {
        LOGGER.info("Process-Flag-Operation für Dokument mit Token (ID) {} aufgerufen", id != null ? id.getIdPart() : "null");
        if (id == null || !id.hasIdPart()) {
            throw new UnprocessableEntityException("Die ID des Dokuments (ergToken) darf nicht fehlen.");
        }
        String documentToken = id.getIdPart();

        // 1. Validiere die Eingabeparameter
        validateInputParameters(markierung, zeitpunkt, details, gelesen, artDerArchivierung);

        // 2. Berechtigungsprüfung
        AccessToken accessToken = authorizationService.validateAndExtractAccessToken(theRequestDetails);
        authorizationService.authorizeAccessBasedOnContext(accessToken, theRequestDetails); // Stellt sicher, dass Nutzer und Scope passen

        // 3. Suche das Dokument anhand des Tokens (ID)
        DocumentReference document = processFlagService.findDocumentByToken(documentToken);
        if (document == null) {
            throw new ResourceNotFoundException("Kein Dokument mit Token (ID) " + documentToken + " gefunden");
        }

        // 4. Prüfe, ob der Nutzer berechtigt ist, auf DIESES Dokument zuzugreifen
        authorizationService.validateDocumentAccess(document, accessToken);

        // 5. Füge die Markierung hinzu und speichere das Dokument
        DocumentReference savedDocument = processFlagService.applyFlagToDocument(
                document, markierung, zeitpunkt, details, gelesen, artDerArchivierung
        );

        // 6. Protokolliere die Markierung (falls AuditService aktiviert wird)
        // logProcessFlagOperation(savedDocument, accessToken, markierung);

        // 7. Erstelle die Antwort
        Parameters result = new Parameters();
        if (savedDocument != null && savedDocument.hasMeta()) {
             result.addParameter().setName("meta").setValue(savedDocument.getMeta());
        } else {
            LOGGER.warn("Das gespeicherte Dokument hat keine Meta-Informationen. Antwort-Parameter 'meta' wird leer sein.");
            // Evtl. leeres Meta hinzufügen oder Fehler werfen?
             result.addParameter().setName("meta").setValue(new Meta());
        }

        LOGGER.info("Process-Flag-Operation erfolgreich beendet für Dokument mit Token (ID) {}", documentToken);
        return result;
    }

    /**
     * Private Methode zur Validierung der Eingabeparameter der $process-flag Operation.
     *
     * @param markierung          Die Art der Markierung als Coding.
     * @param zeitpunkt           Der Zeitpunkt der Markierung.
     * @param details             Optionale Details als Freitext zur Markierung.
     * @param gelesen             Gelesen-Status falls Markierung vom Typ 'gelesen' ist.
     * @param artDerArchivierung  Details zur Art der Archivierung falls Markierung vom Typ 'archiviert' ist.
     * @throws UnprocessableEntityException Wenn die Validierung fehlschlägt.
     */
    private void validateInputParameters(Coding markierung, DateTimeType zeitpunkt, StringType details,
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

    // Die Methoden validateInputParameters, addMarkierungToDocument wurden in separate Services ausgelagert.
    // Die Methoden zur Autorisierung und Dokumentsuche werden von den entsprechenden Services bereitgestellt.

    // Die Audit-Logik bleibt auskommentiert, könnte aber hier reaktiviert werden.
    /*
    private void logProcessFlagOperation(DocumentReference document, AccessToken accessToken, Coding markierung) {
        ...
    }
    */
} 