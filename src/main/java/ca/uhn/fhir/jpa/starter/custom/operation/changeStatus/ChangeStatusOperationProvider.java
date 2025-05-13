package ca.uhn.fhir.jpa.starter.custom.operation.changeStatus;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.DocumentRetrievalService;
import ca.uhn.fhir.jpa.starter.custom.operation.changeStatus.ChangeStatusService;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Provider für die Change-Status-Operation, die die Änderung des Status eines Rechnungsdokuments ermöglicht.
 * Diese Operation ist als FHIR-Operation $change-status auf dem Endpunkt /DocumentReference/{id}/ implementiert.
 * Die Operation ermöglicht es dem Versicherten, den Status einer Rechnung zu ändern.
 */
@Component
public class ChangeStatusOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeStatusOperationProvider.class);
    private static final List<String> VALID_STATUS_CODES = Arrays.asList("offen", "erledigt", "papierkorb");

    @Autowired
    private CustomValidator validator;

    @Autowired
    private AuthorizationService authorizationService;

    private final FhirContext ctx;

    @Autowired
    private DocumentRetrievalService documentRetrievalService;

    @Autowired
    private ChangeStatusService changeStatusService;

    @Autowired
    public ChangeStatusOperationProvider(FhirContext ctx, AuthorizationService authorizationService) {
        this.ctx = ctx;
        this.authorizationService = authorizationService;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    /**
     * Implementierung der $change-status Operation gemäß der Spezifikation.
     * Diese Operation ermöglicht die Änderung des Status eines Rechnungsdokuments durch den Versicherten.
     *
     * @param id                Die ID des Dokuments, dessen Status geändert werden soll
     * @param tag               Der neue Status-Code
     * @param theRequestDetails Request-Details für die Autorisierung
     * @return Parameters mit dem aktualisierten Meta-Element
     */
    @Operation(name = "$change-status", idempotent = false)
    public Parameters changeStatusOperation(
            @IdParam IdType id,
            @OperationParam(name = "tag") String tag,
            RequestDetails theRequestDetails
    ) {
        LOGGER.info("Change-Status-Operation für Dokument mit ID {} aufgerufen, neuer Status: {}", id.getIdPart(), tag);

        // Validiere den Status-Code
        if (tag == null || !VALID_STATUS_CODES.contains(tag)) {
            throw new InvalidRequestException("Der angegebene Status-Code ist ungültig. Gültige Werte sind: offen, erledigt, papierkorb.");
        }

        // 1. Authentifizierung und Autorisierung über den zentralen Service
        AccessToken accessToken = authorizationService.authorizeChangeStatusOperation(theRequestDetails);

        // 2. Lade das Dokument direkt über DocumentRetrievalService
        // ResourceNotFoundException wird von documentRetrievalService geworfen, falls nichts gefunden wird.
        DocumentReference document = documentRetrievalService.findDocument(id.getIdPart());
        
        // 3. Prüfe Zugriff auf DIESES Dokument über den zentralen Service
        authorizationService.validateDocumentAccess(document, accessToken);

        // 4. Delegiere die Statusänderungslogik an den ChangeStatusService
        DocumentReference updatedDocument = changeStatusService.processStatusChange(id, tag, accessToken);
            
        // 5. Erstelle die Antwort gemäß der Spezifikation
        Parameters result = new Parameters();
        result.addParameter().setName("meta").setValue(updatedDocument.getMeta());
            
        LOGGER.info("Change-Status-Operation erfolgreich beendet für Dokument mit ID {}", id.getIdPart());
        return result;
    }
} 