package ca.uhn.fhir.jpa.starter.custom.operation.submit;


import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
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
import ca.uhn.fhir.jpa.starter.custom.operation.submit.validation.RechnungValidator;

@Component
public class SubmitOperationProvider implements IResourceProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger(SubmitOperationProvider.class);

	private final SubmitAuthorizationService authorizationService;
	private final SubmitValidationService validationService;
	private final DocumentProcessorService documentProcessorService;
	private final RechnungValidator rechnungValidator;

	@Autowired
	public SubmitOperationProvider(SubmitAuthorizationService authorizationService,
									SubmitValidationService validationService,
									DocumentProcessorService documentProcessorService,
									RechnungValidator rechnungValidator) {
		this.authorizationService = authorizationService;
		this.validationService = validationService;
		this.documentProcessorService = documentProcessorService;
		this.rechnungValidator = rechnungValidator;
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
		AccessToken accessToken = authorizationService.authorizeRequest(theRequestDetails);
		LOGGER.debug("Authorization successful for user with profession: {}", accessToken.getProfession());

		// 2. FHIR-Validierung, ggf. Speicherung & Transformation über den neuen Validator
		RechnungValidator.ValidationAndTransformResult validationResult = rechnungValidator.validate(rechnung, modus, accessToken);

		// // 3. Geschäftsregelvalidierung (inkl. Token-Prüfung, Größe etc.)
		// validationService.validateBusinessRules(rechnung, anhaenge, modus);
		// LOGGER.debug("Business rule validation successful.");

		// // 4. Test-Modus prüfen
		// if (modus != null && "test".equals(modus.getValue())) {
		// 	LOGGER.info("Test-Modus: Nur Validierung durchgeführt.");
		// 	Parameters response = new Parameters();
		// 	response.addParameter().setName("status").setValue(new StringType("valid"));
		// 	return response;
		// }

		// 5. Verarbeitung & Speicherung
		Parameters retVal = new Parameters();
		// boolean createEnrichedPdf = angereichertesPDF != null && angereichertesPDF.getValue();

		// // Verarbeite die Rechnung
		// LOGGER.info("Processing main invoice...");
		// DocumentProcessorService.TokenResult rechnungResult = documentProcessorService.processDocument(rechnung, createEnrichedPdf, patientId);
		// addTokenResultToParameters(retVal, rechnungResult, "rechnung");
		// LOGGER.info("Main invoice processed. Token: {}", rechnungResult.token);

		// // Verarbeite Anhänge
		// if (anhaenge != null && !anhaenge.isEmpty()) {
		// 	LOGGER.info("Processing {} attachments...", anhaenge.size());
		// 	for (int i = 0; i < anhaenge.size(); i++) {
		// 		DocumentReference anhang = anhaenge.get(i);
		// 		LOGGER.debug("Processing attachment {}...", i + 1);
		// 		// Anhänge werden nie angereichert, PatientId ist irrelevant
		// 		DocumentProcessorService.TokenResult anhangResult = documentProcessorService.processDocument(anhang, false, null);
		// 		addTokenResultToParameters(retVal, anhangResult, "anhang");
		// 		LOGGER.debug("Attachment {} processed. Token: {}", i + 1, anhangResult.token);
		// 	}
		// 	LOGGER.info("All attachments processed.");
		// }

		// Füge die erhaltenen Warnungen/Infos zur Response hinzu
		if (validationResult != null && validationResult.warnings != null && !validationResult.warnings.getIssue().isEmpty()) {
			retVal.addParameter().setName("warnungen").setResource(validationResult.warnings);
			LOGGER.info("Validierungswarnungen/-informationen wurden zur Antwort hinzugefügt.");
		} else {
             LOGGER.debug("Keine Validierungswarnungen/-informationen zum Hinzufügen zur Antwort.");
        }

        // Füge die transformierte Rechnung zur Response hinzu, falls vorhanden
        if (validationResult != null && validationResult.transformedRechnung != null) {
            retVal.addParameter().setName("transformedRechnung").setResource(validationResult.transformedRechnung);
            LOGGER.info("Transformierte Rechnung (ID: {}) wurde zur Antwort hinzugefügt.", 
                        validationResult.transformedRechnung.hasId() ? validationResult.transformedRechnung.getIdElement().getValue() : "unbekannt");
        } else {
            LOGGER.debug("Keine transformierte Rechnung zum Hinzufügen zur Antwort.");
        }

		// TODO: Protokollierung der Operation (ggf. in eigenem Service)
		// logSubmitOperation(savedRechnung, rechnungToken.token, accessToken);

		LOGGER.info("Submit Operation erfolgreich beendet für Patient {}", patientId != null ? patientId.getIdPart() : "UNKNOWN");
		return retVal;
	}

	// private void addTokenResultToParameters(Parameters parameters, DocumentProcessorService.TokenResult result, String documentType) {
	// 	Parameters.ParametersParameterComponent tokenParam = parameters.addParameter().setName("token"); // Gemäß Spec immer 'token'
	// 	tokenParam.addPart().setName("id").setValue(new StringType(result.token));
	// 	tokenParam.addPart().setName("docRef").setValue(result.identifier); // Verwende den generierten Identifier

	// 	// Füge das angereicherte PDF hinzu, falls vorhanden (nur für Hauptrechnung möglich)
	// 	if (result.enrichedPdf != null) {
	// 		tokenParam.addPart().setName("angereichertesPDF").setResource(result.enrichedPdf);
	// 		LOGGER.debug("Enriched PDF added to response for {} token {}", documentType, result.token);
	// 	}
	// }
}