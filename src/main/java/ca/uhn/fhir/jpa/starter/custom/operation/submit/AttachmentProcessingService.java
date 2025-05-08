package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class AttachmentProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentProcessingService.class);
    private static final long MAX_ATTACHMENT_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    private static final String CONTENT_TYPE_OCTET_STREAM = "application/octet-stream";
    private static final String MODE_NORMAL = "normal"; // Wird benötigt, um zu entscheiden, ob gespeichert wird

    private final CustomValidator customValidator;
    private final DaoRegistry daoRegistry;

    @Autowired
    public AttachmentProcessingService(CustomValidator customValidator, DaoRegistry daoRegistry) {
        this.customValidator = customValidator;
        this.daoRegistry = daoRegistry;
    }

    public static class AttachmentProcessingOverallResult {
        public final List<ProcessedAttachmentResult> processedAttachments;
        public final List<SingleValidationMessage> messages; // Fehler und Warnungen

        public AttachmentProcessingOverallResult(List<ProcessedAttachmentResult> processedAttachments, List<SingleValidationMessage> messages) {
            this.processedAttachments = processedAttachments;
            this.messages = messages;
        }
    }

    public AttachmentProcessingOverallResult processAndLinkAttachments(
            List<DocumentReference> anhaenge,
            String modusValue,
            DocumentReference transformedHauptRechnung) { // transformedHauptRechnung zum Verlinken
        
        List<ProcessedAttachmentResult> successfulResults = new ArrayList<>();
        List<SingleValidationMessage> allMessagesFromAttachments = new ArrayList<>();

        if (anhaenge == null || anhaenge.isEmpty() || !MODE_NORMAL.equalsIgnoreCase(modusValue)) {
            if (anhaenge != null && !anhaenge.isEmpty() && !MODE_NORMAL.equalsIgnoreCase(modusValue)) {
                LOGGER.info("Überspringe Anhangverarbeitung im Modus '{}'.", modusValue);
            }
            return new AttachmentProcessingOverallResult(successfulResults, allMessagesFromAttachments);
        }

        LOGGER.info("Starte Verarbeitung für {} Anhänge.", anhaenge.size());
        for (int i = 0; i < anhaenge.size(); i++) {
            DocumentReference anhangDocRef = anhaenge.get(i);
            String anhangIdLog = anhangDocRef.hasId() ? anhangDocRef.getIdElement().getValue() : "Anhang #" + (i + 1) + " (ohne ID)";
            List<SingleValidationMessage> currentAttachmentMessages = new ArrayList<>();
            DocumentReference savedAnhangDocRef = null;

            try {
                // 1. Anhang validieren
                ValidationResult anhangValidationResult = customValidator.validateAndReturnResult(anhangDocRef);
                handleAttachmentValidationResult(anhangValidationResult, currentAttachmentMessages, "Anhang " + anhangIdLog, anhangDocRef.fhirType() + (anhangDocRef.hasId() ? "/" + anhangDocRef.getIdPart() : ""));

                // Nur fortfahren, wenn keine FEHLER bei der Validierung des Anhangs aufgetreten sind
                boolean hasErrors = currentAttachmentMessages.stream().anyMatch(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL);
                if (hasErrors) {
                    allMessagesFromAttachments.addAll(currentAttachmentMessages);
                    LOGGER.warn("Anhang {} hat Validierungsfehler und wird nicht weiterverarbeitet.", anhangIdLog);
                    continue; // Nächsten Anhang bearbeiten
                }

                // 2. Anhang-Inhalte als Binary speichern und URLs im Anhang ersetzen
                DocumentReference anhangToProcess = anhangDocRef.copy(); // Kopie für Modifikationen
                processIndividualAttachmentContents(anhangToProcess, anhangIdLog);

                // 3. Modifizierte Anhang-DocumentReference speichern
                LOGGER.debug("Speichere verarbeitete Anhang-DocumentReference: {}", anhangIdLog);
                DaoMethodOutcome savedAnhangOutcome = daoRegistry.getResourceDao(DocumentReference.class).create(anhangToProcess);
                if (savedAnhangOutcome.getCreated() != null && savedAnhangOutcome.getCreated()) {
                    savedAnhangDocRef = (DocumentReference) savedAnhangOutcome.getResource();
                    LOGGER.info("Anhang-DocumentReference {} erfolgreich gespeichert mit ID: {}", anhangIdLog, savedAnhangDocRef.getIdElement().getValue());
                } else {
                    LOGGER.error("Fehler beim Speichern der Anhang-DocumentReference {}. Outcome: {}", anhangIdLog, savedAnhangOutcome);
                    throw new InternalErrorException("Konnte Anhang-DocumentReference " + anhangIdLog + " nicht speichern.");
                }

            } catch (UnprocessableEntityException | InternalErrorException e) {
                LOGGER.error("Verarbeitungsfehler (UEE/IEE) bei Anhang {}: {}", anhangIdLog, e.getMessage());
                SingleValidationMessage errorMsg = new SingleValidationMessage();
                errorMsg.setSeverity(ResultSeverityEnum.ERROR);
                errorMsg.setMessage("Fehler bei Verarbeitung von Anhang " + anhangIdLog + ": " + e.getMessage());
                errorMsg.setLocationString(anhangDocRef.fhirType() + (anhangDocRef.hasId() ? "/" + anhangDocRef.getIdPart() : ""));
                currentAttachmentMessages.add(errorMsg);
            } catch (Exception e) {
                LOGGER.error("Unerwarteter Fehler bei Verarbeitung von Anhang {}.", anhangIdLog, e);
                SingleValidationMessage errorMsg = new SingleValidationMessage();
                errorMsg.setSeverity(ResultSeverityEnum.FATAL);
                errorMsg.setMessage("Unerwarteter Fehler bei Verarbeitung von Anhang " + anhangIdLog + ": " + e.getMessage());
                errorMsg.setLocationString(anhangDocRef.fhirType() + (anhangDocRef.hasId() ? "/" + anhangDocRef.getIdPart() : ""));
                currentAttachmentMessages.add(errorMsg);
            }
            
            allMessagesFromAttachments.addAll(currentAttachmentMessages);

            // Nur wenn Anhang erfolgreich gespeichert wurde, zu Ergebnissen hinzufügen und verlinken
            if (savedAnhangDocRef != null) {
                OperationOutcome anhangWarningsOutcome = createOperationOutcomeForAttachment(currentAttachmentMessages);
                successfulResults.add(new ProcessedAttachmentResult(savedAnhangDocRef, anhangWarningsOutcome));
                linkAttachmentToHauptRechnung(transformedHauptRechnung, savedAnhangDocRef);
            }
        }
        LOGGER.info("Verarbeitung von {} Anhängen beendet. {} erfolgreich verarbeitet.", anhaenge.size(), successfulResults.size());
        return new AttachmentProcessingOverallResult(successfulResults, allMessagesFromAttachments);
    }

    private void processIndividualAttachmentContents(DocumentReference anhangToProcess, String anhangIdLog) {
        if (anhangToProcess.hasContent()) {
            for (DocumentReference.DocumentReferenceContentComponent content : anhangToProcess.getContent()) {
                if (content.hasAttachment() && content.getAttachment().hasData()) {
                    Attachment attachment = content.getAttachment();
                    byte[] attachmentData = attachment.getData();
                    String attachmentContentType = attachment.hasContentType() ? attachment.getContentType() : CONTENT_TYPE_OCTET_STREAM;

                    if (attachmentData.length > MAX_ATTACHMENT_SIZE_BYTES) {
                        LOGGER.error("Anhang {} Attachment-Daten überschreiten die maximale Größe von {} Bytes.", anhangIdLog, MAX_ATTACHMENT_SIZE_BYTES);
                        throw new UnprocessableEntityException("Anhang " + anhangIdLog + " Attachment überschreitet die maximale Größe von 10MB.");
                    }

                    LOGGER.debug("Speichere Attachment-Daten ({} Bytes, Typ: {}) aus Anhang {} als Binary.", attachmentData.length, attachmentContentType, anhangIdLog);
                    Binary binary = new Binary();
                    binary.setContentType(attachmentContentType);
                    binary.setData(attachmentData);
                    DaoMethodOutcome binaryOutcome = daoRegistry.getResourceDao(Binary.class).create(binary);

                    if (binaryOutcome.getCreated() != null && binaryOutcome.getCreated()) {
                        String binaryUrl = binaryOutcome.getId().toUnqualifiedVersionless().getValue();
                        attachment.setData(null);
                        attachment.setUrl(binaryUrl);
                        LOGGER.info("Attachment-Daten aus Anhang {} als Binary ({}) gespeichert und URL in Anhang gesetzt.", anhangIdLog, binaryUrl);
                    } else {
                        LOGGER.error("Fehler beim Speichern der Attachment-Daten aus Anhang {} als Binary.", anhangIdLog);
                        throw new InternalErrorException("Konnte Attachment-Daten aus Anhang " + anhangIdLog + " nicht als Binary speichern.");
                    }
                }
            }
        }
    }
    
    private void linkAttachmentToHauptRechnung(DocumentReference transformedHauptRechnung, DocumentReference savedAnhangDocRef) {
        if (savedAnhangDocRef != null && savedAnhangDocRef.hasId()) {
            DocumentReference.DocumentReferenceContextComponent context = transformedHauptRechnung.getContext();
            if (!transformedHauptRechnung.hasContext()) {
                context = new DocumentReference.DocumentReferenceContextComponent();
                transformedHauptRechnung.setContext(context);
            }
            Reference anhangRef = new Reference(savedAnhangDocRef.getIdElement().toUnqualifiedVersionless());
            anhangRef.setType("DocumentReference");
            context.addRelated(anhangRef);
            LOGGER.info("Referenz zu Anhang {} zur transformierten Hauptrechnung hinzugefügt.", savedAnhangDocRef.getIdElement().getValue());
        }
    }

    /**
     * Verarbeitet das ValidationResult für einen einzelnen Anhang.
     * Wirft KEINE Exception bei Fehlern, sondern fügt sie zur Message-Liste hinzu.
     * Nur Warnungen und Infos werden ebenfalls hinzugefügt.
     */
    private void handleAttachmentValidationResult(ValidationResult validationResult, List<SingleValidationMessage> messageList, String resourceType, String locationPrefix) {
        for (SingleValidationMessage message : validationResult.getMessages()) {
            // Setze eine spezifischere Location, falls nicht schon vorhanden oder zu generisch
            if (message.getLocationString() == null || message.getLocationString().isEmpty() || !message.getLocationString().startsWith(locationPrefix)) {
                 message.setLocationString(locationPrefix + (message.getLocationString() != null ? " (" + message.getLocationString() + ")" : ""));
            }
            messageList.add(message); // Alle Nachrichten (Fatal, Error, Warning, Info) hinzufügen

            if (message.getSeverity() == ResultSeverityEnum.ERROR || message.getSeverity() == ResultSeverityEnum.FATAL) {
                 LOGGER.error("Validierungsfehler für {}: {} an {}", resourceType, message.getMessage(), message.getLocationString());
            }
        }
    }

    private OperationOutcome createOperationOutcomeForAttachment(List<SingleValidationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        OperationOutcome outcome = new OperationOutcome();
        messages.stream()
            // Nur Warnungen und Infos für das ProcessedAttachmentResult. Fehler wurden schon geloggt und verhindern Speicherung.
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING || m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .forEach(message -> {
                OperationOutcome.IssueSeverity severity = OperationOutcome.IssueSeverity.NULL;
                switch (message.getSeverity()) {
                    case WARNING: severity = OperationOutcome.IssueSeverity.WARNING; break;
                    case INFORMATION: severity = OperationOutcome.IssueSeverity.INFORMATION; break;
                    default: break; // Andere sollten hier nicht mehr ankommen
                }
                outcome.addIssue()
                    .setSeverity(severity)
                    .setCode(OperationOutcome.IssueType.INVALID) // Oder passenderer Code
                    .setDiagnostics(message.getMessage())
                    .addLocation(message.getLocationString());
            });
        return outcome.getIssue().isEmpty() ? null : outcome;
    }
} 