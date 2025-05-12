package ca.uhn.fhir.jpa.starter.custom.operation.changeStatus;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
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
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Provider für die Change-Status-Operation, die die Änderung des Status eines Rechnungsdokuments ermöglicht.
 * Diese Operation ist als FHIR-Operation $change-status auf dem Endpunkt /DocumentReference/{id}/ implementiert.
 * Die Operation ermöglicht es dem Versicherten, den Status einer Rechnung zu ändern.
 */
@Component
public class ChangeStatusOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeStatusOperationProvider.class);
    private static final String STATUS_SYSTEM = "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs";
    private static final List<String> VALID_STATUS_CODES = Arrays.asList("offen", "erledigt", "papierkorb");

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private CustomValidator validator;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuthorizationService authorizationService;

    private final FhirContext ctx;

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

        // 1. Authentifizierung und Basis-Autorisierung über den zentralen Service
        AccessToken accessToken = authorizationService.validateAndExtractAccessToken(theRequestDetails);
        // TODO: Ggf. spezifischere Scope-Prüfung für Change-Status hinzufügen?
        // Aktuell prüft validateAndExtractAccessToken nur auf Vorhandensein.
        // Wir brauchen hier aber 'invoiceDoc.u' oder 'openid e-rezept'.
        String scope = accessToken.getScope();
        if (scope == null || (!scope.contains("invoiceDoc.u") && !scope.contains("openid e-rezept"))) {
             LOGGER.warn("Fehlender oder ungültiger Scope '{}' für Change-Status (ID: {}). Erforderlich: 'invoiceDoc.u' oder 'openid e-rezept'", scope, accessToken.getIdNumber());
            throw new ForbiddenOperationException("Fehlender oder ungültiger Scope: Erforderlich ist 'invoiceDoc.u' oder 'openid e-rezept'");
        }

        // Zusätzliche Prüfung auf Profession (nur Versicherter darf Status ändern)
        if (accessToken.getProfession() != Profession.VERSICHERTER) {
            LOGGER.warn("Unautorisierte Profession '{}' für Change-Status (ID: {}).", accessToken.getProfession(), accessToken.getIdNumber());
            throw new ForbiddenOperationException("Nur Versicherte dürfen den Dokumentenstatus ändern.");
        }
        // Prüfe, ob die KVNR vorhanden ist (wird in validateDocumentAccess benötigt)
        if (accessToken.getKvnr().isEmpty()) {
            LOGGER.warn("Keine KVNR im Access Token des Versicherten gefunden (ID: {}).", accessToken.getIdNumber());
            throw new ForbiddenOperationException("Keine KVNR im Access Token gefunden.");
        }

        // 2. Lade das Dokument direkt über DAO
        DocumentReference document = null;
        try {
            IBaseResource resource = daoRegistry.getResourceDao(DocumentReference.class).read(id);
            if (resource instanceof DocumentReference) {
                document = (DocumentReference) resource;
                 LOGGER.info("Dokument direkt via DAO geladen: ID={}, Version={}, Status={}", 
                    document.getIdElement().getIdPart(),
                    document.getMeta().getVersionId(),
                    document.getStatus());
            } else {
                // Sollte nicht passieren, wenn read erfolgreich war
                 LOGGER.error("Gelesene Ressource ist kein DocumentReference: {}", resource.fhirType());
                throw new ResourceNotFoundException("Ressource mit ID " + id.getIdPart() + " ist kein DocumentReference.");
            }
        } catch (ResourceNotFoundException e) {
            LOGGER.warn("Dokument mit ID {} nicht gefunden.", id.getIdPart());
            throw e; // Exception weiterleiten
        } catch (Exception e) {
             LOGGER.error("Fehler beim Laden des Dokuments mit ID {} via DAO.", id.getIdPart(), e);
            throw new InternalErrorException("Fehler beim Laden des Dokuments: " + e.getMessage(), e);
        }

        // Wenn kein Dokument gefunden wurde, wirf eine ResourceNotFoundException
        if (document == null) {
            throw new ResourceNotFoundException("Kein Dokument mit ID " + id.getIdPart() + " gefunden");
        }

        // 3. Prüfe Zugriff auf DIESES Dokument über den zentralen Service
        authorizationService.validateDocumentAccess(document, accessToken);

        // Prüfe, ob der Statuswechsel zulässig ist
        validateStatusChange(document, tag);

        try {
            // Aktualisiere den Status des Dokuments
            DocumentReference updatedDocument = updateDocumentStatusWithMetaOperations(document, tag);
            
            // Protokolliere die Statusänderung
            String alterStatus = getCurrentStatus(document);
            logChangeStatusOperation(updatedDocument, accessToken, alterStatus, tag);
            
            // Erstelle die Antwort gemäß der Spezifikation
            Parameters result = new Parameters();
            result.addParameter().setName("meta").setValue(updatedDocument.getMeta());
            
            LOGGER.info("Change-Status-Operation erfolgreich beendet für Dokument mit ID {}", id.getIdPart());
            return result;
        } catch (Exception e) {
            LOGGER.error("Fehler beim Ändern des Status: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Ermittelt den aktuellen Status des Dokuments.
     */
    private String getCurrentStatus(DocumentReference document) {
        // Logge alle vorhandenen Tags
        LOGGER.info("Alle Tags im Dokument bei getCurrentStatus:");
        for (Coding tag : document.getMeta().getTag()) {
            LOGGER.info("  System={}, Code={}, Display={}", 
                tag.getSystem(), tag.getCode(), tag.getDisplay());
        }
        
        // Suche nach dem Status-Tag im Meta-Element
        Optional<Coding> statusTag = document.getMeta().getTag().stream()
            .filter(tag -> STATUS_SYSTEM.equals(tag.getSystem()))
            .findFirst();
            
        String status = statusTag.map(Coding::getCode).orElse("offen"); // Default ist "offen"
        LOGGER.info("Ermittelter Status: {}", status);
        return status;
    }

    /**
     * Prüft, ob der Statuswechsel zulässig ist.
     */
    private void validateStatusChange(DocumentReference document, String newStatus) {
        String currentStatus = getCurrentStatus(document);
        LOGGER.info("Aktueller Status: {}, Neuer Status: {}", currentStatus, newStatus);
        
        // Prüfe, ob der aktuelle Status gültig ist
        if (!VALID_STATUS_CODES.contains(currentStatus)) {
            LOGGER.warn("Dokument hat einen ungültigen Status: {}. Behandle als 'offen'.", currentStatus);
            currentStatus = "offen";
        }
        
        // Prüfe, ob der Status gleich bleibt
        if (currentStatus.equals(newStatus)) {
            throw new UnprocessableEntityException("Der Dokument hat bereits den Status '" + newStatus + "'");
        }
        
        // Prüfe, ob der Statuswechsel zulässig ist
        if ("papierkorb".equals(currentStatus)) {
            // Aus dem Papierkorb kann nicht zurückgewechselt werden
            throw new UnprocessableEntityException("Dokumente im Papierkorb können nicht in einen anderen Status versetzt werden");
        }
        
        // Alle anderen Statuswechsel sind erlaubt
        LOGGER.info("Statuswechsel von '{}' zu '{}' ist zulässig", currentStatus, newStatus);
    }

    /**
     * Liefert den Display-Text für einen Status-Code.
     */
    private String getDisplayForStatus(String statusCode) {
        switch (statusCode) {
            case "offen":
                return "Offen";
            case "erledigt":
                return "Erledigt";
            case "papierkorb":
                return "Papierkorb";
            default:
                return statusCode;
        }
    }

    /**
     * Aktualisiert den Status eines Dokuments mit den Meta-Operationen $meta-delete und $meta-add.
     * Diese Methode entfernt zuerst alle Status-Tags und fügt dann das neue Status-Tag hinzu.
     * 
     * @param document Das Dokument, dessen Status geändert werden soll
     * @param newStatus Der neue Status
     * @return Das aktualisierte Dokument
     */
    private DocumentReference updateDocumentStatusWithMetaOperations(DocumentReference document, String newStatus) {
        LOGGER.info("Aktualisiere Status des Dokuments mit ID {} auf {} mit Meta-Operationen", 
            document.getIdElement().getIdPart(), newStatus);
        
        // Erstelle eine Kopie des Dokuments
        DocumentReference updatedDocument = document.copy();
        
        // Setze den entsprechenden DocumentReference-Status
        if ("papierkorb".equals(newStatus)) {
            updatedDocument.setStatus(Enumerations.DocumentReferenceStatus.ENTEREDINERROR);
        } else {
            updatedDocument.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        }
        
        // Aktualisiere statusChangedDate und nextStatusChangeDate
        updateStatusChangeDates(updatedDocument, newStatus);
        
        // Speichere das Dokument mit dem aktualisierten Status
        DaoMethodOutcome outcome = daoRegistry.getResourceDao(DocumentReference.class).update(updatedDocument);
        DocumentReference savedDocument = (DocumentReference) outcome.getResource();
        
        // Entferne alle Status-Tags
        removeAllStatusTags(savedDocument);
        
        // Füge das neue Status-Tag hinzu
        addStatusTag(savedDocument, newStatus);
        
        // Hole das aktualisierte Dokument
        IBaseResource resource = daoRegistry.getResourceDao(DocumentReference.class).read(savedDocument.getIdElement());
        if (!(resource instanceof DocumentReference)) {
            throw new ResourceNotFoundException("Dokument mit ID " + savedDocument.getIdElement().getIdPart() + " ist kein DocumentReference");
        }
        
        DocumentReference finalDocument = (DocumentReference) resource;
        
        // Logge das aktualisierte Dokument
        LOGGER.info("Aktualisiertes Dokument: Status={}, Version={}", 
            finalDocument.getStatus(), 
            finalDocument.getMeta().getVersionId());
        LOGGER.info("Anzahl der Tags nach der Aktualisierung: {}", finalDocument.getMeta().getTag().size());
        for (Coding coding : finalDocument.getMeta().getTag()) {
            LOGGER.info("Tag nach der Aktualisierung: System={}, Code={}, Display={}", 
                coding.getSystem(), coding.getCode(), coding.getDisplay());
        }
        
        return finalDocument;
    }
    
    /**
     * Aktualisiert die statusChangedDate und nextStatusChangeDate Extensions basierend auf dem neuen Status.
     * 
     * @param document Das zu aktualisierende Dokument
     * @param newStatus Der neue Status
     */
    private void updateStatusChangeDates(DocumentReference document, String newStatus) {
        LocalDate statusChangeDate = LocalDate.now();
        LocalDate nextChangeDate = null;
        
        switch (newStatus) {
            case "offen":
                // 3 Jahre + Aufrundung zum Jahresende
                nextChangeDate = statusChangeDate.plusYears(3).withMonth(12).withDayOfMonth(31);
                break;
            case "erledigt":
                // 1 Jahr + Aufrundung zum Monatsende
                LocalDate oneYearLater = statusChangeDate.plusYears(1);
                nextChangeDate = oneYearLater.withDayOfMonth(oneYearLater.lengthOfMonth());
                break;
            case "papierkorb":
                // 3 Monate + Aufrundung zum Monatsende
                LocalDate threeMonthsLater = statusChangeDate.plusMonths(3);
                nextChangeDate = threeMonthsLater.withDayOfMonth(threeMonthsLater.lengthOfMonth());
                break;
        }
        
        // Setze die Extensions
        setExtension(document, "https://gematik.de/fhir/erg/StructureDefinition/statusChangedDate", 
                    new DateTimeType(Date.from(statusChangeDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
        setExtension(document, "https://gematik.de/fhir/erg/StructureDefinition/nextStatusChangeDate", 
                    new DateTimeType(Date.from(nextChangeDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
        
        LOGGER.info("Status-Änderungsdatum gesetzt auf: {}, Nächstes Status-Änderungsdatum gesetzt auf: {}", 
                statusChangeDate, nextChangeDate);
    }
    
    /**
     * Setzt eine Extension in einem Dokument.
     * 
     * @param document Das Dokument
     * @param url Die URL der Extension
     * @param value Der Wert der Extension
     */
    private void setExtension(DocumentReference document, String url, Type value) {
        // Entferne bestehende Extension mit dieser URL
        document.getExtension().removeIf(ext -> url.equals(ext.getUrl()));
        
        // Füge neue Extension hinzu
        document.addExtension(url, value);
    }
    
    /**
     * Entfernt alle Status-Tags aus einem Dokument.
     * 
     * @param document Das Dokument, aus dem die Status-Tags entfernt werden sollen
     */
    private void removeAllStatusTags(DocumentReference document) {
        LOGGER.info("Entferne alle Status-Tags aus Dokument mit ID {}", document.getIdElement().getIdPart());
        
        // Erstelle eine Liste aller zu entfernenden Status-Tags
        List<Coding> tagsToRemove = document.getMeta().getTag().stream()
            .filter(tag -> STATUS_SYSTEM.equals(tag.getSystem()))
            .collect(java.util.stream.Collectors.toList());
            
        LOGGER.info("Zu entfernende Tags: {}", tagsToRemove.size());
        for (Coding tag : tagsToRemove) {
            LOGGER.info("  System={}, Code={}, Display={}", 
                tag.getSystem(), tag.getCode(), tag.getDisplay());
        }
        
        // Erstelle eine Meta-Instanz mit den zu entfernenden Tags
        Meta metaToRemove = new Meta();
        for (Coding tag : tagsToRemove) {
            metaToRemove.addTag(tag.getSystem(), tag.getCode(), tag.getDisplay());
        }
        
        // Wenn keine Tags zu entfernen sind, beende die Methode
        if (tagsToRemove.isEmpty()) {
            LOGGER.info("Keine Status-Tags zu entfernen");
            return;
        }
        
        try {
            // Führe die $meta-delete Operation aus
            Parameters inParams = new Parameters();
            inParams.addParameter().setName("meta").setValue(metaToRemove);
            
            LOGGER.info("Führe $meta-delete Operation aus");
            
            // Verwende die direkte DAO-Methode, um die Meta-Daten zu aktualisieren
            Meta updatedMeta = daoRegistry.getResourceDao(DocumentReference.class)
                .metaDeleteOperation(document.getIdElement(), metaToRemove, null);
                
            LOGGER.info("Meta-Delete Operation erfolgreich ausgeführt");
            LOGGER.info("Anzahl der Tags nach dem Entfernen: {}", updatedMeta.getTag().size());
            for (Coding tag : updatedMeta.getTag()) {
                LOGGER.info("  System={}, Code={}, Display={}", 
                    tag.getSystem(), tag.getCode(), tag.getDisplay());
            }
        } catch (Exception e) {
            LOGGER.error("Fehler beim Entfernen der Status-Tags: {}", e.getMessage(), e);
            throw e;
        }
    }
    
    /**
     * Fügt ein Status-Tag zu einem Dokument hinzu.
     * 
     * @param document Das Dokument, zu dem das Status-Tag hinzugefügt werden soll
     * @param statusCode Der Status-Code
     */
    private void addStatusTag(DocumentReference document, String statusCode) {
        LOGGER.info("Füge Status-Tag {} zu Dokument mit ID {} hinzu", 
            statusCode, document.getIdElement().getIdPart());
        
        // Erstelle eine Meta-Instanz mit dem neuen Status-Tag
        Meta metaToAdd = new Meta();
        metaToAdd.addTag(STATUS_SYSTEM, statusCode, getDisplayForStatus(statusCode));
        
        try {
            // Führe die $meta-add Operation aus
            Parameters inParams = new Parameters();
            inParams.addParameter().setName("meta").setValue(metaToAdd);
            
            LOGGER.info("Führe $meta-add Operation aus");
            
            // Verwende die direkte DAO-Methode, um die Meta-Daten zu aktualisieren
            Meta updatedMeta = daoRegistry.getResourceDao(DocumentReference.class)
                .metaAddOperation(document.getIdElement(), metaToAdd, null);
                
            LOGGER.info("Meta-Add Operation erfolgreich ausgeführt");
            LOGGER.info("Anzahl der Tags nach dem Hinzufügen: {}", updatedMeta.getTag().size());
            for (Coding tag : updatedMeta.getTag()) {
                LOGGER.info("  System={}, Code={}, Display={}", 
                    tag.getSystem(), tag.getCode(), tag.getDisplay());
            }
        } catch (Exception e) {
            LOGGER.error("Fehler beim Hinzufügen des Status-Tags: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Protokolliert die Change-Status-Operation
     * 
     * @param document Das aktualisierte Dokument
     * @param accessToken Der Access-Token des Nutzers
     * @param alterStatus Der alte Status
     * @param neuerStatus Der neue Status
     */
    private void logChangeStatusOperation(DocumentReference document, AccessToken accessToken, String alterStatus, String neuerStatus) {
        String actorName = accessToken.getGivenName() + " " + accessToken.getFamilyName();
        String actorId = accessToken.getKvnr().orElse(null);
        
        AuditEvent auditEvent = auditService.createRestAuditEvent(
            AuditEvent.AuditEventAction.U,
            "change-status",
            new Reference(document.getId()),
            "DocumentReference",
            "Statusänderung eines Rechnungsvorgangs von " + alterStatus + " zu " + neuerStatus,
            actorName,
            actorId
        );
        
        // Füge Status-Details hinzu
        auditService.addEntityDetail(auditEvent, "alter-status", alterStatus);
        auditService.addEntityDetail(auditEvent, "neuer-status", neuerStatus);
    }
} 