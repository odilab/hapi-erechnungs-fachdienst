package ca.uhn.fhir.jpa.starter.custom.operation.retrieve;

import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Provider für die $retrieve Operation
 * Ermöglicht den Abruf von Rechnungsdokumenten für berechtigte Nutzer
 */
@Component
public class RetrieveOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrieveOperationProvider.class);

    private final DocumentRetrievalService documentRetrievalService;
    private final AuthorizationService authorizationService;
    private final DocumentProcessorService documentProcessorService;
    private final CustomValidator validator;

    @Autowired
    public RetrieveOperationProvider(
            DocumentRetrievalService documentRetrievalService,
            AuthorizationService authorizationService,
            DocumentProcessorService documentProcessorService,
            CustomValidator validator) {
        this.documentRetrievalService = documentRetrievalService;
        this.authorizationService = authorizationService;
        this.documentProcessorService = documentProcessorService;
        this.validator = validator;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    /**
     * Implementierung der $retrieve Operation gemäß der Spezifikation
     * 
     * @param token Das Dokumenttoken zur Identifikation des abzurufenden Dokuments
     * @param returnAngereichertesPDF Steuert, ob das angereicherte PDF zurückgegeben wird
     * @param returnStrukturierteDaten Steuert, ob die strukturierten Rechnungsinhalte zurückgegeben werden
     * @param returnOriginalPDF Steuert, ob das originale PDF zurückgegeben wird
     * @param returnSignatur Steuert, ob die Signatur zurückgegeben wird
     * @param theRequestDetails Request-Details mit Zugriff auf den AccessToken
     * @return Parameters-Objekt mit den DocumentReferences
     */
    @Operation(name = "$retrieve", idempotent = true)
    public Parameters retrieveOperation(
            @OperationParam(name = "token") StringType token,
            @OperationParam(name = "returnAngereichertesPDF") BooleanType returnAngereichertesPDF,
            @OperationParam(name = "returnStrukturierteDaten") BooleanType returnStrukturierteDaten,
            @OperationParam(name = "returnOriginalPDF") BooleanType returnOriginalPDF,
            @OperationParam(name = "returnSignatur") BooleanType returnSignatur,
            RequestDetails theRequestDetails
    ) {
        LOGGER.info("Retrieve Operation gestartet für Token {}", token != null ? token.getValue() : "null");
        logRequestParameters(returnAngereichertesPDF, returnStrukturierteDaten, returnOriginalPDF, returnSignatur);

        // Validiere Token-Parameter
        validateTokenParameter(token);

        // Extrahiere und validiere den AccessToken
        AccessToken accessToken = authorizationService.validateAndExtractAccessToken(theRequestDetails);
        
        // Prüfe die Berechtigung des Nutzers
        authorizationService.validateAuthorization(accessToken, theRequestDetails);

        // Suche das Dokument anhand des Tokens
        DocumentReference document = documentRetrievalService.findDocumentByToken(token.getValue());
        if (document == null) {
            throw new ResourceNotFoundException("Kein Dokument mit Token " + token.getValue() + " gefunden");
        }

        // Prüfe, ob der Nutzer berechtigt ist, auf dieses Dokument zuzugreifen
        authorizationService.validateDocumentAccess(document, accessToken);

        // Verarbeite das Dokument entsprechend der Parameter
        Parameters response = documentProcessorService.buildRetrieveResponse(
            document,
            getBooleanValue(returnAngereichertesPDF),
            getBooleanValue(returnStrukturierteDaten),
            getBooleanValue(returnOriginalPDF),
            getBooleanValue(returnSignatur),
            accessToken,
            theRequestDetails
        );

        LOGGER.info("Retrieve Operation erfolgreich beendet für Token {}", token.getValue());
        return response;
    }

    /**
     * Loggt die empfangenen Request-Parameter
     */
    private void logRequestParameters(BooleanType returnAngereichertesPDF, BooleanType returnStrukturierteDaten, 
                                     BooleanType returnOriginalPDF, BooleanType returnSignatur) {
        LOGGER.info("RetrieveOperation: Empfangene Parameter - returnAngereichertesPDF: {}, returnStrukturierteDaten: {}, returnOriginalPDF: {}, returnSignatur: {}",
            returnAngereichertesPDF != null ? returnAngereichertesPDF.getValueAsString() : "null",
            returnStrukturierteDaten != null ? returnStrukturierteDaten.getValueAsString() : "null",
            returnOriginalPDF != null ? returnOriginalPDF.getValueAsString() : "null",
            returnSignatur != null ? returnSignatur.getValueAsString() : "null"
        );
    }

    /**
     * Validiert den Token-Parameter
     */
    private void validateTokenParameter(StringType token) {
        if (token == null || token.getValue() == null || token.getValue().isEmpty()) {
            throw new UnprocessableEntityException("Token darf nicht leer sein");
        }
    }

    /**
     * Extrahiert den Boolean-Wert aus einem BooleanType-Parameter, behandelt null-Werte
     */
    private boolean getBooleanValue(BooleanType booleanType) {
        return booleanType != null && booleanType.getValue();
    }
} 