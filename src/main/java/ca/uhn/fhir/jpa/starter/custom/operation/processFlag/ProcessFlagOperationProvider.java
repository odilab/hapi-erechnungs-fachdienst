package ca.uhn.fhir.jpa.starter.custom.operation.processFlag;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessTokenService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
//import ca.uhn.fhir.jpa.starter.custom.interceptor.audit.AuditService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
// Importiere die neuen Services
import ca.uhn.fhir.jpa.starter.custom.operation.retrieve.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.retrieve.DocumentRetrievalService;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.context.FhirContext;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

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
    private CustomValidator validator;

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
    private final ProcessFlagInputValidator inputValidator;
    private final ProcessFlagService processFlagService;
    private final FhirContext ctx;

    @Autowired
    public ProcessFlagOperationProvider(FhirContext ctx,
                                      AuthorizationService authorizationService,
                                      DocumentRetrievalService documentRetrievalService,
                                      ProcessFlagInputValidator inputValidator,
                                      ProcessFlagService processFlagService) {
        this.ctx = ctx;
        this.authorizationService = authorizationService;
        this.documentRetrievalService = documentRetrievalService;
        this.inputValidator = inputValidator;
        this.processFlagService = processFlagService;
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
        inputValidator.validateInputParameters(markierung, zeitpunkt, details, gelesen, artDerArchivierung);

        // 2. Berechtigungsprüfung
        AccessToken accessToken = authorizationService.validateAndExtractAccessToken(theRequestDetails);
        authorizationService.validateAuthorization(accessToken, theRequestDetails); // Stellt sicher, dass Nutzer und Scope passen

        // 3. Suche das Dokument anhand des Tokens (ID)
        DocumentReference document = documentRetrievalService.findDocumentByToken(documentToken);
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

    // Die Methoden validateInputParameters, addMarkierungToDocument wurden in separate Services ausgelagert.
    // Die Methoden zur Autorisierung und Dokumentsuche werden von den entsprechenden Services bereitgestellt.

    // Die Audit-Logik bleibt auskommentiert, könnte aber hier reaktiviert werden.
    /*
    private void logProcessFlagOperation(DocumentReference document, AccessToken accessToken, Coding markierung) {
        ...
    }
    */
} 