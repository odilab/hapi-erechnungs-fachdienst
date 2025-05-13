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
    private AuditService auditService;

    @Autowired
    public ChangeStatusOperationProvider(FhirContext ctx, AuthorizationService authorizationService, DocumentRetrievalService documentRetrievalService, ChangeStatusService changeStatusService, AuditService auditService) {
        this.ctx = ctx;
        this.authorizationService = authorizationService;
        this.documentRetrievalService = documentRetrievalService;
        this.changeStatusService = changeStatusService;
        this.auditService = auditService;
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
        
        // Ermittle den alten Status für das Audit-Log, BEVOR er geändert wird.
        // Annahme: Der Status ist in einem Tag der Meta-Informationen gespeichert.
        // Dies ist eine vereinfachte Annahme und muss an Ihre tatsächliche Status-Speicherlogik angepasst werden!
        String alterStatusValue = "UNBEKANNT"; // Fallback
        if (document.getMeta() != null && document.getMeta().getTag() != null) {
            // Suchen Sie hier nach dem spezifischen Tag, das Ihren Status repräsentiert.
            // Beispiel: Annahme, es ist ein Tag mit einem bestimmten System.
            // Für dieses Beispiel nehmen wir an, der erste gefundene Tag-Code ist der Status.
            // In einer echten Implementierung wäre hier eine robustere Logik nötig.
            for (Coding metaTag : document.getMeta().getTag()) {
                // Beispiel: if ("https://IhrSystem.de/CodeSystem/status".equals(metaTag.getSystem())) {
                if (VALID_STATUS_CODES.contains(metaTag.getCode())) { // Prüfen, ob der Code ein valider Status ist
                    alterStatusValue = metaTag.getCode();
                    break;
                }
            }
        }
        LOGGER.info("Alter Status für Audit-Log ermittelt: {}", alterStatusValue);

        // 3. Prüfe Zugriff auf DIESES Dokument über den zentralen Service
        authorizationService.validateDocumentAccess(document, accessToken);

        // 4. Delegiere die Statusänderungslogik an den ChangeStatusService
        DocumentReference updatedDocument = changeStatusService.processStatusChange(id, tag, accessToken);
            
        // 5. Erstelle die Antwort gemäß der Spezifikation
        Parameters result = new Parameters();
        result.addParameter().setName("meta").setValue(updatedDocument.getMeta());

        // 6. Audit-Log Eintrag erstellen
        try {
            Reference patientReference = null;
            if (updatedDocument.getSubject() != null && updatedDocument.getSubject().getReferenceElement().getResourceType().equals("Patient")) {
                patientReference = updatedDocument.getSubject();
            }
            String kvnr = accessToken.getKvnr().orElse(accessToken.getIdNumber()); // Fallback auf IDNumber, falls KVNR nicht da

            AuditEvent auditEvent = auditService.createRestAuditEvent(
                AuditEvent.AuditEventAction.U, // U für Update
                "change-status", // Korrekter Subtype-Code
                AuditEvent.AuditEventOutcome._0, // Erfolg
                new Reference(updatedDocument.getIdElement().toVersionless()),
                "DocumentReference", // Konsistenter Resource Name
                updatedDocument.getIdElement().toVersionless().getValue(), // entityWhatDisplay
                "Status von DocumentReference mit ID '" + id.getIdPart() + "' geändert von '" + alterStatusValue + "' zu '" + tag + "' durch Versicherten.",
                accessToken.getIdNumber(), // actorName - Korrektur: getIdNumber() als Fallback
                kvnr, // actorId (KVNR des Versicherten)
                patientReference // patientReference für Versicherter-Slice
            );

            // Füge spezifische Details für alten und neuen Status hinzu
            if (auditEvent != null && auditEvent.hasId()) {
                auditService.addEntityDetail(auditEvent, "alter-status", alterStatusValue);
                auditService.addEntityDetail(auditEvent, "neuer-status", tag);
            }

        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen des AuditEvents für ChangeStatusOperation: {}", e.getMessage(), e);
            // Die Hauptoperation sollte hierdurch nicht fehlschlagen
        }
            
        LOGGER.info("Change-Status-Operation erfolgreich beendet für Dokument mit ID {}", id.getIdPart());
        return result;
    }
} 