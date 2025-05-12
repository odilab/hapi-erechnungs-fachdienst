package ca.uhn.fhir.jpa.starter.custom.operation.retrieve;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.context.FhirContext;

import java.util.List;

/**
 * Service für die Suche und den Abruf von Dokumenten
 */
@Service
public class DocumentRetrievalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentRetrievalService.class);

    private final DaoRegistry daoRegistry;
    private final FhirContext fhirContext;

    @Autowired
    public DocumentRetrievalService(DaoRegistry daoRegistry, FhirContext fhirContext) {
        this.daoRegistry = daoRegistry;
        this.fhirContext = fhirContext;
    }

    /**
     * Sucht ein Dokument anhand des Tokens
     */
    public DocumentReference findDocumentByToken(String token) {
        LOGGER.info("Suche Dokument mit Token: {}", token);

        // VERSUCH 1: Direkter Lesezugriff über ID (wenn der Token die logische ID ist)
        try {
            IdType docId = new IdType("DocumentReference", token);
            DocumentReference document = daoRegistry.getResourceDao(DocumentReference.class).read(docId);
            if (document != null) {
                LOGGER.info("Dokument direkt via ID (Token) {} gefunden.", token);
                logDocumentDetails(document, token);
                return document;
            }
        } catch (ResourceNotFoundException e) {
            LOGGER.info("Dokument mit ID (Token) {} nicht gefunden (ResourceNotFoundException), versuche Identifier-Suche.", token);
        } catch (Exception e) {
            LOGGER.warn("Unerwarteter Fehler beim direkten Lesen der DocumentReference mit ID (Token) {}: {}. Versuche Identifier-Suche.", token, e.getMessage(), e);
        }
        
        // VERSUCH 2: Suche über Identifier mit spezifischem System
        DocumentReference document = findDocumentByIdentifierWithSystem(token, "https://gematik.de/fhir/sid/erg-token");
        if (document != null) {
            return document;
        }
        
        // VERSUCH 3: Suche über Identifier ohne Systemangabe
        document = findDocumentByIdentifierWithoutSystem(token);
        if (document != null) {
            return document;
        }
        
        // VERSUCH 4: Fallback - Durchsuche alle Dokumente und filtere manuell
        document = findDocumentByManualFiltering(token);
        
        if (document == null) {
            LOGGER.error("Kein Dokument mit Token {} nach allen Suchstrategien gefunden.", token);
        }
        
        return document;
    }

    /**
     * Lädt eine Binary-Ressource von einer URL
     */
    public Binary loadBinaryFromUrl(String url, RequestDetails requestDetails) {
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
            Binary binary = daoRegistry.getResourceDao(Binary.class).read(binaryId, requestDetails);
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
     * Lädt die Patientenressource eines Dokuments
     */
    public Patient loadPatientResource(DocumentReference document) {
        if (document.getSubject() == null || document.getSubject().isEmpty() || !document.getSubject().hasReference()) {
            LOGGER.warn("Dokument hat keinen oder keinen gültigen Patienten-Bezug (Reference)");
            return null;
        }

        String patientReferenceString = document.getSubject().getReference();
        LOGGER.info("Patientenreferenz aus DocumentReference: {}", patientReferenceString);

        try {
            IdType patientId = new IdType(patientReferenceString);
            if (!patientId.hasResourceType() || !"Patient".equals(patientId.getResourceType()) || !patientId.hasIdPart()) {
                LOGGER.warn("Ungültige Patientenreferenz in DocumentReference: {}", patientReferenceString);
                return null;
            }
            Patient patientResource = daoRegistry.getResourceDao(Patient.class).read(patientId);
            if (patientResource == null) {
                LOGGER.warn("Patientenressource mit ID {} nicht gefunden.", patientId.getIdPart());
                return null;
            }
            LOGGER.info("Patientenressource {} erfolgreich geladen.", patientId.toUnqualifiedVersionless().getValue());
            return patientResource;
        } catch (ResourceNotFoundException e) {
            LOGGER.error("Patientenressource, auf die in DocumentReference.subject verwiesen wird, konnte nicht gefunden werden: {}", patientReferenceString, e);
            return null;
        } catch (Exception e) {
            LOGGER.error("Fehler beim Laden der Patientenressource (Referenz: {}): {}", patientReferenceString, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extrahiert die KVNR aus der Patientenressource
     */
    public String extractKvnrFromPatient(Patient patient) {
        if (patient == null || !patient.hasIdentifier()) {
            return null;
        }
        
        for (Identifier identifier : patient.getIdentifier()) {
            if ("http://fhir.de/sid/gkv/kvid-10".equals(identifier.getSystem()) && identifier.hasValue()) {
                String kvnr = identifier.getValue();
                LOGGER.info("KVNR '{}' aus geladener Patientenressource extrahiert.", kvnr);
                return kvnr;
            }
        }
        
        // Logging für Debugging-Zwecke
        patient.getIdentifier().forEach(id -> 
            LOGGER.warn("Gefundener Identifier im Patienten {}: System='{}', Wert='{}'", 
                        patient.getIdElement().toUnqualifiedVersionless().getValue(), 
                        id.getSystem(), 
                        id.getValue()));
        
        return null;
    }

    // Private Hilfsmethoden

    private DocumentReference findDocumentByIdentifierWithSystem(String token, String system) {
        LOGGER.info("Versuche Suche via Identifier mit spezifischem System: {} und Wert: {}", system, token);
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, new TokenParam(system, token));
        
        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);
        
        if (results.size() > 0) {
            LOGGER.info("Dokument mit spezifischem Identifier-System und Token-Wert gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }
        
        LOGGER.info("Kein Dokument mit spezifischem Identifier-System ({}) und Wert {} gefunden.", system, token);
        return null;
    }

    private DocumentReference findDocumentByIdentifierWithoutSystem(String token) {
        LOGGER.info("Versuche Suche via Identifier ohne spezifisches System, nur mit Wert: {}", token);
        SearchParameterMap paramMap = new SearchParameterMap();
        paramMap.add(DocumentReference.SP_IDENTIFIER, new TokenParam(null, token));
        
        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);
        
        if (results.size() > 0) {
            LOGGER.info("Dokument mit Identifier (nur Wert) gefunden. Anzahl: {}", results.size());
            return (DocumentReference) results.getResources(0, 1).get(0);
        }
        
        LOGGER.info("Kein Dokument mit Identifier (nur Wert) {} gefunden.", token);
        return null;
    }

    private DocumentReference findDocumentByManualFiltering(String token) {
        LOGGER.warn("Letzter Versuch: Durchsuche ALLE DocumentReference-Ressourcen und filtere manuell nach dem Token '{}' im Identifier.", token);
        SearchParameterMap paramMap = new SearchParameterMap(); // Leere Map, um alle Ressourcen zu laden
        IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class).search(paramMap);
        
        Integer resultSize = results.size();
        LOGGER.info("Gesamtzahl der DocumentReference-Ressourcen für manuelles Filtern: {}", resultSize != null ? resultSize : "(unbekannt/null)");
        
        if (resultSize != null && resultSize > 0) {
            List<IBaseResource> allDocs = results.getResources(0, resultSize);
            for (IBaseResource res : allDocs) {
                DocumentReference doc = (DocumentReference) res;
                for (Identifier identifier : doc.getIdentifier()) {
                    if (token.equals(identifier.getValue())) {
                        LOGGER.info("Dokument mit passendem Token-Wert '{}' im Identifier bei manueller Iteration gefunden. DocumentReference ID: {}", token, doc.getIdElement().toUnqualifiedVersionless().getValue());
                        return doc;
                    }
                }
            }
        }
        
        return null;
    }

    private void logDocumentDetails(DocumentReference document, String token) {
        String documentAsJson = fhirContext.newJsonParser().setPrettyPrint(true).encodeResourceToString(document);
        LOGGER.info("Vollständiger Inhalt der via ID (Token) {} abgerufenen DocumentReference:\n{}", token, documentAsJson);
    }
} 