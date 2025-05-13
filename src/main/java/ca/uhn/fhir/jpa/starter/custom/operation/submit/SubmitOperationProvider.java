package ca.uhn.fhir.jpa.starter.custom.operation.submit;


import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.operation.AuthorizationService;
import ca.uhn.fhir.jpa.starter.custom.operation.AuditService;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class SubmitOperationProvider implements IResourceProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger(SubmitOperationProvider.class);

	private final AuthorizationService authorizationService;
	private final RechnungProcessingService rechnungProcessingService;
	private final DaoRegistry daoRegistry;
	private final AuditService auditService;

	@Autowired
	public SubmitOperationProvider(AuthorizationService authorizationService,
									RechnungProcessingService rechnungProcessingService,
									DaoRegistry daoRegistry,
									AuditService auditService) {
		this.authorizationService = authorizationService;
		this.rechnungProcessingService = rechnungProcessingService;
		this.daoRegistry = daoRegistry;
		this.auditService = auditService;
	}

	@Override
	public Class<Patient> getResourceType() {
		return Patient.class;
	}

	@Operation(name = "$erechnung-submit", idempotent = false)
	public Parameters submitOperation(
			@IdParam IdType patientId,
			@OperationParam(name = "rechnung", min = 1) DocumentReference rechnung,
			@OperationParam(name = "anhang") List<DocumentReference> anhaenge,
			@OperationParam(name = "modus") CodeType modus,
			@OperationParam(name = "angereichertesPDF") BooleanType angereichertesPDF,
			RequestDetails theRequestDetails
	) {
		LOGGER.info("Submit Operation gestartet für Patient {}", patientId != null ? patientId.getIdPart() : "UNKNOWN");

		// Manuelle Prüfung des Pflichtparameters 'rechnung'
		if (rechnung == null) {
			throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException("Der Parameter 'rechnung' ist erforderlich.");
		}

  		// Validiere den Modus-Parameter
		if (modus != null && !("normal".equals(modus.getValue()) || "test".equals(modus.getValue()))) {
			throw new UnprocessableEntityException("Validierungsfehler: Ungültiger Modus: " + modus.getValue());
		}

		// 1. Berechtigungsprüfung
		AccessToken accessToken = authorizationService.authorizeSubmitOperation(theRequestDetails);
		LOGGER.debug("Authorization successful for user with profession: {}", accessToken.getProfession());

		// 2. FHIR-Validierung, ggf. Speicherung & Transformation über den neuen Service
		ValidationAndTransformResult validationResult = this.rechnungProcessingService.validate(rechnung, modus, accessToken, anhaenge);

		Parameters retVal = new Parameters();

		// Test-Modus prüfen: Nur Warnungen zurückgeben
		if (modus != null && "test".equals(modus.getValueAsString())) {
			LOGGER.info("Test-Modus aktiv: Nur Validierungswarnungen werden zurückgegeben.");
			if (validationResult != null && validationResult.warnings != null && !validationResult.warnings.getIssue().isEmpty()) {
				retVal.addParameter().setName("warnungen").setResource(validationResult.warnings);
			}
			// Im Test-Modus auch den Status "valid" zurückgeben, wenn keine Fehler aufgetreten sind (Warnungen sind ok)
            // Dies signalisiert, dass die Validierung an sich durchlief.
            // Wir prüfen hier nicht explizit auf Fehler, da der RechnungValidator bei Fehlern eine Exception wirft.
            boolean hasErrors = false; // Annahme: Keine Fehler, da sonst Exception vom Validator
            if (validationResult != null && validationResult.warnings != null) {
                for (OperationOutcome.OperationOutcomeIssueComponent issue : validationResult.warnings.getIssue()) {
                    if (issue.getSeverity() == OperationOutcome.IssueSeverity.ERROR || issue.getSeverity() == OperationOutcome.IssueSeverity.FATAL) {
                        hasErrors = true;
                        break;
                    }
                }
            }
            if (!hasErrors) {
                retVal.addParameter().setName("status").setValue(new StringType("valid"));
            }
			return retVal;
		}

		// Normal-Modus oder wenn Modus nicht 'test' ist:

		// 1. ERG-Token (ID der transformierten Rechnung)
		if (validationResult != null && validationResult.transformedRechnung != null && validationResult.transformedRechnung.hasId()) {
			String ergToken = validationResult.transformedRechnung.getIdElement().getIdPart();
			retVal.addParameter().setName("ergToken").setValue(new StringType(ergToken));
			LOGGER.info("ERG-Token (ID: {}) zur Antwort hinzugefügt.", ergToken);
		} else {
			LOGGER.warn("Keine transformierte Rechnung oder keine ID in transformierter Rechnung gefunden. ERG-Token wird nicht zur Antwort hinzugefügt.");
		}

		// 2. Angereichertes PDF (optional)
		boolean createEnrichedPdfRequested = angereichertesPDF != null && angereichertesPDF.getValue();
		if (createEnrichedPdfRequested && validationResult != null && validationResult.transformedRechnung != null) {
			DocumentReference transformedRechnung = validationResult.transformedRechnung;
			String pdfBinaryUrl = null;
			if (transformedRechnung.hasContent()) {
				for (DocumentReference.DocumentReferenceContentComponent content : transformedRechnung.getContent()) {
					if (content.hasAttachment() && "application/pdf".equals(content.getAttachment().getContentType()) && content.getAttachment().hasUrl()) {
						pdfBinaryUrl = content.getAttachment().getUrl();
						break;
					}
				}
			}

			if (pdfBinaryUrl != null) {
				try {
					// Annahme: Die URL ist eine relative ID zur Binary-Ressource, z.B. "Binary/123"
					// Hier greifen wir direkt über das DAO zu, da wir im selben System sind.
                    // Ein FHIR-Client-Aufruf wäre auch möglich, aber umständlicher.
                    // Wichtig: Die ID muss korrekt extrahiert werden (z.B. nur der ID-Teil ohne "Binary/")
                    IdType binaryId = new IdType(pdfBinaryUrl);
                    if (!binaryId.getResourceType().equals("Binary")) {
                         // Fallback, wenn die URL nicht standardmäßig "Binary/ID" ist, sondern nur die ID
                        binaryId = new IdType("Binary", pdfBinaryUrl);
                    }

					Binary pdfBinary = this.daoRegistry.getResourceDao(Binary.class).read(binaryId, theRequestDetails);
					if (pdfBinary != null) {
						retVal.addParameter().setName("angereichertesPDF").setResource(pdfBinary);
						LOGGER.info("Angereichertes PDF (Binary/{}) zur Antwort hinzugefügt.", pdfBinary.getIdElement().getIdPart());
					} else {
						LOGGER.warn("Konnte angereichertes PDF (Binary mit URL {}) nicht laden. Wird nicht zur Antwort hinzugefügt.", pdfBinaryUrl);
					}
				} catch (Exception e) {
					LOGGER.error("Fehler beim Laden der angereicherten PDF-Binary von URL {}: {}", pdfBinaryUrl, e.getMessage(), e);
                    // Fehler hier nicht die ganze Operation abbrechen lassen, nur PDF nicht hinzufügen
				}
			} else {
				LOGGER.warn("Keine URL zu einem PDF-Attachment in der transformierten Rechnung gefunden. Angereichertes PDF kann nicht hinzugefügt werden.");
			}
		} else if (createEnrichedPdfRequested) {
            LOGGER.warn("Angereichertes PDF angefordert, aber keine transformierte Rechnung vorhanden. PDF wird nicht hinzugefügt.");
        }

		// 3. Warnungen (immer, außer im reinen Test-Modus oben)
		if (validationResult != null && validationResult.warnings != null && !validationResult.warnings.getIssue().isEmpty()) {
			retVal.addParameter().setName("warnungen").setResource(validationResult.warnings);
			LOGGER.info("Validierungswarnungen/-informationen wurden zur Antwort hinzugefügt.");
		} else {
						LOGGER.debug("Keine Validierungswarnungen/-informationen zum Hinzufügen zur Antwort (Normalmodus).");
        }

		// TODO: Protokollierung der Operation (ggf. in eigenem Service)
		if (validationResult != null && validationResult.transformedRechnung != null && validationResult.transformedRechnung.hasId() && !(modus != null && "test".equals(modus.getValueAsString()))) {
			try {
				// Annahme: patientId ist die Referenz auf den Patienten (Versicherten)
				Reference patientReference = new Reference(patientId.getValue()); 
				String actorId = accessToken.getTelematikId().orElse(accessToken.getIdNumber()); // Für LE Telematik-ID, sonst IDNumber

				AuditEvent auditEvent = auditService.createRestAuditEvent(
					AuditEvent.AuditEventAction.C, // C für Create
					"erechnung-submit", // Korrekter Subtype-Code
					AuditEvent.AuditEventOutcome._0, // Erfolg
					new Reference(validationResult.transformedRechnung.getIdElement().toVersionless()),
					"Erechnung Submission", // Resource Name
					validationResult.transformedRechnung.getIdElement().toVersionless().getValue(), // entityWhatDisplay
					"E-Rechnung mit ERG-Token '" + validationResult.transformedRechnung.getIdElement().getIdPart() + "' eingereicht durch Akteur '" + actorId + "' für Patient '" + patientId.getIdPart() + "'.",
					accessToken.getIdNumber(), // actorName (oder ein anderer passender Name aus dem Token)
					actorId, // actorId (Telematik-ID oder ID des Rechnungserstellers)
					patientReference // patientReference für Versicherter-Slice
				);

				// Füge spezifisches Detail für den Workflow-Status hinzu, falls das AuditEvent erfolgreich erstellt wurde
				if (auditEvent != null && auditEvent.hasId()) { // Sicherstellen, dass das Event existiert und gespeichert wurde (eine ID hat)
					// Annahme: Der initiale Workflow-Status nach dem Submit ist "OFFEN" oder ein Äquivalent.
					// Dieser Wert muss ggf. aus der Logik des RechnungProcessingService oder der DocumentReference selbst kommen.
					String workflowStatus = "OFFEN"; // Beispielwert, anpassen falls nötig!
					auditService.addEntityDetail(auditEvent, "workflow-status", workflowStatus);
				}

			} catch (Exception e) {
				LOGGER.error("Fehler beim Erstellen des AuditEvents für SubmitOperation: {}", e.getMessage(), e);
				// Die Hauptoperation sollte hierdurch nicht fehlschlagen
			}
		}

		LOGGER.info("Submit Operation erfolgreich beendet für Patient {}", patientId != null ? patientId.getIdPart() : "UNKNOWN");
		return retVal;
	}

}