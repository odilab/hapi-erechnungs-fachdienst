package ca.uhn.fhir.jpa.starter.custom.operation;

import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
import ca.uhn.fhir.jpa.starter.custom.operation.retrieve.DocumentRetrievalService;
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
 * Service für die Berechtigungsprüfung für verschiedene FHIR-Operationen.
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
     * Extrahiert und validiert den AccessToken aus den RequestDetails.
     * Wirft eine AuthenticationException, wenn kein gültiger Token gefunden wird.
     */
    public AccessToken validateAndExtractAccessToken(RequestDetails requestDetails) {
        Object tokenObj = requestDetails.getUserData().get("ACCESS_TOKEN");
        if (!(tokenObj instanceof AccessToken)) {
            LOGGER.warn("Kein AccessToken Objekt in RequestDetails gefunden.");
            throw new AuthenticationException("Kein gültiger Access Token gefunden");
        }
        AccessToken accessToken = (AccessToken) tokenObj;
        LOGGER.debug("AccessToken extrahiert für User ID: {}", accessToken.getIdNumber()); // Log ggf. anpassen, falls ID vertraulich ist
        return accessToken;
    }

    /**
     * Authorisiert eine Anfrage basierend auf der Profession und dem Scope im AccessToken.
     * Diese Methode ist für allgemeinere Zugriffsprüfungen gedacht (z.B. für Retrieve- oder Process-Operationen).
     * Wirft ForbiddenOperationException bei unzureichender Berechtigung.
     */
    public void authorizeAccessBasedOnContext(AccessToken accessToken, RequestDetails requestDetails) {
        Profession profession = accessToken.getProfession();
        
        if (profession == null) {
            LOGGER.warn("Keine Profession im Access Token gefunden für User ID: {}", accessToken.getIdNumber());
            throw new ForbiddenOperationException("Keine Profession im Access Token gefunden");
        }
        LOGGER.debug("Prüfe Berechtigung für Profession: {}", profession);

        // Prüfe, ob der Nutzer den erforderlichen Scope hat (Beispiel: invoiceDoc.r)
        String scope = accessToken.getScope();
        // TODO: Scope-Prüfung spezifischer für den Kontext gestalten, ggf. erwarteten Scope übergeben?
        // Aktuelle Prüfung lässt 'invoiceDoc.r' oder 'openid e-rezept' zu.
        if (scope == null || (!scope.contains("invoiceDoc.r") && !scope.contains("openid e-rezept"))) {
             LOGGER.warn("Fehlender oder ungültiger Scope '{}' für User ID: {}", scope, accessToken.getIdNumber());
            throw new ForbiddenOperationException("Fehlender oder ungültiger Scope: Erforderlich ist z.B. 'invoiceDoc.r' oder 'openid e-rezept'");
        }
        LOGGER.debug("Scope '{}' ist vorhanden.", scope);


        // Prüfe, ob die ID-Nummer vorhanden ist
        if (accessToken.getIdNumber() == null || accessToken.getIdNumber().isEmpty()) {
             LOGGER.warn("Keine ID-Nummer im Access Token gefunden.");
            throw new ForbiddenOperationException("Keine ID-Nummer im Access Token gefunden");
        }

        // Beispielhafte Prüfung basierend auf Profession (kann je nach Operation variieren)
        switch (profession) {
            case VERSICHERTER:
                if (accessToken.getKvnr().isEmpty()) {
                     LOGGER.warn("Keine KVNR für Versicherten gefunden (ID: {}).", accessToken.getIdNumber());
                    throw new ForbiddenOperationException("Keine KVNR für Versicherten gefunden");
                }
                 LOGGER.debug("Versicherter mit KVNR validiert.");
                break;
            case KOSTENTRAEGER:
                if (accessToken.getTelematikId().isEmpty()) {
                     LOGGER.warn("Keine Telematik-ID für Kostenträger gefunden (ID: {}).", accessToken.getIdNumber());
                    throw new ForbiddenOperationException("Keine Telematik-ID für Kostenträger gefunden");
                }
                 LOGGER.debug("Kostenträger mit Telematik-ID validiert.");
                break;
            // Füge hier ggf. weitere erlaubte Professionen für diesen Kontext hinzu
            default:
                LOGGER.warn("Nicht autorisierte Profession '{}' für diesen Kontext (ID: {}).", profession, accessToken.getIdNumber());
                throw new ForbiddenOperationException("Für diese Operation sind nur Versicherte und Kostenträger zugelassen. Aktuelle Profession: " + profession);
        }
    }

    /**
     * Prüft, ob der Nutzer (basierend auf dem AccessToken) berechtigt ist, auf das spezifische FHIR-Dokument zuzugreifen.
     * Wirft ForbiddenOperationException oder UnprocessableEntityException bei Problemen.
     */
    public void validateDocumentAccess(DocumentReference document, AccessToken accessToken) {
        // Prüfe, ob das Dokument einen Patienten-Bezug hat
        if (document.getSubject() == null || !document.getSubject().hasReference()) {
            LOGGER.warn("Dokument {} hat keinen gültigen Patienten-Bezug (Subject).", document.getIdElement().getIdPart());
            throw new UnprocessableEntityException("Dokument hat keinen oder keinen gültigen Patienten-Bezug (Reference)");
        }
        String patientReference = document.getSubject().getReference();
        LOGGER.debug("Prüfe Zugriff auf Dokument {} für Patient {}", document.getIdElement().getIdPart(), patientReference);


        // Lade die Patientenressource
        Patient patientResource = documentRetrievalService.loadPatientResource(document);
        if (patientResource == null) {
            LOGGER.warn("Zugehörige Patientenressource für Referenz {} nicht gefunden.", patientReference);
            throw new UnprocessableEntityException("Zugehörige Patientenressource nicht gefunden oder nicht auflösbar");
        }

        // Extrahiere die KVNR des Patienten
        String patientKvnr = documentRetrievalService.extractKvnrFromPatient(patientResource);
        if (patientKvnr == null) {
             LOGGER.warn("Keine gültige KVNR im Patientenprofil {} gefunden.", patientResource.getIdElement().getIdPart());
            throw new UnprocessableEntityException("Dokument bzw. zugehöriger Patient hat keine gültige KVNR (System: http://fhir.de/sid/gkv/kvid-10)");
        }
        LOGGER.debug("Patienten-KVNR extrahiert: {}", patientKvnr);

        // Prüfe die Berechtigung je nach Profession
        Profession profession = accessToken.getProfession();
        LOGGER.debug("Prüfe Dokumentenzugriff für Profession: {}", profession);
        
        switch (profession) {
            case VERSICHERTER:
                // Versicherte dürfen nur ihre eigenen Dokumente abrufen
                String userKvnr = accessToken.getKvnr().orElse("");
                LOGGER.debug("Vergleiche Patienten-KVNR ({}) mit Benutzer-KVNR ({}).", patientKvnr, userKvnr);
                if (!patientKvnr.equals(userKvnr)) {
                     LOGGER.warn("Zugriffsverletzung: Versicherter (KVNR: {}) versucht auf Dokument von Patient (KVNR: {}) zuzugreifen.", userKvnr, patientKvnr);
                    throw new ForbiddenOperationException("Versicherte dürfen nur ihre eigenen Dokumente abrufen");
                }
                LOGGER.info("Zugriff für Versicherten auf eigenes Dokument gewährt.");
                break;
            case KOSTENTRAEGER:
                 LOGGER.debug("Kostenträger-Zugriff auf Dokument von Patient (KVNR: {}) wird geprüft.", patientKvnr);
                // Hier könnte eine zusätzliche Prüfung erfolgen, ob der Kostenträger für diesen Patienten zuständig ist
                // Diese Logik müsste in einer realen Implementierung ergänzt werden (z.B. über Versichertenstammdaten)
                 LOGGER.info("Zugriff für Kostenträger auf Dokument von Patient (KVNR: {}) vorläufig gewährt (keine detaillierte Zuständigkeitsprüfung implementiert).", patientKvnr);
                break;
            default:
                 LOGGER.warn("Unbehandelte Profession '{}' beim Versuch des Dokumentenzugriffs.", profession);
                throw new ForbiddenOperationException("Ungültige Profession für Dokumentenzugriff: " + profession);
        }
    }

     /**
     * Authorisiert eine Anfrage für die Submit-Operation ($erechnung-submit).
     * Prüft, ob die Profession Leistungserbringer oder Arzt/Krankenhaus ist und der Scope 'invoiceDoc.c' oder 'openid e-rezept' vorhanden ist.
     * Wirft AuthenticationException oder ForbiddenOperationException bei unzureichender Berechtigung.
     *
     * @param requestDetails Die RequestDetails, die den AccessToken enthalten.
     * @return Den validierten AccessToken bei erfolgreicher Autorisierung.
     */
    public AccessToken authorizeSubmitOperation(RequestDetails requestDetails) {
        AccessToken accessToken = validateAndExtractAccessToken(requestDetails); // Nutzt die gemeinsame Extraktionsmethode
        Profession profession = accessToken.getProfession();

         LOGGER.debug("Prüfe Berechtigung für Submit-Operation für Profession: {}", profession);

        if (profession == null || (profession != Profession.LEISTUNGSERBRINGER && profession != Profession.ARZT_KRANKENHAUS)) {
             LOGGER.warn("Unzureichende Berechtigung für Submit-Operation. Profession: {}", profession);
            throw new AuthenticationException("Keine ausreichende Berechtigung für die Submit-Operation. Nur Leistungserbringer und Ärzte im Krankenhaus dürfen Rechnungen einreichen.");
        }

        // Scope-Prüfung gemäß A_26029
        String scope = accessToken.getScope();
         LOGGER.debug("Prüfe Scope '{}' für Submit-Operation.", scope);
        if (scope == null || (!scope.contains("invoiceDoc.c") && !scope.contains("openid e-rezept"))) {
             LOGGER.warn("Fehlender Scope für Submit-Operation: '{}'", scope);
            throw new ForbiddenOperationException("Fehlender Scope: Erforderlich ist 'invoiceDoc.c' oder 'openid e-rezept'");
        }

         LOGGER.info("Submit-Operation erfolgreich autorisiert für Profession: {}", profession);
        return accessToken;
    }
} 