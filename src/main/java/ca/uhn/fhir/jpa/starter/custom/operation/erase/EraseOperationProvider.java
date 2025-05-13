package ca.uhn.fhir.jpa.starter.custom.operation.erase;

import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.DocumentRetrievalService;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class EraseOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(EraseOperationProvider.class);

    private final AuthorizationService authorizationService;
    private final DocumentRetrievalService documentRetrievalService;
    private final EraseService eraseService;

    @Autowired
    public EraseOperationProvider(AuthorizationService authorizationService,
                                  DocumentRetrievalService documentRetrievalService,
                                  EraseService eraseService) {
        this.authorizationService = authorizationService;
        this.documentRetrievalService = documentRetrievalService;
        this.eraseService = eraseService;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    @Operation(name = "$erase", idempotent = false, type = DocumentReference.class)
    public OperationOutcome eraseOperation(
            @IdParam IdType theId,
            RequestDetails theRequestDetails
    ) {
        LOGGER.info("Erase-Operation für DocumentReference mit ID {} aufgerufen", theId != null ? theId.getIdPart() : "null");
        if (theId == null || !theId.hasIdPart()) {
            throw new InvalidRequestException("Die ID der DocumentReference darf nicht fehlen.");
        }
        String documentId = theId.getIdPart();

        // 1. Berechtigungsprüfung
        AccessToken accessToken = authorizationService.validateAndExtractAccessToken(theRequestDetails);
        authorizationService.authorizeAccessBasedOnContext(accessToken, theRequestDetails);

        // 2. Lade die DocumentReference
        DocumentReference documentReference = documentRetrievalService.findDocument(documentId); 

        // 3. Prüfe, ob der Nutzer berechtigt ist, DIESE DocumentReference zu löschen (KVNR-Abgleich)
        authorizationService.validateDocumentAccess(documentReference, accessToken); 

        // 4. Führe die eigentliche Löschlogik im EraseService aus
        eraseService.eraseDocumentReferenceAndAssociations(documentReference, accessToken);

        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                .setDiagnostics("Rechnungsvorgang mit ID '" + documentId + "' erfolgreich gelöscht.");

        LOGGER.info("Erase-Operation für DocumentReference mit ID {} erfolgreich beendet.", documentId);
        return outcome;
    }
}
