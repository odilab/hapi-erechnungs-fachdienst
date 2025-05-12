package ca.uhn.fhir.jpa.starter.custom.operation.retrieve;

import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service für die Berechtigungsprüfung
 */
@Service
public class AuthorizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthorizationService.class);

    private final DocumentRetrievalService documentRetrievalService;

    @Autowired
    public AuthorizationService(DocumentRetrievalService documentRetrievalService) {
        this.documentRetrievalService = documentRetrievalService;
    }

    /**
     * Extrahiert und validiert den AccessToken aus den RequestDetails
     */
    public AccessToken validateAndExtractAccessToken(RequestDetails requestDetails) {
        Object tokenObj = requestDetails.getUserData().get("ACCESS_TOKEN");
        if (!(tokenObj instanceof AccessToken)) {
            throw new AuthenticationException("Kein gültiger Access Token gefunden");
        }
        
        return (AccessToken) tokenObj;
    }

    /**
     * Validiert die Autorisierung des Nutzers basierend auf seiner Profession und dem Scope
     */
    public void validateAuthorization(AccessToken accessToken, RequestDetails requestDetails) {
        Profession profession = accessToken.getProfession();
        
        if (profession == null) {
            throw new ForbiddenOperationException("Keine Profession im Access Token gefunden");
        }

        // Prüfe, ob der Nutzer den erforderlichen Scope hat
        String scope = accessToken.getScope();
        if (scope == null || (!scope.equals("invoiceDoc.r") && !scope.equals("openid e-rezept"))) {
            throw new ForbiddenOperationException("Fehlender Scope: Erforderlich ist invoiceDoc.r oder openid e-rezept");
        }

        // Prüfe, ob die ID-Nummer vorhanden ist
        if (accessToken.getIdNumber() == null || accessToken.getIdNumber().isEmpty()) {
            throw new ForbiddenOperationException("Keine ID-Nummer im Access Token gefunden");
        }

        // Nur Versicherte und Kostenträger sind erlaubt
        switch (profession) {
            case VERSICHERTER:
                // Für Versicherte muss die KVNR vorhanden sein
                if (accessToken.getKvnr().isEmpty()) {
                    throw new ForbiddenOperationException("Keine KVNR für Versicherten gefunden");
                }
                break;
            case KOSTENTRAEGER:
                // Für Kostenträger muss die Telematik-ID vorhanden sein
                if (accessToken.getTelematikId().isEmpty()) {
                    throw new ForbiddenOperationException("Keine Telematik-ID für Kostenträger gefunden");
                }
                break;
            default:
                throw new ForbiddenOperationException("Für diese Operation sind nur Versicherte und Kostenträger zugelassen. Aktuelle Profession: " + profession);
        }
    }

    /**
     * Prüft, ob der Nutzer berechtigt ist, auf das Dokument zuzugreifen
     */
    public void validateDocumentAccess(DocumentReference document, AccessToken accessToken) {
        // Prüfe, ob das Dokument einen Patienten-Bezug hat
        if (document.getSubject() == null || document.getSubject().isEmpty() || !document.getSubject().hasReference()) {
            throw new UnprocessableEntityException("Dokument hat keinen oder keinen gültigen Patienten-Bezug (Reference)");
        }

        // Lade die Patientenressource
        Patient patientResource = documentRetrievalService.loadPatientResource(document);
        if (patientResource == null) {
            throw new UnprocessableEntityException("Zugehörige Patientenressource nicht gefunden oder nicht auflösbar");
        }

        // Extrahiere die KVNR des Patienten
        String patientKvnr = documentRetrievalService.extractKvnrFromPatient(patientResource);
        if (patientKvnr == null) {
            throw new UnprocessableEntityException("Dokument bzw. zugehöriger Patient hat keine gültige KVNR (System: http://fhir.de/sid/gkv/kvid-10)");
        }

        // Prüfe die Berechtigung je nach Profession
        Profession profession = accessToken.getProfession();
        
        switch (profession) {
            case VERSICHERTER:
                // Versicherte dürfen nur ihre eigenen Dokumente abrufen
                String userKvnr = accessToken.getKvnr().orElse("");
                LOGGER.info("Patienten-KVNR im Dokument: {}", patientKvnr);
                LOGGER.info("Benutzer-KVNR im Token: {}", userKvnr);
                if (!patientKvnr.equals(userKvnr)) {
                    throw new ForbiddenOperationException("Versicherte dürfen nur ihre eigenen Dokumente abrufen");
                }
                break;
            case KOSTENTRAEGER:
                // Hier könnte eine zusätzliche Prüfung erfolgen, ob der Kostenträger für diesen Patienten zuständig ist
                // Diese Logik müsste in einer realen Implementierung ergänzt werden
                break;
            default:
                throw new ForbiddenOperationException("Ungültige Profession: " + profession);
        }
    }
} 