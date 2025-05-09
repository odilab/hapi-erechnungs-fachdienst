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
        // DEBUG: Logge die empfangenen BooleanType Parameter direkt nach Deserialisierung
        LOGGER.info("RetrieveOperation: Empfangene Parameter - returnAngereichertesPDF: {}, returnStrukturierteDaten: {}, returnOriginalPDF: {}, returnSignatur: {}",
            returnAngereichertesPDF != null ? returnAngereichertesPDF.getValueAsString() : "null",
            returnStrukturierteDaten != null ? returnStrukturierteDaten.getValueAsString() : "null",
            returnOriginalPDF != null ? returnOriginalPDF.getValueAsString() : "null",
            returnSignatur != null ? returnSignatur.getValueAsString() : "null"
        );

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
        Parameters retVal = buildRetrieveResponse(
            document,
            returnAngereichertesPDF != null && returnAngereichertesPDF.getValue(),
            returnStrukturierteDaten != null && returnStrukturierteDaten.getValue(),
            returnOriginalPDF != null && returnOriginalPDF.getValue(),
            returnSignatur != null && returnSignatur.getValue(),
            accessToken,
            theRequestDetails
        );

        // Validiere das Ergebnis-Dokument - Diese Validierung muss ggf. angepasst werden,
        // da die Struktur der Antwort nun flexibler ist (Parameters statt einzelner DocumentReference).
        // Fürs Erste lassen wir sie auskommentiert oder entfernen sie, da die `Parameters`-Ressource
        // an sich keine so strengen Validierungsregeln wie eine einzelne `DocumentReference` hat.
        // validateResultDocument(processedDocument);

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
        
        // Prüfe, ob results.size() null ist, bevor es verwendet wird.
        Integer resultSize = results.size();
        LOGGER.info("Gesamtzahl der DocumentReference-Ressourcen für manuelles Filtern: {}", resultSize != null ? resultSize : "(unbekannt/null)");
        
        if (resultSize != null && resultSize > 0) { // isEmpty() ist nicht null-sicher
            List<IBaseResource> allDocs = results.getResources(0, resultSize);
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
     * Verarbeitet das Dokument entsprechend der Parameter und erstellt die Parameters-Antwort.
     */
    private Parameters buildRetrieveResponse(
            DocumentReference originalDocRef,
            boolean retrieveAngereichertesPDF,
            boolean retrieveStrukturierteDaten,
            boolean retrieveOriginalPDF,
            boolean retrieveSignatur,
            AccessToken accessToken,
            RequestDetails theRequestDetails
    ) {
        Parameters responseParameters = new Parameters();
        LOGGER.info("buildRetrieveResponse - Beginn für DocumentReference ID: {}. Flags: angPDF={}, strDaten={}, origPDF={}, signatur={}",
            originalDocRef.getIdElement().toVersionless().getValue(), retrieveAngereichertesPDF, retrieveStrukturierteDaten, retrieveOriginalPDF, retrieveSignatur);

        // 1. Metadaten-DocumentReference hinzufügen
        DocumentReference metadataDocRef = originalDocRef.copy();
        
        // --- BEGINN: Hinzufügen der Markierung-Extension ---
        final String MARKIERUNG_MAIN_EXTENSION_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung";
        final String SUB_EXT_MARKIERUNG_URL = "markierung";
        final String SUB_EXT_GELESEN_URL = "gelesen";
        final String SUB_EXT_DETAILS_URL = "details";
        final String SUB_EXT_ZEITPUNKT_URL = "zeitpunkt";
        // Annahme: Das ValueSet 'erg-dokument-artderarchivierung-vs' enthält den Code 'gelesen' und definiert dessen System.
        // Wir verwenden hier die ValueSet-URL als System, was eine gängige Praxis ist, wenn kein spezifisches CodeSystem explizit ist.
        final String MARKIERUNG_CODING_SYSTEM_FOR_GELESEN = "https://gematik.de/fhir/erg/ValueSet/erg-dokument-artderarchivierung-vs";

        // Hole oder erstelle die Haupt-Extension
        Extension markierungMainExtension = metadataDocRef.getExtensionsByUrl(MARKIERUNG_MAIN_EXTENSION_URL).stream().findFirst().orElse(null);
        if (markierungMainExtension == null) {
            markierungMainExtension = metadataDocRef.addExtension().setUrl(MARKIERUNG_MAIN_EXTENSION_URL);
        } else {
            // Bestehende Sub-Extensions ggf. entfernen oder gezielt aktualisieren.
            // Für diese Implementierung fügen wir hinzu oder aktualisieren gezielt.
            // Es könnte sinnvoll sein, alte Sub-Extensions zu entfernen, um Konsistenz zu gewährleisten:
            // markierungMainExtension.setExtension(new ArrayList<>()); // Vorsicht: Entfernt alle Sub-Extensions
        }

        // Sub-Extension "markierung" (Typ der Markierung)
        List<Extension> markierungTypSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_MARKIERUNG_URL);
        Extension markierungTypSubExtension = markierungTypSubExtensions.stream().findFirst().orElse(null);
        if (markierungTypSubExtension == null) {
            markierungTypSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_MARKIERUNG_URL);
        }
        // Gemäß Constraint ERGDocumentReferenceMarkierung-2: wenn "gelesen" (boolean) gesetzt wird,
        // muss die "markierung" (coding) den Code "gelesen" haben.
        Coding markierungCoding = new Coding()
            .setSystem(MARKIERUNG_CODING_SYSTEM_FOR_GELESEN)
            .setCode("gelesen")
            .setDisplay("Gelesen"); // Display ist optional
        markierungTypSubExtension.setValue(markierungCoding);

        // Sub-Extension "gelesen" (Boolean-Flag)
        List<Extension> gelesenSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_GELESEN_URL);
        Extension gelesenSubExtension = gelesenSubExtensions.stream().findFirst().orElse(null);
        if (gelesenSubExtension == null) {
            gelesenSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_GELESEN_URL);
        }
        gelesenSubExtension.setValue(new BooleanType(true));

        // Sub-Extension "details" (Wer hat die Aktion durchgeführt)
        List<Extension> detailsSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_DETAILS_URL);
        Extension detailsSubExtension = detailsSubExtensions.stream().findFirst().orElse(null);
        if (detailsSubExtension == null) {
            detailsSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_DETAILS_URL);
        }
        String detailsContent;
        Profession profession = accessToken.getProfession();
        String idNum = accessToken.getIdNumber(); // Sollte vorhanden sein gemäß validateAuthorization

        if (profession == Profession.VERSICHERTER) {
            detailsContent = "Gelesen-Markierung gesetzt von: Versicherter (ID: " + idNum + ", KVNR: " + accessToken.getKvnr().orElse("nicht vorhanden") + ")";
        } else if (profession == Profession.KOSTENTRAEGER) {
            detailsContent = "Gelesen-Markierung gesetzt von: Kostenträger (ID: " + idNum + ", TelematikID: " + accessToken.getTelematikId().orElse("nicht vorhanden") + ")";
        } else if (profession != null) {
            detailsContent = "Gelesen-Markierung gesetzt von: " + profession.toString() + " (ID: " + idNum + ")";
        } else {
            detailsContent = "Gelesen-Markierung gesetzt von: Unbekannte Profession (ID: " + idNum + ")";
        }
        detailsSubExtension.setValue(new StringType(detailsContent));

        // Sub-Extension "zeitpunkt" (Wann wurde die Aktion durchgeführt)
        List<Extension> zeitpunktSubExtensions = markierungMainExtension.getExtensionsByUrl(SUB_EXT_ZEITPUNKT_URL);
        Extension zeitpunktSubExtension = zeitpunktSubExtensions.stream().findFirst().orElse(null);
        if (zeitpunktSubExtension == null) {
            zeitpunktSubExtension = markierungMainExtension.addExtension().setUrl(SUB_EXT_ZEITPUNKT_URL);
        }
        zeitpunktSubExtension.setValue(new DateTimeType(new java.util.Date()));
        
        LOGGER.info("Markierung-Extension zu metadataDocRef hinzugefügt/aktualisiert. Gelesen=true, Details='{}'", detailsContent);
        // --- ENDE: Hinzufügen der Markierung-Extension ---

        responseParameters.addParameter().setName("dokumentMetadaten").setResource(metadataDocRef);
        LOGGER.info("Metadaten-DocumentReference (mit ursprünglichem Content und Markierung) zum Parameter 'dokumentMetadaten' hinzugefügt.");

        // 2. Angereichertes PDF (erechnung)
        if (retrieveAngereichertesPDF) {
            originalDocRef.getContent().stream()
                .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()) &&
                             c.hasFormat() && "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(c.getFormat().getSystem()) &&
                             "erechnung".equals(c.getFormat().getCode()) && c.getAttachment().hasUrl())
                .findFirst()
                .ifPresent(content -> {
                    try {
                        Binary pdfBinary = loadBinaryFromUrl(content.getAttachment().getUrl(), theRequestDetails);
                        if (pdfBinary != null) {
                            responseParameters.addParameter().setName("angereichertesPDF").setResource(pdfBinary);
                            LOGGER.info("Angereichertes PDF (erechnung) als Binary-Ressource zum Parameter 'angereichertesPDF' hinzugefügt. URL: {}", content.getAttachment().getUrl());
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Konnte angereichertes PDF (erechnung) nicht laden von URL {}: {}", content.getAttachment().getUrl(), e.getMessage());
                    }
                });
        }

        // 3. Strukturierte Daten (Invoice)
        if (retrieveStrukturierteDaten) {
            originalDocRef.getContent().stream()
                .filter(c -> c.hasAttachment() && "application/fhir+json".equals(c.getAttachment().getContentType()) &&
                             c.hasFormat() && "https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs".equals(c.getFormat().getSystem()) &&
                             "rechnungsinhalt".equals(c.getFormat().getCode()) && c.getAttachment().hasUrl())
                .findFirst()
                .ifPresent(content -> {
                    try {
                        IdType invoiceId = new IdType(content.getAttachment().getUrl());
                        if ("Invoice".equals(invoiceId.getResourceType()) && invoiceId.hasIdPart()) {
                             Invoice invoice = daoRegistry.getResourceDao(Invoice.class).read(invoiceId, theRequestDetails);
                             if (invoice != null) {
                                responseParameters.addParameter().setName("strukturierteDaten").setResource(invoice);
                                LOGGER.info("Strukturierte Daten (Invoice) zum Parameter 'strukturierteDaten' hinzugefügt. ID: {}", invoiceId.getValue());
                             } else {
                                 LOGGER.warn("Invoice mit ID {} nicht gefunden (null zurückgegeben).", invoiceId.getValue());
                             }
                        } else {
                             LOGGER.warn("URL {} für strukturierte Daten verweist nicht auf eine gültige Invoice-Ressource oder hat keinen ID-Teil.", content.getAttachment().getUrl());
                        }
                    } catch (ResourceNotFoundException e) {
                        LOGGER.warn("Invoice Ressource nicht gefunden unter URL {}: {}", content.getAttachment().getUrl(), e.getMessage());
                    } catch (Exception e) {
                        LOGGER.warn("Konnte strukturierte Daten (Invoice) nicht laden von URL {}: {}", content.getAttachment().getUrl(), e.getMessage(), e);
                    }
                });
        }

        // 4. Original PDF
        if (retrieveOriginalPDF) {

            // DEBUG: Logge die relatesTo-Liste des originalDocRef
            if (originalDocRef.hasRelatesTo()) {
                LOGGER.info("OriginalPDF: DEBUG - originalDocRef.getRelatesTo() enthält {} Elemente.", originalDocRef.getRelatesTo().size());
                for (int i = 0; i < originalDocRef.getRelatesTo().size(); i++) {
                    DocumentReference.DocumentReferenceRelatesToComponent rel = originalDocRef.getRelatesTo().get(i);
                    String relCode = rel.hasCode() ? rel.getCode().toCode() : "null";
                    String targetRef = rel.hasTarget() && rel.getTarget().hasReference() ? rel.getTarget().getReference() : "null";
                    LOGGER.info("OriginalPDF: DEBUG - relatesTo[{}]: code='{}', target.reference='{}'", i, relCode, targetRef);
                }
            } else {
                LOGGER.info("OriginalPDF: DEBUG - originalDocRef hat keine relatesTo-Einträge.");
            }

            originalDocRef.getRelatesTo().stream()
                .filter(rel -> rel.hasCode() && "transforms".equals(rel.getCode().toCode()) && rel.hasTarget() && rel.getTarget().hasReference())
                .findFirst()
                .ifPresent(relatesToEntry -> {
                    String sourceDocRefUrl = relatesToEntry.getTarget().getReference();
                    LOGGER.info("OriginalPDF: Versuche Original-DocumentReference über relatesTo-Referenz zu laden. URL aus relatesTo.target.reference: {}", sourceDocRefUrl);
                    try {
                        IdType originalDocRefId = new IdType(sourceDocRefUrl);
                        if (!"DocumentReference".equals(originalDocRefId.getResourceType()) || !originalDocRefId.hasIdPart()){
                            LOGGER.warn("OriginalPDF: Referenz in relatesTo ({}) ist keine gültige DocumentReference ID.", sourceDocRefUrl);
                            return;
                        }
                        DocumentReference sourceDocRef = daoRegistry.getResourceDao(DocumentReference.class).read(originalDocRefId, theRequestDetails);
                        if (sourceDocRef != null) {
                            // HINWEIS: Ausgabe der vollständigen sourceDocRef
                            LOGGER.info("OriginalPDF: Folgende DocumentReference wurde erfolgreich über die URL '{}' geladen:\n{}", 
                                        sourceDocRefUrl, 
                                        ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(sourceDocRef));

                            sourceDocRef.getContent().stream()
                                // Vereinfachter Filter: Nimm das erste PDF-Attachment
                                .filter(c -> c.hasAttachment() && "application/pdf".equals(c.getAttachment().getContentType()))
                                .findFirst()
                                .ifPresent(pdfContent -> {
                                    try {
                                        Binary originalPdfBinary = null;
                                        if (pdfContent.getAttachment().hasData()) {
                                            originalPdfBinary = new Binary();
                                            originalPdfBinary.setContentType(pdfContent.getAttachment().getContentType());
                                            originalPdfBinary.setData(pdfContent.getAttachment().getData());
                                            LOGGER.info("OriginalPDF: Original PDF direkt aus Attachment.data der sourceDocRef erstellt.");
                                        } else if (pdfContent.getAttachment().hasUrl()) {
                                            // Dieser Pfad ist relevant, wenn das Original-PDF als separate Binary-Ressource gespeichert ist
                                            // und in sourceDocRef.content.attachment.url darauf verwiesen wird.
                                            originalPdfBinary = loadBinaryFromUrl(pdfContent.getAttachment().getUrl(), theRequestDetails);
                                            //LOGGER.info("OriginalPDF: Original PDF als Binary-Ressource von URL {} geladen.", pdfContent.getAttachment().getUrl());
                                        }

                                        if (originalPdfBinary != null) {
                                            responseParameters.addParameter().setName("originalPDF").setResource(originalPdfBinary);
                                            LOGGER.info("OriginalPDF: Original PDF als Binary-Ressource zum Parameter 'originalPDF' hinzugefügt.");
                                        } else {
                                            LOGGER.warn("OriginalPDF: Konnte keine Daten oder URL für das Original PDF im sourceDocRef.content finden.");
                                        }
                                    } catch (Exception e) {
                                        LOGGER.warn("OriginalPDF: Fehler beim Verarbeiten/Extrahieren des Original PDF Inhalts: {}", e.getMessage(), e);
                                    }
                                });
                        } else {
                            LOGGER.warn("OriginalPDF: Original-DocumentReference via relatesTo ({}) nicht gefunden (null zurückgegeben).", sourceDocRefUrl);
                        }
                    } catch (ResourceNotFoundException e) {
                        LOGGER.warn("OriginalPDF: Original-DocumentReference Ressource nicht gefunden unter URL {}: {}", sourceDocRefUrl, e.getMessage());
                    } catch (Exception e) {
                        LOGGER.warn("OriginalPDF: Konnte referenziertes Original-DocumentReference nicht laden von {}: {}", sourceDocRefUrl, e.getMessage(), e);
                    }
                });
        }

        // 5. Signatur
        if (retrieveSignatur) {
            originalDocRef.getExtension().stream()
                .filter(ext -> "https://gematik.de/fhir/erg/StructureDefinition/erg-docref-signature".equals(ext.getUrl()))
                .findFirst()
                .ifPresent(ext -> {
                    if (ext.getValue() instanceof Signature) {
                        Signature signature = (Signature) ext.getValue();
                        responseParameters.addParameter().setName("signatur").setValue(signature);
                        LOGGER.info("Signatur aus Extension zum Parameter 'signatur' hinzugefügt.");
                    } else {
                        LOGGER.warn("Signatur-Extension gefunden, aber der Wert ist nicht vom Typ Signature. Gefunden: {}", ext.getValue().fhirType());
                    }
                });
        }
        
        LOGGER.info("buildRetrieveResponse - Ende. {} Parameter erstellt.", responseParameters.getParameter().size());
        return responseParameters;
    }

    private Binary loadBinaryFromUrl(String url, RequestDetails theRequestDetails) {
        if (url == null || url.isEmpty()) {
            LOGGER.warn("URL zum Laden der Binary ist leer oder null.");
            return null;
        }
        try {
            IdType binaryId = new IdType(url);
            if (!"Binary".equals(binaryId.getResourceType()) || !binaryId.hasIdPart()) {
                 LOGGER.warn("URL {} ist keine gültige relative Binary-Referenz (erwartet Format 'Binary/[ID]').", url);
                 return null;
            }
            LOGGER.info("Versuche Binary zu laden mit ID: {}", binaryId.toUnqualifiedVersionless().getValue());
            Binary binary = daoRegistry.getResourceDao(Binary.class).read(binaryId, theRequestDetails);
            if (binary == null) {
                LOGGER.warn("Binary mit ID {} nicht gefunden (null zurückgegeben).", binaryId.toUnqualifiedVersionless().getValue());
            }
            return binary;
        } catch (ResourceNotFoundException e) {
            LOGGER.warn("Binary-Ressource nicht gefunden unter URL {}: {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            LOGGER.error("Fehler beim Laden der Binary-Ressource von URL {}: {}", url, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Validiert das Ergebnis-Dokument gemäß den Anforderungen
     * HINWEIS: Diese Methode ist nun weniger relevant, da die Antwort eine Parameters-Ressource ist
     * und die einzelnen Komponenten optional sein können. Eine Validierung müsste ggf.
     * auf der `Parameters`-Ressource selbst oder deren Inhalt basieren.
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
        
        // Prüfe, ob Content vorhanden ist - DIESE PRÜFUNG IST FÜR DIE METADATEN-DOCREF NICHT MEHR SINNVOLL,
        // DA content NUN NULL GESETZT WIRD.
        // if (document.getContent() == null || document.getContent().isEmpty()) {
        //     throw new UnprocessableEntityException("Content muss vorhanden sein");
        // }
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