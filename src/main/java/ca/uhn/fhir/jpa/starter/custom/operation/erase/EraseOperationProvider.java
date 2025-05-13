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
import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Identifier;
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
    private final AuditService auditService;

    @Autowired
    public EraseOperationProvider(AuthorizationService authorizationService,
                                  DocumentRetrievalService documentRetrievalService,
                                  EraseService eraseService,
                                  AuditService auditService) {
        this.authorizationService = authorizationService;
        this.documentRetrievalService = documentRetrievalService;
        this.eraseService = eraseService;
        this.auditService = auditService;
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

        // Speichere die ID und Subject Referenz für das Audit-Log, bevor die documentReference gelöscht wird
        String erasedDocumentIdString = documentReference.getIdElement().toVersionless().getValue(); // z.B. "DocumentReference/xyz"
        IdType parsedErasedId = new IdType(erasedDocumentIdString); // Parsen, um Teile wie Ressourcentyp und ID zu bekommen

        Reference patientRefForAudit = null;
        if (documentReference.getSubject() != null && documentReference.getSubject().getReferenceElement().getResourceType().equals("Patient")) {
            patientRefForAudit = documentReference.getSubject().copy(); // Kopieren, da Original bald weg sein könnte
        }

        // 4. Führe die eigentliche Löschlogik im EraseService aus
        eraseService.eraseDocumentReferenceAndAssociations(documentReference, accessToken);

        // 5. Audit-Log Eintrag erstellen
        AuditEvent createdAuditEvent = null;
        try {
            String kvnr = accessToken.getKvnr().orElse(accessToken.getIdNumber());

            Reference whatForAuditEvent = new Reference();
            // Setze den Identifier, um die gelöschte Ressource eindeutig zu kennzeichnen.
            // Das System "urn:ietf:rfc:3986" ist ein allgemeiner URN-Namespace.
            // Der Wert ist die vollständige ID der gelöschten Ressource (z.B. "DocumentReference/xyz").
            whatForAuditEvent.setIdentifier(new Identifier()
                .setSystem("urn:ietf:rfc:3986") // Oder ein spezifischeres System, falls definiert
                .setValue(erasedDocumentIdString));
            whatForAuditEvent.setDisplay("Gelöschte Ressource: " + erasedDocumentIdString);
            // Die .reference Komponente wird bewusst nicht gesetzt oder auf null gelassen,
            // da die Ressource nicht mehr existiert und dies Validierungsfehler vermeiden kann.

            createdAuditEvent = auditService.createRestAuditEvent(
                AuditEvent.AuditEventAction.D, // D für Delete
                "erase", // Korrekter Subtype-Code
                AuditEvent.AuditEventOutcome._0, // Erfolg
                whatForAuditEvent, // Die modifizierte Referenz, die primär auf Identifier basiert
                parsedErasedId.getResourceType() + " Erase", // entity.name, z.B. "DocumentReference Erase"
                erasedDocumentIdString, // entityWhatDisplay (kann auch der Display-Text von whatForAuditEvent sein)
                "Ressource vom Typ '" + parsedErasedId.getResourceType() + "' mit ID '" + parsedErasedId.getIdPart() + "' gelöscht durch Versicherten.", // AuditEvent.entity.description
                accessToken.getIdNumber(), // actorName
                kvnr, // actorId (KVNR des Versicherten)
                patientRefForAudit // patientReference für Versicherter-Slice
            );

            if (createdAuditEvent != null && createdAuditEvent.hasId()) {
                LOGGER.info("EraseOperationProvider: AuditEvent für Erase-Operation erfolgreich erstellt und gespeichert mit ID: {}", createdAuditEvent.getIdElement().toUnqualifiedVersionless().getValue());
            } else if (createdAuditEvent != null) {
                LOGGER.warn("EraseOperationProvider: AuditEvent für Erase-Operation erstellt, aber es hat keine ID (möglicherweise nicht gespeichert).");
            } else {
                LOGGER.warn("EraseOperationProvider: AuditEvent für Erase-Operation konnte nicht erstellt werden (auditService.createRestAuditEvent gab null zurück).");
            }
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen des AuditEvents für EraseOperation: {}", e.getMessage(), e);
            // Die Hauptoperation sollte hierdurch nicht fehlschlagen
        }

        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                .setDiagnostics("Rechnungsvorgang mit ID '" + documentId + "' erfolgreich gelöscht.");

        LOGGER.info("Erase-Operation für DocumentReference mit ID {} erfolgreich beendet.", documentId);
        return outcome;
    }
}
