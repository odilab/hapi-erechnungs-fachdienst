package ca.uhn.fhir.jpa.starter.custom.operation.submit.validation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.jpa.starter.custom.operation.submit.TokenGenerationService;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
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

@Component
public class RechnungValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RechnungValidator.class);
    private final CustomValidator customValidator;
    private final DaoRegistry daoRegistry;
    private final TokenGenerationService tokenGenerationService;

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
    public RechnungValidator(CustomValidator customValidator, DaoRegistry daoRegistry, TokenGenerationService tokenGenerationService) {
        this.customValidator = customValidator;
        this.daoRegistry = daoRegistry;
        this.tokenGenerationService = tokenGenerationService;
    }

    /**
     * Validiert die übergebene DocumentReference (Rechnung) und speichert sie optional.
     * - Führt immer die FHIR-Validierung durch.
     * - Wenn modus='normal', wird die Rechnung zusätzlich in der Datenbank gespeichert.
     * - Wenn modus='test', wird nur validiert.
     * Wirft eine UnprocessableEntityException, wenn die FHIR-Validierung FATAL oder ERROR ergibt.
     *
     * @param rechnung Die zu validierende DocumentReference.
     * @param modus Der Verarbeitungsmodus ('normal' oder 'test').
     * @return Ein ValidationAndTransformResult Objekt, das ggf. Warnungen und die transformierte Rechnung enthält.
     */
    public ValidationAndTransformResult validate(DocumentReference rechnung, CodeType modus) {
        OperationOutcome warningsOutcome = null;
        DocumentReference finalTransformedRechnung = null; // Variable für das Endergebnis

        if (rechnung == null) {
            LOGGER.warn("validate wurde mit einer null DocumentReference aufgerufen.");
            // Hier könnten wir eine Exception werfen oder ein leeres Ergebnis zurückgeben
            // throw new IllegalArgumentException("DocumentReference darf nicht null sein."); 
            return new ValidationAndTransformResult(null, null);
        }

        String modusValue = (modus != null) ? modus.getValueAsString() : "normal";

        LOGGER.debug("Starte Validierung/Verarbeitung für DocumentReference (Rechnung) mit ID: {}. Modus: {}",
                rechnung.hasId() ? rechnung.getIdElement().getValue() : "keine ID", modusValue);

        // 1. FHIR-validieren und Ergebnis holen
        ValidationResult validationResult = customValidator.validateAndReturnResult(rechnung);

        // 2. Auf FATAL/ERROR prüfen und ggf. Exception werfen
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || 
                        m.getSeverity() == ResultSeverityEnum.FATAL)
            .collect(Collectors.toList());
            
        if (!errors.isEmpty()) {
            OperationOutcome errorOutcome = createOperationOutcomeFromMessages(errors);
            String errorMessage = errors.stream()
                .map(single -> single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
            LOGGER.error("Validierungsfehler gefunden: \n{}", errorMessage);
            throw new UnprocessableEntityException("Validierungsfehler: " + errorMessage, errorOutcome);
        }

        LOGGER.debug("DocumentReference (Rechnung) erfolgreich FHIR-validiert (keine Errors/Fatals).");

        // 3. Speichern und Transformieren nur im 'normal'-Modus (nach erfolgreicher Validierung)
        if ("normal".equalsIgnoreCase(modusValue)) {
            try {
                LOGGER.info("Modus 'normal': Speichere DocumentReference (Rechnung) mit ID: {} in der Datenbank.",
                        rechnung.hasId() ? rechnung.getIdElement().getValue() : "(wird generiert)");
                DaoMethodOutcome docRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).create(rechnung);
                if (docRefOutcome.getCreated() != null && docRefOutcome.getCreated()) {
                    String originalDocRefId = docRefOutcome.getId().getValue();
                    LOGGER.info("DocumentReference erfolgreich gespeichert mit ID: {}", originalDocRefId);

                    // Hole die *gespeicherte* Referenz, da sie eine ID hat
                    DocumentReference savedRechnung = (DocumentReference) docRefOutcome.getResource();
                    if (savedRechnung == null) {
                         // Sollte nicht passieren, wenn created=true, aber sicherheitshalber prüfen
                        LOGGER.error("Konnte die gespeicherte DocumentReference nicht vom DaoMethodOutcome abrufen, obwohl created=true.");
                        throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler nach dem Speichern der Rechnung: Gespeicherte Ressource nicht verfügbar.");
                    }

                    // Erstelle Kopie für die "transforms"-Referenz
                    DocumentReference transformedRechnung = savedRechnung.copy();
                    // ID entfernen, da wir eine neue setzen werden
                    transformedRechnung.setId((String)null); 

                    // Generiere eindeutige ID für die transformierte Rechnung
                    String generatedTokenId = tokenGenerationService.generateUniqueToken();
                    transformedRechnung.setId(generatedTokenId); // Setze die generierte ID
                    LOGGER.info("Generierte eindeutige ID für transformierte Rechnung: {}", generatedTokenId);
                    // Hier könntest du die ID zwischenspeichern, wenn sie außerhalb benötigt wird
                    // z.B. in einer Map oder einem Kontextobjekt, das weitergereicht wird.

                    // Füge relatesTo hinzu
                    DocumentReference.DocumentReferenceRelatesToComponent relatesTo = new DocumentReference.DocumentReferenceRelatesToComponent();
                    relatesTo.setCode(DocumentReference.DocumentRelationshipType.TRANSFORMS);
                    relatesTo.setTarget(new Reference(originalDocRefId)); // Referenz auf die ursprüngliche, gespeicherte DocRef
                    transformedRechnung.addRelatesTo(relatesTo);

                    // Speichere die neue ("transformierte") DocumentReference MIT der generierten ID via UPDATE
                    LOGGER.info("Speichere transformierte DocumentReference mit ID {} (relatesTo {}).", generatedTokenId, originalDocRefId);
                    try {
                        DaoMethodOutcome transformedDocRefOutcome = daoRegistry.getResourceDao(DocumentReference.class).update(transformedRechnung);
                        if (transformedDocRefOutcome.getId() != null && generatedTokenId.equals(transformedDocRefOutcome.getId().getIdPart())) {
                             LOGGER.info("Transformierte DocumentReference erfolgreich mit ID {} aktualisiert/gespeichert.", transformedDocRefOutcome.getId().getValue());
                             // Speichere die erfolgreich gespeicherte transformierte Rechnung für die Rückgabe
                             finalTransformedRechnung = transformedRechnung; 
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
                     // Ggf. auch hier einen Fehler werfen?
                }
            } catch (Exception e) {
                LOGGER.error("Fehler beim Speichern der initialen DocumentReference (Rechnung) in der Datenbank.", e);
                throw new ca.uhn.fhir.rest.server.exceptions.InternalErrorException("Fehler beim Speichern der Rechnung: " + e.getMessage(), e);
            }
        } else if ("test".equalsIgnoreCase(modusValue)) {
            LOGGER.info("Modus 'test': Nur Validierung durchgeführt, kein Speichern oder Transformieren der DocumentReference (Rechnung).");
        } else {
             LOGGER.warn("Unbekannter Modus '{}' in RechnungValidator.validate erhalten. Behandle wie 'test'.", modusValue);
        }

        // 4. Warnungen/Informationen extrahieren
        List<SingleValidationMessage> warningsOrInfo = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING ||
                        m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .collect(Collectors.toList());

        if (!warningsOrInfo.isEmpty()) {
             String warningMessage = warningsOrInfo.stream()
                .map(single -> single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
            LOGGER.warn("Validierungswarnungen/-informationen gefunden:\n{}", warningMessage);
            warningsOutcome = createOperationOutcomeFromMessages(warningsOrInfo);
        } else {
            LOGGER.debug("Keine Validierungswarnungen oder -informationen gefunden.");
        }

        // 5. Ergebnis zurückgeben
        return new ValidationAndTransformResult(warningsOutcome, finalTransformedRechnung);
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