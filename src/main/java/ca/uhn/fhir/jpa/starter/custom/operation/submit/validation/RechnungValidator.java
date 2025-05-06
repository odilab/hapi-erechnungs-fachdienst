package ca.uhn.fhir.jpa.starter.custom.operation.submit.validation;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.operation.submit.TokenGenerationService;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Invoice;
import org.hl7.fhir.instance.model.api.IBaseResource;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.hl7.fhir.r4.model.*;

@Component
public class RechnungValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RechnungValidator.class);
    private final CustomValidator customValidator;
    private final DaoRegistry daoRegistry;
    private final TokenGenerationService tokenGenerationService;
    private final FhirContext ctx;

    /**
     * Container-Klasse für das Ergebnis der Validierung und Transformation.
     */
    public static class ValidationAndTransformResult {
        public final OperationOutcome warnings;
        public final DocumentReference transformedRechnung;

        ValidationAndTransformResult(OperationOutcome warnings, DocumentReference transformedRechnung) {
            this.warnings = warnings;
            this.transformedRechnung = transformedRechnung;
        }
    }

    @Autowired
    public RechnungValidator(CustomValidator customValidator, DaoRegistry daoRegistry, TokenGenerationService tokenGenerationService, FhirContext ctx) {
        this.customValidator = customValidator;
        this.daoRegistry = daoRegistry;
        this.tokenGenerationService = tokenGenerationService;
        this.ctx = ctx;
    }

    /**
     * Validiert die übergebene DocumentReference (Rechnung), extrahiert, validiert und speichert ggf. enthaltene Invoices,
     * speichert die Rechnung und eine transformierte Version (mit Links zur Invoice) im Normal-Modus.
     * - Führt immer die FHIR-Validierung für DocumentReference und extrahierte Invoices durch.
     * - Wenn modus='normal', werden DocumentReference und Invoices gespeichert und die transformierte DocumentReference angepasst.
     * - Wenn modus='test', wird nur validiert.
     * Wirft eine UnprocessableEntityException, wenn die FHIR-Validierung FATAL oder ERROR ergibt (für DocRef oder Invoice).
     *
     * @param rechnung Die zu validierende DocumentReference.
     * @param modus Der Verarbeitungsmodus ('normal' oder 'test').
     * @return Ein ValidationAndTransformResult Objekt, das ggf. Warnungen und die transformierte Rechnung enthält.
     */
    public ValidationAndTransformResult validate(DocumentReference rechnung, CodeType modus) {
        DocumentReference finalTransformedRechnung = null;
        List<SingleValidationMessage> allWarningsAndInfos = new ArrayList<>();
        Map<Integer, String> invoiceUrlMap = new HashMap<>();

        if (rechnung == null) {
            LOGGER.warn("validate wurde mit einer null DocumentReference aufgerufen.");
            return new ValidationAndTransformResult(null, null);
        }

        String modusValue = (modus != null) ? modus.getValueAsString() : "normal";

        LOGGER.debug("Starte Validierung/Verarbeitung für DocumentReference (Rechnung) mit ID: {}. Modus: {}",
                rechnung.hasId() ? rechnung.getIdElement().getValue() : "keine ID", modusValue);

        // 1. FHIR-validieren der Haupt-DocumentReference
        ValidationResult docRefValidationResult = customValidator.validateAndReturnResult(rechnung);
        handleValidationResult(docRefValidationResult, allWarningsAndInfos, "DocumentReference");

        // 2. Extrahiere, parse, validiere (und speichere ggf.) Invoices aus Attachments
        if (rechnung.hasContent()) {
            for (int i = 0; i < rechnung.getContent().size(); i++) {
                DocumentReference.DocumentReferenceContentComponent content = rechnung.getContent().get(i);
                if (content.hasAttachment() && content.getAttachment().hasContentType() && content.getAttachment().hasData()) {
                    Attachment attachment = content.getAttachment();
                    String contentType = attachment.getContentType();
                    boolean isFhirJson = "application/fhir+json".equalsIgnoreCase(contentType);
                    boolean isFhirXml = "application/fhir+xml".equalsIgnoreCase(contentType);

                    if (isFhirJson || isFhirXml) {
                        LOGGER.debug("Found potential FHIR Invoice in content index {} with contentType: {}", i, contentType);
                        try {
                            byte[] decodedBytes = Base64.getDecoder().decode(attachment.getData());
                            String decodedString = new String(decodedBytes, StandardCharsets.UTF_8);
                            
                            IParser parser = isFhirJson ? ctx.newJsonParser() : ctx.newXmlParser();
                            IBaseResource parsedResource = parser.parseResource(decodedString);

                            if (parsedResource instanceof Invoice) {
                                Invoice parsedInvoice = (Invoice) parsedResource;
                                LOGGER.info("Successfully parsed Invoice from content index {}", i);

                                // Validiere die Invoice
                                ValidationResult invoiceValidationResult = customValidator.validateAndReturnResult(parsedInvoice);
                                handleValidationResult(invoiceValidationResult, allWarningsAndInfos, "Invoice (Index " + i + ")");

                                // Speichere Invoice nur im Normal-Modus (nach erfolgreicher Validierung)
                                if ("normal".equalsIgnoreCase(modusValue)) {
                                    try {
                                         LOGGER.info("Modus 'normal': Speichere Invoice aus Content-Index {} in der Datenbank.", i);
                                         DaoMethodOutcome savedInvoiceOutcome = daoRegistry.getResourceDao(Invoice.class).create(parsedInvoice);
                                         if (savedInvoiceOutcome.getCreated() != null && savedInvoiceOutcome.getCreated()) {
                                             String invoiceUrl = savedInvoiceOutcome.getId().toUnqualifiedVersionless().getValue();
                                             LOGGER.info("Invoice erfolgreich gespeichert mit ID/URL: {}", invoiceUrl);
                                             invoiceUrlMap.put(i, invoiceUrl); // Index und URL merken
                                         } else {
                                             LOGGER.error("Speichern der Invoice aus Index {} hat kein 'created=true' zurückgegeben. Outcome: {}", i, savedInvoiceOutcome);
                                             throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Konnte die extrahierte Invoice nicht speichern.");
                                         }
                                    } catch (Exception eSave) {
                                        LOGGER.error("Fehler beim Speichern der Invoice aus Content-Index {}.", i, eSave);
                                        throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler beim Speichern der extrahierten Invoice: " + eSave.getMessage(), eSave);
                                    }
                                }
                            } else {
                                LOGGER.warn("Geparste Ressource aus Content-Index {} ist keine Invoice, sondern {}. Wird ignoriert.", i, parsedResource.fhirType());
                                // Optional: Warnung zum OperationOutcome hinzufügen?
                            }
                        } catch (IllegalArgumentException eDecode) {
                            LOGGER.error("Fehler beim Base64-Dekodieren der Daten aus Content-Index {}.", i, eDecode);
                            // Wirf Fehler oder füge Warnung hinzu?
                            throw new UnprocessableEntityException("Ungültige Base64-Kodierung in Attachment bei Index " + i + ": " + eDecode.getMessage());
                        } catch (Exception eParse) {
                            LOGGER.error("Fehler beim Parsen der FHIR-Ressource aus Content-Index {}. ContentType: {}.", i, contentType, eParse);
                            throw new UnprocessableEntityException("FHIR-Parsing-Fehler in Attachment bei Index " + i + ": " + eParse.getMessage());
                        }
                    }
                }
            }
        }

        // 3. Speichern und Transformieren der DocumentReference nur im 'normal'-Modus 
        if ("normal".equalsIgnoreCase(modusValue)) {
            try {
                LOGGER.info("Modus 'normal': Speichere initiale DocumentReference (Rechnung) mit ID: {} in der Datenbank.",
                        rechnung.hasId() ? rechnung.getIdElement().getValue() : "(wird generiert)");
                DaoMethodOutcome docRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).create(rechnung);
                if (docRefOutcome.getCreated() != null && docRefOutcome.getCreated()) {
                    String originalDocRefId = docRefOutcome.getId().getValue();
                    LOGGER.info("Initiale DocumentReference erfolgreich gespeichert mit ID: {}", originalDocRefId);
                    DocumentReference savedRechnung = (DocumentReference) docRefOutcome.getResource();
                     if (savedRechnung == null) {
                        LOGGER.error("Konnte die gespeicherte initiale DocumentReference nicht vom DaoMethodOutcome abrufen.");
                        throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler nach dem Speichern der Rechnung: Gespeicherte Ressource nicht verfügbar.");
                    }

                    // Erstelle Kopie für die Transformation
                    DocumentReference transformedRechnung = savedRechnung.copy();
                    transformedRechnung.setId((String)null); 

                    // --> Modifiziere die transformierte Rechnung basierend auf gespeicherten Invoices
                    if (!invoiceUrlMap.isEmpty()) {
                         LOGGER.debug("Modifiziere transformierte Rechnung: Ersetze Daten durch URLs für Indizes: {}", invoiceUrlMap.keySet());
                         for (Map.Entry<Integer, String> entry : invoiceUrlMap.entrySet()) {
                             int contentIndex = entry.getKey();
                             String invoiceUrl = entry.getValue();
                             if (contentIndex < transformedRechnung.getContent().size()) {
                                 Attachment attachmentToModify = transformedRechnung.getContent().get(contentIndex).getAttachment();
                                 attachmentToModify.setData(null); // Daten entfernen
                                 attachmentToModify.setUrl(invoiceUrl); // URL setzen
                                 LOGGER.debug("Content-Index {} in transformierter Rechnung: data entfernt, url '{}' gesetzt.", contentIndex, invoiceUrl);
                             } else {
                                  LOGGER.error("Interner Fehler: Content-Index {} aus invoiceUrlMap ist außerhalb der Grenzen der transformierten Rechnung (size {}). Überspringe.", contentIndex, transformedRechnung.getContent().size());
                             }
                         }
                    }
                    
                    // Füge relatesTo hinzu
                    DocumentReference.DocumentReferenceRelatesToComponent relatesTo = new DocumentReference.DocumentReferenceRelatesToComponent();
                    relatesTo.setCode(DocumentReference.DocumentRelationshipType.TRANSFORMS);
                    relatesTo.setTarget(new Reference(originalDocRefId));
                    transformedRechnung.addRelatesTo(relatesTo);

                    // Generiere eindeutige ID für die transformierte Rechnung
                    String generatedTokenId = tokenGenerationService.generateUniqueToken();
                    transformedRechnung.setId(generatedTokenId);
                    LOGGER.info("Generierte eindeutige ID für transformierte Rechnung: {}", generatedTokenId);

                    // Speichere die transformierte DocumentReference via UPDATE
                    LOGGER.info("Speichere transformierte DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId);
                    try {
                        DaoMethodOutcome transformedDocRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).update(transformedRechnung);
                        if (transformedDocRefOutcome.getId() != null && generatedTokenId.equals(transformedDocRefOutcome.getId().getIdPart())) {
                             LOGGER.info("Transformierte DocumentReference erfolgreich mit ID {} aktualisiert/gespeichert.", transformedDocRefOutcome.getId().getValue());
                             finalTransformedRechnung = transformedRechnung; // Für Rückgabe speichern
                        } else {
                            LOGGER.warn("Speichern/Update der transformierten DocumentReference war nicht erfolgreich oder gab unerwartetes Ergebnis zurück. Outcome: {}", transformedDocRefOutcome);
                            throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler beim Update der transformierten Rechnung: Unerwartetes Ergebnis vom DAO.");
                        }
                    } catch (Exception eTrans) {
                         LOGGER.error("Fehler beim Speichern/Update der transformierten DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId, eTrans);
                         throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler beim Speichern/Update der transformierten Rechnung: " + eTrans.getMessage(), eTrans);
                    }

                } else {
                     LOGGER.warn("Speichern der initialen DocumentReference hat kein 'created=true' zurückgegeben. Outcome: {}", docRefOutcome);
                }
            } catch (Exception e) {
                LOGGER.error("Fehler beim Speichern der initialen DocumentReference (Rechnung) in der Datenbank.", e);
                throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler beim Speichern der Rechnung: " + e.getMessage(), e);
            }
        } else {
            LOGGER.info("Modus 'test': Nur Validierung durchgeführt, kein Speichern oder Transformieren.");
        }

        // 4. Finale Warnungen/Infos sammeln und Ergebnis zurückgeben
        OperationOutcome finalWarningsOutcome = null;
        if (!allWarningsAndInfos.isEmpty()) {
            finalWarningsOutcome = createOperationOutcomeFromMessages(allWarningsAndInfos);
            LOGGER.info("Validierungswarnungen/-informationen gefunden und werden zurückgegeben.");
        } else {
            LOGGER.debug("Keine Validierungswarnungen oder -informationen gefunden.");
        }

        return new ValidationAndTransformResult(finalWarningsOutcome, finalTransformedRechnung);
    }

    /**
     * Verarbeitet das ValidationResult, wirft eine Exception bei Fehlern 
     * und fügt Warnungen/Infos zur übergebenen Liste hinzu.
     */
    private void handleValidationResult(ValidationResult validationResult, List<SingleValidationMessage> warningList, String resourceType) {
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || m.getSeverity() == ResultSeverityEnum.FATAL)
            .collect(Collectors.toList());
            
        if (!errors.isEmpty()) {
            OperationOutcome errorOutcome = createOperationOutcomeFromMessages(errors);
            String errorMessage = errors.stream()
                .map(single -> resourceType + " -> " + single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
            LOGGER.error("Validierungsfehler gefunden für {}: \n{}", resourceType, errorMessage);
            throw new UnprocessableEntityException("Validierungsfehler für " + resourceType + ": " + errorMessage, errorOutcome);
        }

        // Sammle nur Warnungen und Informationen
        validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING || m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .forEach(warningList::add);
    }

    /**
     * Erstellt ein OperationOutcome aus einer Liste von SingleValidationMessage.
     */
    private OperationOutcome createOperationOutcomeFromMessages(List<SingleValidationMessage> messages) {
        OperationOutcome outcome = new OperationOutcome();
        messages.forEach(message -> {
            OperationOutcome.IssueSeverity severity = OperationOutcome.IssueSeverity.NULL;
            switch (message.getSeverity()) {
                case FATAL: severity = OperationOutcome.IssueSeverity.FATAL; break;
                case ERROR: severity = OperationOutcome.IssueSeverity.ERROR; break;
                case WARNING: severity = OperationOutcome.IssueSeverity.WARNING; break;
                case INFORMATION: severity = OperationOutcome.IssueSeverity.INFORMATION; break;
                default: severity = OperationOutcome.IssueSeverity.NULL; break;
            }
            // Hier könnte man versuchen, den IssueType besser zu mappen, aber INVALID ist oft passend
            OperationOutcome.IssueType issueType = OperationOutcome.IssueType.INVALID;
            
            outcome.addIssue()
                .setSeverity(severity)
                .setCode(issueType)
                .setDiagnostics(message.getMessage()) // Nur die Nachricht
                .addLocation(message.getLocationString()); // Ort separat
        });
        return outcome;
    }
} 