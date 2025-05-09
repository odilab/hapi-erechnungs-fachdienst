package ca.uhn.fhir.jpa.starter.custom.operation.retrieve;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessTokenService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.Profession;
//import ca.uhn.fhir.jpa.starter.custom.interceptor.audit.AuditService;
import ca.uhn.fhir.jpa.starter.custom.interceptor.CustomValidator;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.context.FhirContext;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class RetrieveOperationProvider implements IResourceProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrieveOperationProvider.class);

    @Autowired
    private DaoRegistry daoRegistry;

    @Autowired
    private CustomValidator validator;

    @Autowired
    private AccessTokenService accessTokenService;

    // @Autowired
    // private AuditService auditService;

    private final FhirContext ctx;

    @Autowired
    public RetrieveOperationProvider(FhirContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Class<DocumentReference> getResourceType() {
        return DocumentReference.class;
    }

    /**
     * Implementierung der $retrieve Operation gemäß der Spezifikation
     * 
     * @param token Das Dokumenttoken zur Identifikation des abzurufenden Dokuments
     * @param strukturierterRechnungsinhalt Steuert, ob die strukturierten Rechnungsinhalte zurückgegeben werden
     * @param originaleRechnung Steuert, ob die originale Rechnung inkl. Signatur zurückgegeben wird
     * @param theRequestDetails Request-Details mit Zugriff auf den AccessToken
     * @return Parameters-Objekt mit dem DocumentReference
     */
    @Operation(name = "$retrieve", idempotent = true)
    public Parameters retrieveOperation(
            @OperationParam(name = "token") StringType token,
            @OperationParam(name = "strukturierterRechnungsinhalt") BooleanType strukturierterRechnungsinhalt,
            @OperationParam(name = "originaleRechnung") BooleanType originaleRechnung,
            RequestDetails theRequestDetails
    ) {
        LOGGER.info("Retrieve Operation gestartet für Token {}", token != null ? token.getValue() : "null");

        // Validiere Token-Parameter
        if (token == null || token.getValue() == null || token.getValue().isEmpty()) {
            throw new UnprocessableEntityException("Token darf nicht leer sein");
        }

        // Berechtigungsprüfung
        Object tokenObj = theRequestDetails.getUserData().get("ACCESS_TOKEN");
        if (!(tokenObj instanceof AccessToken)) {
            throw new AuthenticationException("Kein gültiger Access Token gefunden");
        }
        
        AccessToken accessToken = (AccessToken) tokenObj;
        
        // Prüfe, ob der Nutzer die erforderliche Profession und Scope hat
        validateAuthorization(accessToken, theRequestDetails);

        // Suche das Dokument anhand des Tokens
        DocumentReference document = findDocumentByToken(token.getValue());
        
        // Wenn kein Dokument gefunden wurde, wirf eine ResourceNotFoundException
        if (document == null) {
            throw new ResourceNotFoundException("Kein Dokument mit Token " + token.getValue() + " gefunden");
        }

        // Prüfe, ob der Nutzer berechtigt ist, auf dieses Dokument zuzugreifen
        validateDocumentAccess(document, accessToken);

        // Verarbeite das Dokument entsprechend der Parameter
        DocumentReference processedDocument = processDocument(
            document, 
            strukturierterRechnungsinhalt != null && strukturierterRechnungsinhalt.getValue(),
            originaleRechnung != null && originaleRechnung.getValue(),
            accessToken
        );

        // Validiere das Ergebnis-Dokument
        validateResultDocument(processedDocument);

        // Erstelle die Antwort
        Parameters retVal = new Parameters();
        retVal.addParameter().setName("dokument").setResource(processedDocument);

        // Protokolliere den Vorgang
        // logRetrieveOperation(document, accessToken, strukturierterRechnungsinhalt != null && strukturierterRechnungsinhalt.getValue());

        LOGGER.info("Retrieve Operation erfolgreich beendet für Token {}", token.getValue());
        return retVal;
    }

    /**
     * Validiert die Autorisierung des Nutzers basierend auf seiner Profession und dem Scope
     */
    private void validateAuthorization(AccessToken accessToken, RequestDetails theRequestDetails) {
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
     * Sucht ein Dokument anhand des Tokens
     */
    private DocumentReference findDocumentByToken(String token) {
        LOGGER.info("Suche Dokument mit Token: {}", token);

        // VERSUCH 1: Direkter Lesezugriff über ID (wenn der Token die logische ID ist)
        try {
            IdType docId = new IdType("DocumentReference", token);
            DocumentReference document = daoRegistry.getResourceDao(DocumentReference.class).read(docId);
            if (document != null) {
                LOGGER.info("Dokument direkt via ID (Token) {} gefunden.", token);
                // Logge das vollständige abgerufene Dokument als JSON
                String documentAsJson = ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(document);
                LOGGER.info("Vollständiger Inhalt der via ID (Token) {} abgerufenen DocumentReference:\n{}", token, documentAsJson);
                return document;
            }
        } catch (ResourceNotFoundException e) {
            LOGGER.info("Dokument mit ID (Token) {} nicht gefunden (ResourceNotFoundException), versuche Identifier-Suche.", token);
        } catch (Exception e) {
            // Breitere Exception-Abdeckung für unerwartete Fehler beim direkten Lesen
            LOGGER.warn("Unerwarteter Fehler beim direkten Lesen der DocumentReference mit ID (Token) {}: {}. Versuche Identifier-Suche.", token, e.getMessage(), e);
        }
        
        // VERSUCH 2: Suche über Identifier mit spezifischem System
        LOGGER.info("Versuche Suche via Identifier mit spezifischem System: https://gematik.de/fhir/sid/erg-token und Wert: {}", token);
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, 
            new TokenParam("https://gematik.de/fhir/sid/erg-token", token));
        
        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class)
            .search(paramMap);
        
        if (results.size() > 0) {
            LOGGER.info("Dokument mit spezifischem Identifier-System und Token-Wert gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }
        
        LOGGER.info("Kein Dokument mit spezifischem Identifier-System (https://gematik.de/fhir/sid/erg-token) und Wert {} gefunden.", token);
        
        // VERSUCH 3: Suche über Identifier ohne Systemangabe
        LOGGER.info("Versuche Suche via Identifier ohne spezifisches System, nur mit Wert: {}", token);
        paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, new TokenParam(null, token));
        
        results = daoRegistry.getResourceDao(DocumentReference.class)
            .search(paramMap);
        
        if (results.size() > 0) {
            LOGGER.info("Dokument mit Identifier (nur Wert) gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }
        
        LOGGER.info("Kein Dokument mit Identifier (nur Wert) {} gefunden.", token);
        
        // VERSUCH 4: Fallback - Durchsuche alle Dokumente und filtere manuell (sollte selten nötig sein)
        // Diese Methode ist potenziell ineffizient und sollte nur als letzter Ausweg dienen.
        LOGGER.warn("Letzter Versuch: Durchsuche ALLE DocumentReference-Ressourcen und filtere manuell nach dem Token '{}' im Identifier. Dies kann bei vielen Ressourcen langsam sein.", token);
        paramMap = new SearchParameterMap(); // Leere Map, um alle Ressourcen zu laden
        results = daoRegistry.getResourceDao(DocumentReference.class)
            .search(paramMap);
        
        LOGGER.info("Gesamtzahl der DocumentReference-Ressourcen für manuelles Filtern: {}", results.size());
        
        if (results.isEmpty() == false && results.size() > 0) { // Sicherstellen, dass size() aufgerufen werden kann
            List<IBaseResource> allDocs = results.getResources(0, results.size()); // Vorsicht: results.size() kann null zurückgeben, wenn keine Ressourcen gefunden wurden.
            for (IBaseResource res : allDocs) {
                DocumentReference doc = (DocumentReference) res;
                for (Identifier identifier : doc.getIdentifier()) {
                    // Detailliertere Log-Ausgabe für die manuelle Suche
                    // LOGGER.debug("Manuelle Prüfung: Doc ID '{}', Identifier System='{}', Wert='{}'",
                    // doc.getIdElement().getIdPart(), identifier.getSystem(), identifier.getValue());
                    if (token.equals(identifier.getValue())) {
                        LOGGER.info("Dokument mit passendem Token-Wert '{}' im Identifier bei manueller Iteration gefunden. DocumentReference ID: {}", token, doc.getIdElement().toUnqualifiedVersionless().getValue());
                        return doc;
                    }
                }
            }
        }
        
        LOGGER.error("Kein Dokument mit Token {} nach allen Suchstrategien gefunden (direkter Zugriff, spezifischer Identifier, unspezifischer Identifier, manuelle Iteration).", token);
        return null;
    }

    /**
     * Prüft, ob der Nutzer berechtigt ist, auf das Dokument zuzugreifen
     */
    private void validateDocumentAccess(DocumentReference document, AccessToken accessToken) {
        // Prüfe, ob das Dokument einen Patienten-Bezug hat
        if (document.getSubject() == null || document.getSubject().isEmpty() || !document.getSubject().hasReference()) {
            throw new UnprocessableEntityException("Dokument hat keinen oder keinen gültigen Patienten-Bezug (Reference)");
        }

        String patientReferenceString = document.getSubject().getReference();
        LOGGER.info("Patientenreferenz aus DocumentReference: {}", patientReferenceString);

        // Lade die Patientenressource
        Patient patientResource = null;
        try {
            // Stelle sicher, dass die Referenz eine ID enthält (z.B. "Patient/123")
            IdType patientId = new IdType(patientReferenceString);
            if (!patientId.hasResourceType() || !"Patient".equals(patientId.getResourceType()) || !patientId.hasIdPart()) {
                throw new UnprocessableEntityException("Ungültige Patientenreferenz in DocumentReference: " + patientReferenceString);
            }
            patientResource = daoRegistry.getResourceDao(Patient.class).read(patientId);
            if (patientResource == null) {
                throw new ResourceNotFoundException("Patientenressource mit ID " + patientId.getIdPart() + " nicht gefunden.");
            }
            LOGGER.info("Patientenressource {} erfolgreich geladen.", patientId.toUnqualifiedVersionless().getValue());
        } catch (ResourceNotFoundException e) {
            LOGGER.error("Patientenressource, auf die in DocumentReference.subject verwiesen wird, konnte nicht gefunden werden: {}", patientReferenceString, e);
            throw new UnprocessableEntityException("Zugehörige Patientenressource nicht gefunden: " + patientReferenceString, e);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Laden der Patientenressource (Referenz: {}): {}", patientReferenceString, e.getMessage(), e);
            throw new UnprocessableEntityException("Fehler beim Auflösen der Patientenreferenz: " + patientReferenceString, e);
        }

        // Extrahiere die KVNR des Patienten aus der geladenen Patientenressource
        String patientKvnr = null;
        if (patientResource.hasIdentifier()) {
            for (Identifier identifier : patientResource.getIdentifier()) {
                if ("http://fhir.de/sid/gkv/kvid-10".equals(identifier.getSystem()) && identifier.hasValue()) {
                    patientKvnr = identifier.getValue();
                    LOGGER.info("KVNR '{}' aus geladener Patientenressource extrahiert.", patientKvnr);
                    break;
                }
            }
        }

        if (patientKvnr == null) {
            // Logge die Identifier des Patienten, um das Debugging zu erleichtern
            // Stelle sicher, dass patientResource für den Lambda-Ausdruck effectively final ist.
            final Patient finalPatientResource = patientResource; 
            if (finalPatientResource.hasIdentifier()) {
                finalPatientResource.getIdentifier().forEach(id -> 
                    LOGGER.warn("Gefundener Identifier im Patienten {}: System='{}', Wert='{}'", 
                                finalPatientResource.getIdElement().toUnqualifiedVersionless().getValue(), 
                                id.getSystem(), 
                                id.getValue()));
            } else {
                LOGGER.warn("Geladene Patientenressource {} hat keine Identifier.", finalPatientResource.getIdElement().toUnqualifiedVersionless().getValue());
            }
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

    /**
     * Verarbeitet das Dokument entsprechend der Parameter
     */
    private DocumentReference processDocument(
            DocumentReference document,
            boolean includeStructuredContent,
            boolean includeOriginalInvoice,
            AccessToken accessToken
    ) {
        // Logge das ankommende Dokument, um seinen Zustand zu verstehen
        LOGGER.info("processDocument - Beginn. Ankommendes Dokument (ID: {}). IncludeStructuredContent: {}, IncludeOriginalInvoice: {}",
                    document.getIdElement().toVersionless().getValue(), includeStructuredContent, includeOriginalInvoice);
        if (document.hasContent()) {
            LOGGER.info("Ankommendes Dokument hat {} Content-Elemente:", document.getContent().size());
            for (int i = 0; i < document.getContent().size(); i++) {
                DocumentReference.DocumentReferenceContentComponent c = document.getContent().get(i);
                String contentType = c.hasAttachment() && c.getAttachment().hasContentType() ? c.getAttachment().getContentType() : "N/A";
                String formatSystem = c.hasFormat() && c.getFormat().hasSystem() ? c.getFormat().getSystem() : "N/A";
                String formatCode = c.hasFormat() && c.getFormat().hasCode() ? c.getFormat().getCode() : "N/A";
                String url = c.hasAttachment() && c.getAttachment().hasUrl() ? c.getAttachment().getUrl() : "N/A (keine URL)";
                String data = c.hasAttachment() && c.getAttachment().hasData() ? "Ja (" + c.getAttachment().getData().length + " Bytes)" : "Nein";
                LOGGER.info("  Content [{}]: Type='{}', Format.System='{}', Format.Code='{}', URL='{}', Data vorhanden='{}'",
                            i, contentType, formatSystem, formatCode, url, data);
            }
        } else {
            LOGGER.warn("processDocument - Ankommendes Dokument (ID: {}) hat KEINE Content-Elemente.", document.getIdElement().toVersionless().getValue());
        }

        DocumentReference result = new DocumentReference();
        // Kopiere alle Felder außer 'content' vom Originaldokument in das Ergebnis.
        document.copyValues(result);
        result.setContent(null); // Beginne mit einer leeren Content-Liste im Ergebnis

        // 1. Angereichertes PDF (muss immer dabei sein, wenn vorhanden im Original)
        document.getContent().stream()
            .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()) &&
                         c.hasFormat() && "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(c.getFormat().getSystem()) &&
                         "erechnung".equals(c.getFormat().getCode()))
            .findFirst()
            .ifPresent(pdfContent -> {
                LOGGER.info("processDocument - 'erechnung' (PDF) Content-Element gefunden und zum Ergebnis hinzugefügt.");
                result.addContent(pdfContent.copy());
            });

        // 2. Strukturierter Rechnungsinhalt (wenn gewünscht und im Original vorhanden)
        if (includeStructuredContent) {
            document.getContent().stream()
                .filter(c -> c.hasAttachment() && c.hasFormat() &&
                             "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(c.getFormat().getSystem()) &&
                             "rechnungsinhalt".equals(c.getFormat().getCode()))
                .findFirst()
                .ifPresent(structuredContent -> {
                    LOGGER.info("processDocument - 'rechnungsinhalt' Content-Element gefunden und zum Ergebnis hinzugefügt (da includeStructuredContent=true).");
                    result.addContent(structuredContent.copy());
                });
        }

        // 3. Originale Rechnung (wenn gewünscht, Versicherter und im Original vorhanden)
        if (includeOriginalInvoice && accessToken.getProfession() == Profession.VERSICHERTER) {
            document.getContent().stream()
                .filter(c -> c.hasAttachment() && c.hasFormat() &&
                             "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(c.getFormat().getSystem()) &&
                             "original".equals(c.getFormat().getCode()))
                .findFirst()
                .ifPresent(originalContent -> {
                    LOGGER.info("processDocument - 'original' Content-Element gefunden und zum Ergebnis hinzugefügt (da includeOriginalInvoice=true und Profession=VERSICHERTER).");
                    result.addContent(originalContent.copy());
                });
        }

        LOGGER.info("processDocument - Finale Anzahl Content-Elemente im Ergebnis: {}", result.getContent() != null ? result.getContent().size() : 0);
        if (result.getContent() == null || result.getContent().isEmpty()) {
            LOGGER.error("processDocument - ACHTUNG: DocumentReference wird OHNE Content-Elemente zurückgegeben. Ursprungs-ID: {}", document.getIdElement().toVersionless().getValue());
        }

        return result;
    }

    /**
     * Validiert das Ergebnis-Dokument gemäß den Anforderungen
     */
    private void validateResultDocument(DocumentReference document) {
        // Prüfe, ob Status vorhanden ist
        if (document.getStatus() == null) {
            throw new UnprocessableEntityException("Status muss vorhanden sein");
        }
        
        // Prüfe, ob Type vorhanden ist
        if (document.getType() == null || document.getType().isEmpty()) {
            throw new UnprocessableEntityException("Type muss vorhanden sein");
        }
        
        // Prüfe, ob Subject vorhanden ist
        if (document.getSubject() == null || document.getSubject().isEmpty()) {
            throw new UnprocessableEntityException("Subject muss vorhanden sein");
        }
        
        // Prüfe, ob Content vorhanden ist
        if (document.getContent() == null || document.getContent().isEmpty()) {
            throw new UnprocessableEntityException("Content muss vorhanden sein");
        }
    }

    // /**
    //  * Protokolliert die Retrieve-Operation
    //  * 
    //  * @param document Das abgerufene Dokument
    //  * @param accessToken Der Access-Token des Nutzers
    //  * @param strukturierterRechnungsinhalt Ob strukturierte Rechnungsinhalte abgerufen wurden
    //  */
    // private void logRetrieveOperation(DocumentReference document, AccessToken accessToken, boolean strukturierterRechnungsinhalt) {
    //     String actorName = accessToken.getGivenName() + " " + accessToken.getFamilyName();
    //     String actorId = accessToken.getKvnr().orElse(accessToken.getTelematikId().orElse(null));
        
    //     String description = "Abruf eines Rechnungsdokuments";
    //     if (strukturierterRechnungsinhalt) {
    //         description += " mit strukturierten Daten";
    //     }
        
    //     AuditEvent auditEvent = auditService.createRestAuditEvent(
    //         AuditEvent.AuditEventAction.R,
    //         "retrieve",
    //         new Reference(document.getId()),
    //         "DocumentReference",
    //         description,
    //         actorName,
    //         actorId
    //     );
    // }
} 