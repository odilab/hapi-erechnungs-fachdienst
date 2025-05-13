package ca.uhn.fhir.jpa.starter.custom.operation.changeStatus;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.DocumentRetrievalService;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Service-Klasse, die die Kernlogik für die Änderung des Status eines Rechnungsdokuments kapselt.
 */
@Service
public class ChangeStatusService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChangeStatusService.class);
    private static final String STATUS_SYSTEM = "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs";
    private static final List<String> VALID_STATUS_CODES = Arrays.asList("offen", "erledigt", "papierkorb");

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private DocumentRetrievalService documentRetrievalService;


    /**
     * Führt die Statusänderung für ein gegebenes Dokument durch.
     *
     * @param documentId  Die ID des Dokuments.
     * @param newStatus   Der neue Status-Code.
     * @param accessToken Der AccessToken des anfragenden Benutzers.
     * @return Das aktualisierte DocumentReference-Objekt.
     */
    public DocumentReference processStatusChange(IdType documentId, String newStatus, AccessToken accessToken) {
        LOGGER.info("Beginne Verarbeitung der Statusänderung für Dokument-ID {} auf Status {}", documentId.getIdPart(), newStatus);

        // 1. Lade das Dokument über den DocumentRetrievalService, wobei documentId.getIdPart() der Token ist.
        // Beachte: documentId enthält bereits den Typ "DocumentReference", findDocumentByToken erwartet aber nur die ID.
        DocumentReference document = documentRetrievalService.findDocument(documentId.getIdPart());

        // 2. Prüfe Zugriff auf das Dokument
        authorizationService.validateDocumentAccess(document, accessToken);

        // 3. Prüfe, ob der Statuswechsel zulässig ist
        String currentStatus = getCurrentStatus(document);
        validateStatusChange(currentStatus, newStatus);

        try {
            // 4. Aktualisiere den Status des Dokuments
            DocumentReference updatedDocument = updateDocumentStatus(document, newStatus);

            // 5. Protokolliere die Statusänderung
            logChangeStatusOperation(updatedDocument, accessToken, currentStatus, newStatus);

            LOGGER.info("Statusänderung für Dokument {} erfolgreich verarbeitet.", documentId.getIdPart());
            return updatedDocument;

        } catch (Exception e) {
            LOGGER.error("Fehler bei der Verarbeitung der Statusänderung für Dokument {}: {}", documentId.getIdPart(), e.getMessage(), e);
            // Hier könnte eine spezifischere Exception geworfen werden, falls nötig.
            throw new InternalErrorException("Fehler bei der Verarbeitung der Statusänderung: " + e.getMessage(), e);
        }
    }

    /**
     * Ermittelt den aktuellen Status des Dokuments aus den Meta-Tags.
     */
    private String getCurrentStatus(DocumentReference document) {
        Optional<Coding> statusTag = document.getMeta().getTag().stream()
                .filter(tag -> STATUS_SYSTEM.equals(tag.getSystem()))
                .findFirst();

        String status = statusTag.map(Coding::getCode).orElse("offen"); // Default ist "offen"
        LOGGER.debug("Ermittelter aktueller Status für Dokument {}: {}", document.getIdElement().getIdPart(), status);
        return status;
    }

    /**
     * Prüft, ob der Statuswechsel von currentStatus zu newStatus zulässig ist.
     */
    private void validateStatusChange(String currentStatus, String newStatus) {
        LOGGER.debug("Validiere Statuswechsel von '{}' zu '{}'", currentStatus, newStatus);

        // Prüfe, ob der aktuelle Status gültig ist (konservative Prüfung)
        if (!VALID_STATUS_CODES.contains(currentStatus)) {
            LOGGER.warn("Dokument hat einen ungültigen Status: {}. Behandle als 'offen'.", currentStatus);
            currentStatus = "offen"; // Fallback, falls alter Status inkonsistent
        }

        // Prüfe, ob der Status gleich bleibt
        if (currentStatus.equals(newStatus)) {
            throw new UnprocessableEntityException("Der Dokument hat bereits den Status '" + newStatus + "'");
        }

        // Prüfe spezifische unzulässige Wechsel
        if ("papierkorb".equals(currentStatus)) {
            // Aus dem Papierkorb kann nicht zurückgewechselt werden
            throw new UnprocessableEntityException("Dokumente im Papierkorb können nicht in einen anderen Status versetzt werden");
        }

        // Alle anderen Statuswechsel sind erlaubt (offen -> erledigt, offen -> papierkorb, erledigt -> offen, erledigt -> papierkorb)
        LOGGER.info("Statuswechsel von '{}' zu '{}' ist zulässig", currentStatus, newStatus);
    }

    /**
     * Aktualisiert den Status eines Dokuments, inklusive Meta-Tags und Extensions.
     */
    private DocumentReference updateDocumentStatus(DocumentReference document, String newStatus) {
        LOGGER.info("Aktualisiere Status des Dokuments mit ID {} auf {}",
                document.getIdElement().getIdPart(), newStatus);

        DocumentReference documentToUpdate = document.copy();

        // 1. Setze den DocumentReference.status
        if ("papierkorb".equals(newStatus)) {
            documentToUpdate.setStatus(Enumerations.DocumentReferenceStatus.ENTEREDINERROR);
        } else {
            // Für "offen" und "erledigt" setzen wir CURRENT
            documentToUpdate.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
        }

        // 2. Aktualisiere die Datums-Extensions
        updateStatusChangeDates(documentToUpdate, newStatus);

        // 3. Speichere das Dokument mit dem aktualisierten Status und den Extensions
        // Die Meta-Tags werden in separaten Schritten aktualisiert
        DaoMethodOutcome outcome = daoRegistry.getResourceDao(DocumentReference.class).update(documentToUpdate);
        DocumentReference savedDocument = (DocumentReference) outcome.getResource();

        // Stelle sicher, dass wir die aktuellste Version für die Meta-Operationen haben
        // Hier brauchen wir wieder die ID, da findDocumentByToken nur den ID-String nimmt.
        DocumentReference currentSavedDoc = documentRetrievalService.findDocument(savedDocument.getIdElement().getIdPart());

        // 4. Entferne alte Status-Tags via $meta-delete
        removeAllStatusTags(currentSavedDoc);

        // 5. Füge neues Status-Tag via $meta-add hinzu
        addStatusTag(currentSavedDoc, newStatus);

        // 6. Lese das finale Dokument mit allen Änderungen (Status, Extensions, Meta-Tags)
        // Erneutes Laden über die ID.
        DocumentReference finalDocument = documentRetrievalService.findDocument(currentSavedDoc.getIdElement().getIdPart());

        LOGGER.info("Dokument {} erfolgreich aktualisiert: Status={}, Version={}, Tags={}",
                finalDocument.getIdElement().getIdPart(),
                finalDocument.getStatus(),
                finalDocument.getMeta().getVersionId(),
                finalDocument.getMeta().getTag().size());

        return finalDocument;
    }


    /**
     * Aktualisiert die statusChangedDate und nextStatusChangeDate Extensions.
     */
    private void updateStatusChangeDates(DocumentReference document, String newStatus) {
        LocalDate statusChangeDate = LocalDate.now();
        LocalDate nextChangeDate = calculateNextStatusChangeDate(statusChangeDate, newStatus);

        if (nextChangeDate != null) {
            setExtension(document, "https://gematik.de/fhir/erg/StructureDefinition/statusChangedDate",
                    new DateTimeType(Date.from(statusChangeDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
            setExtension(document, "https://gematik.de/fhir/erg/StructureDefinition/nextStatusChangeDate",
                    new DateTimeType(Date.from(nextChangeDate.atStartOfDay(ZoneId.systemDefault()).toInstant())));
            LOGGER.info("Status-Änderungsdatum gesetzt auf: {}, Nächstes Status-Änderungsdatum gesetzt auf: {}",
                    statusChangeDate, nextChangeDate);
        } else {
             LOGGER.warn("Konnte kein nächstes Statusänderungsdatum für Status '{}' berechnen.", newStatus);
             // Entferne ggf. vorhandene Extensions, falls der neue Status keine erfordert? Aktuell nicht der Fall.
        }
    }

    private LocalDate calculateNextStatusChangeDate(LocalDate referenceDate, String status) {
         switch (status) {
            case "offen":
                // 3 Jahre + Aufrundung zum Jahresende
                return referenceDate.plusYears(3).withMonth(12).withDayOfMonth(31);
            case "erledigt":
                // 1 Jahr + Aufrundung zum Monatsende
                LocalDate oneYearLater = referenceDate.plusYears(1);
                return oneYearLater.withDayOfMonth(oneYearLater.lengthOfMonth());
            case "papierkorb":
                // 3 Monate + Aufrundung zum Monatsende
                LocalDate threeMonthsLater = referenceDate.plusMonths(3);
                return threeMonthsLater.withDayOfMonth(threeMonthsLater.lengthOfMonth());
            default:
                return null; // Oder eine Standard-Logik / Fehlerbehandlung
        }
    }

    /**
     * Setzt (überschreibt) eine Extension in einem Dokument.
     */
    private void setExtension(DocumentReference document, String url, Type value) {
        // Entferne bestehende Extension mit dieser URL, falls vorhanden
        document.getExtension().removeIf(ext -> url.equals(ext.getUrl()));
        // Füge neue Extension hinzu
        document.addExtension(url, value);
        LOGGER.debug("Extension '{}' gesetzt.", url);
    }

    /**
     * Entfernt alle Status-Tags (aus dem definierten STATUS_SYSTEM) via $meta-delete.
     */
    private void removeAllStatusTags(DocumentReference document) {
        IdType targetId = document.getIdElement().toVersionless(); // Operation auf der neuesten Version
        LOGGER.debug("Beginne Entfernen aller Status-Tags für Dokument {}", targetId.getValue());

        Meta metaSnapshot = document.getMeta(); // Aktueller Meta-Stand
        Meta metaToRemove = new Meta();
        boolean tagsFound = false;

        for (Coding tag : metaSnapshot.getTag()) {
            if (STATUS_SYSTEM.equals(tag.getSystem())) {
                metaToRemove.addTag(tag.getSystem(), tag.getCode(), tag.getDisplay());
                tagsFound = true;
                LOGGER.debug("  Tag zum Entfernen vorgemerkt: system={}, code={}", tag.getSystem(), tag.getCode());
            }
        }

        if (!tagsFound) {
            LOGGER.info("Keine Status-Tags zum Entfernen für Dokument {} gefunden.", targetId.getValue());
            return;
        }

        try {
            LOGGER.info("Führe $meta-delete für Dokument {} aus...", targetId.getValue());
            Meta updatedMeta = daoRegistry.getResourceDao(DocumentReference.class)
                    .metaDeleteOperation(targetId, metaToRemove, null); // RequestDetails sind null, da Operation intern
            LOGGER.info("Meta-Delete Operation erfolgreich ausgeführt für {}. Tags danach: {}", targetId.getValue(), updatedMeta.getTag().size());
        } catch (Exception e) {
            LOGGER.error("Fehler beim Ausführen von $meta-delete für Dokument {}: {}", targetId.getValue(), e.getMessage(), e);
            // Fehler weiterleiten, damit die Transaktion ggf. zurückgerollt wird
            throw new InternalErrorException("Fehler beim Entfernen der Status-Tags via $meta-delete", e);
        }
    }

    /**
     * Fügt das neue Status-Tag via $meta-add hinzu.
     */
    private void addStatusTag(DocumentReference document, String statusCode) {
         IdType targetId = document.getIdElement().toVersionless(); // Operation auf der neuesten Version
         LOGGER.debug("Beginne Hinzufügen des Status-Tags '{}' für Dokument {}", statusCode, targetId.getValue());

        Meta metaToAdd = new Meta();
        metaToAdd.addTag(STATUS_SYSTEM, statusCode, getDisplayForStatus(statusCode));

        try {
            LOGGER.info("Führe $meta-add für Dokument {} aus (Tag: {})...", targetId.getValue(), statusCode);
            Meta updatedMeta = daoRegistry.getResourceDao(DocumentReference.class)
                    .metaAddOperation(targetId, metaToAdd, null); // RequestDetails sind null
            LOGGER.info("Meta-Add Operation erfolgreich ausgeführt für {}. Tags danach: {}", targetId.getValue(), updatedMeta.getTag().size());
             // Zusätzlicher Log zur Überprüfung
            boolean tagFound = updatedMeta.getTag().stream()
                                  .anyMatch(t -> STATUS_SYSTEM.equals(t.getSystem()) && statusCode.equals(t.getCode()));
            if (tagFound) {
                 LOGGER.debug("Neues Tag {} erfolgreich im Meta-Objekt gefunden.", statusCode);
            } else {
                 LOGGER.warn("Neues Tag {} NICHT im Meta-Objekt nach $meta-add gefunden!", statusCode);
            }
        } catch (Exception e) {
            LOGGER.error("Fehler beim Ausführen von $meta-add für Dokument {}: {}", targetId.getValue(), e.getMessage(), e);
            throw new InternalErrorException("Fehler beim Hinzufügen des Status-Tags via $meta-add", e);
        }
    }

     /**
     * Liefert den Display-Text für einen Status-Code.
     */
    private String getDisplayForStatus(String statusCode) {
        switch (statusCode) {
            case "offen": return "Offen";
            case "erledigt": return "Erledigt";
            case "papierkorb": return "Papierkorb";
            default: return statusCode; // Fallback
        }
    }


    /**
     * Protokolliert die Change-Status-Operation im AuditLog.
     */
    private void logChangeStatusOperation(DocumentReference document, AccessToken accessToken, String alterStatus, String neuerStatus) {
        String actorName = accessToken.getGivenName() + " " + accessToken.getFamilyName();
        // KVNR sollte vorhanden sein, da in authorizeChangeStatusOperation geprüft
        String actorId = accessToken.getKvnr().orElse("Unbekannt");

        AuditEvent auditEvent = auditService.createRestAuditEvent(
                AuditEvent.AuditEventAction.U, // Update Action
                "change-status",
                new Reference(document.getId()), // Referenz auf das geänderte Dokument
                "DocumentReference",
                "Statusänderung eines Rechnungsvorgangs von '" + alterStatus + "' zu '" + neuerStatus + "'",
                actorName,
                actorId
        );

        // Füge spezifische Details hinzu
        auditService.addEntityDetail(auditEvent, "alter-status", alterStatus);
        auditService.addEntityDetail(auditEvent, "neuer-status", neuerStatus);
        auditService.addEntityDetail(auditEvent, "document-version", document.getMeta().getVersionId());

        // AuditEvent wird implizit durch den AuditInterceptor gespeichert.
         LOGGER.debug("AuditEvent für Statusänderung (Dokument {}) erstellt.", document.getIdElement().getIdPart());
    }
} 