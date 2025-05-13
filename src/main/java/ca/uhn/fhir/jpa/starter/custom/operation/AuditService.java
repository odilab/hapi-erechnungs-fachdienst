package ca.uhn.fhir.jpa.starter.custom.operation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Service zur Erstellung und Speicherung von Audit-Events gemäß den Gematik-Spezifikationen
 */
@Service
public class AuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditService.class);

    private static final String ERG_NUTZUNGSPROTOKOLL_PROFILE_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll";
    private static final String AUDIT_EVENT_TYPE_SYSTEM_REST = "http://terminology.hl7.org/CodeSystem/audit-event-type";
    private static final String ERG_OPERATIONEN_CS_SYSTEM = "https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs";
    private static final String EXTRA_SECURITY_ROLE_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/extra-security-role-type";
    private static final String DICOM_SYSTEM_APPLICATION_ACTIVITY = "http://dicom.nema.org/resources/ontology/DCM";
    private static final String KVID_10_PROFILE_URL = "http://fhir.de/StructureDefinition/identifier-kvid-10";
    private static final String IDENTIFIER_TYPE_DE_BASIS_SYSTEM = "http://fhir.de/CodeSystem/identifier-type-de-basis";
    private static final String KVZ10_CODE = "KVZ10";
    // Platzhalter - Dieser sollte durch eine echte, konfigurierbare ID des Fachdienstes ersetzt werden
    private static final String FACHDIENST_ACTOR_ID = "E-RECHNUNG-FACHDIENST-ID"; 
    // Platzhalter - Das System für die Fachdienst-ID, falls vorhanden
    private static final String FACHDIENST_ACTOR_ID_SYSTEM = "urn:oid:1.2.276.0.76.S.N.N"; // Beispiel OID


    @Autowired
    private DaoRegistry daoRegistry;

    /**
     * Erstellt ein AuditEvent für eine REST-Operation (Nutzerinteraktion).
     *
     * @param action Die durchgeführte Aktion (C, R, U, D, E)
     * @param subtypeCode Der Subtyp-Code der Operation
     * @param outcome Das Ergebnis der Operation
     * @param resourceReference Referenz auf die betroffene Ressource (optional, wenn die Operation keine spezifische Ressource betrifft)
     * @param resourceName Name der Ressource oder der Operation
     * @param entityWhatDisplay Anzeigename für entity.what (mustSupport)
     * @param description Beschreibung der Operation
     * @param actorName Name des Akteurs
     * @param actorId ID des Akteurs (z.B. KVNR, Telematik-ID)
     * @param patientReference Referenz auf den betroffenen Patienten für den Versicherter-Slice (optional)
     * @return Das erstellte AuditEvent
     */
    public AuditEvent createRestAuditEvent(
            AuditEvent.AuditEventAction action,
            String subtypeCode,
            AuditEvent.AuditEventOutcome outcome,
            Reference resourceReference,
            String resourceName,
            String entityWhatDisplay,
            String description,
            String actorName,
            String actorId,
            Reference patientReference) {

        LOGGER.debug("Erstelle REST-AuditEvent für Operation {} auf Ressource {}", subtypeCode, resourceReference != null ? resourceReference.getReference() : "N/A");

        AuditEvent auditEvent = new AuditEvent();

        Meta meta = new Meta();
        meta.addProfile(ERG_NUTZUNGSPROTOKOLL_PROFILE_URL);
        auditEvent.setMeta(meta);

        Coding type = new Coding()
            .setSystem(AUDIT_EVENT_TYPE_SYSTEM_REST)
            .setCode("rest"); // A_25836: Nutzerinteraktion
        auditEvent.setType(type);

        auditEvent.addSubtype()
            .setSystem(ERG_OPERATIONEN_CS_SYSTEM)
            .setCode(subtypeCode);

        auditEvent.setAction(action);
        auditEvent.setRecorded(new Date());
        auditEvent.setOutcome(outcome); // Dynamisches Outcome

        // Agent (Nutzer)
        AuditEvent.AuditEventAgentComponent agent = auditEvent.addAgent();
        CodeableConcept agentTypeConcept = new CodeableConcept();
        agentTypeConcept.addCoding()
            .setSystem(EXTRA_SECURITY_ROLE_TYPE_SYSTEM)
            .setCode("humanuser"); // A_25836: Nutzer
        agent.setType(agentTypeConcept)
            .setName(actorName)
            .setRequestor(true);

        if (actorId != null) {
            agent.getWho().setIdentifier(new Identifier().setValue(actorId)) // System wird hier nicht explizit gesetzt, da es KVNR oder TelematikID sein kann
                .setDisplay(actorName);
        } else {
            agent.getWho().setDisplay(actorName);
        }
        
        // Entity (Primäre Ressource der Operation)
        if (resourceReference != null || entityWhatDisplay != null) {
            AuditEvent.AuditEventEntityComponent mainEntity = auditEvent.addEntity();
            if (resourceReference != null) {
                mainEntity.setWhat(resourceReference);
            }
            if (entityWhatDisplay != null) { // mustSupport
                 mainEntity.getWhat().setDisplay(entityWhatDisplay);
            }
            mainEntity.setName(resourceName);
            mainEntity.setDescription(description);
        }


        // Source Observer
        auditEvent.getSource().setObserver(new Reference().setDisplay("FdV")); // Annahme: FdV ist der Beobachter der Quelle, ggf. anpassen

        // Versicherter-Slice hinzufügen, falls eine Patientenreferenz vorhanden ist
        if (patientReference != null) {
            addVersicherterEntity(auditEvent, patientReference);
        }

        saveAuditEvent(auditEvent, subtypeCode, "REST");
        return auditEvent;
    }

    /**
     * Erstellt ein AuditEvent für eine automatische Verarbeitung durch den Fachdienst.
     *
     * @param action Die durchgeführte Aktion (C, R, U, D, E)
     * @param subtypeCode Der Subtyp-Code der Operation
     * @param outcome Das Ergebnis der Operation
     * @param resourceReference Referenz auf die betroffene Ressource (optional)
     * @param resourceName Name der Ressource oder der Operation
     * @param entityWhatDisplay Anzeigename für entity.what (mustSupport, optional wenn keine direkte Entität)
     * @param description Beschreibung der Operation
     * @param patientReference Referenz auf den betroffenen Patienten für den Versicherter-Slice (optional)
     * @return Das erstellte AuditEvent
     */
    public AuditEvent createSystemAuditEvent(
            AuditEvent.AuditEventAction action,
            String subtypeCode,
            AuditEvent.AuditEventOutcome outcome,
            Reference resourceReference,
            String resourceName,
            String entityWhatDisplay,
            String description,
            Reference patientReference) {

        LOGGER.debug("Erstelle System-AuditEvent für Operation {} auf Ressource {}",
                   subtypeCode,
                   resourceReference != null ? resourceReference.getReference() : "N/A");

        AuditEvent auditEvent = new AuditEvent();

        Meta meta = new Meta();
        meta.addProfile(ERG_NUTZUNGSPROTOKOLL_PROFILE_URL);
        auditEvent.setMeta(meta);

        Coding type = new Coding()
            .setSystem(DICOM_SYSTEM_APPLICATION_ACTIVITY) // Gemäß Spec E-Rechnung für Systemaktionen
            .setCode("110100"); // Application Activity (A_25837)
        auditEvent.setType(type);

        auditEvent.addSubtype()
            .setSystem(ERG_OPERATIONEN_CS_SYSTEM)
            .setCode(subtypeCode);

        auditEvent.setAction(action);
        auditEvent.setRecorded(new Date());
        auditEvent.setOutcome(outcome); // Dynamisches Outcome

        // Agent (Fachdienst)
        AuditEvent.AuditEventAgentComponent agent = auditEvent.addAgent();
        CodeableConcept agentTypeConcept = new CodeableConcept();
        agentTypeConcept.addCoding()
            .setSystem(EXTRA_SECURITY_ROLE_TYPE_SYSTEM)
            .setCode("dataprocessor"); // A_25837: System
        agent.setType(agentTypeConcept)
            .setName("E-Rechnung-Fachdienst")
            .setRequestor(false); // System agiert nicht als anfordernder Nutzer

        // Gemäß StructureDefinition ist agent.who.identifier mustSupport.
        // Hier sollte ein eindeutiger Identifier für den Fachdienst verwendet werden.
        agent.getWho()
             .setIdentifier(new Identifier().setSystem(FACHDIENST_ACTOR_ID_SYSTEM).setValue(FACHDIENST_ACTOR_ID))
             .setDisplay("E-Rechnung-Fachdienst");


        // Entity (Primäre Ressource der Operation)
        if (resourceReference != null || entityWhatDisplay != null) {
            AuditEvent.AuditEventEntityComponent mainEntity = auditEvent.addEntity();
             if (resourceReference != null) {
                mainEntity.setWhat(resourceReference);
            }
            if (entityWhatDisplay != null) { // mustSupport
                 mainEntity.getWhat().setDisplay(entityWhatDisplay);
            }
            mainEntity.setName(resourceName);
            mainEntity.setDescription(description);
        }


        // Source Observer
        auditEvent.getSource().setObserver(new Reference().setDisplay("FdV")); // FdV = Fachdienst Versicherten? Ggf. anpassen auf "E-Rechnung Fachdienst"

        // Versicherter-Slice hinzufügen, falls eine Patientenreferenz vorhanden ist
        if (patientReference != null) {
            addVersicherterEntity(auditEvent, patientReference);
        }
        
        saveAuditEvent(auditEvent, subtypeCode, "System");
        return auditEvent;
    }

    /**
     * Fügt dem AuditEvent eine Entity für den betroffenen Versicherten hinzu (Slice: Versicherter).
     * Die Patientenreferenz sollte einen Identifier vom Typ KVNR enthalten.
     *
     * @param auditEvent Das AuditEvent, dem die Entity hinzugefügt wird.
     * @param patientReference Die Referenz auf die Patientenressource des Versicherten.
     */
    private void addVersicherterEntity(AuditEvent auditEvent, Reference patientReference) {
        if (patientReference == null || !patientReference.hasReference()) {
            LOGGER.warn("Keine gültige Patientenreferenz für Versicherter-Slice vorhanden.");
            return;
        }

        // Es wird erwartet, dass die patientReference bereits einen Identifier mit KVNR enthält
        // oder die aufgelöste Ressource diesen hätte.
        // Für das AuditEvent setzen wir die Referenz und patternen den Typ.
        
        AuditEvent.AuditEventEntityComponent versicherterEntity = auditEvent.addEntity();
        versicherterEntity.setRole(new Coding("http://terminology.hl7.org/CodeSystem/object-role", "1", "Patient")); // Standard Rolle für Patient
        versicherterEntity.setType(new Coding("http://hl7.org/fhir/resource-types", "Patient", "Patient")); // Setzt den Typ der "what" Referenz explizit, obwohl es durch patternUri abgedeckt ist.
                                                                                                           // Für das "what" wird die Referenz direkt genutzt
        
        versicherterEntity.getWhat()
            .setReference(patientReference.getReferenceElement().getValue()) // z.B. "Patient/123"
            .setType("Patient") // Explizit setzen, da mustSupport
            .setIdentifier(extractAndCreateKvnrIdentifier(patientReference)); // Setzt den KVID-10 Identifier basierend auf der Referenz (falls möglich)
                                                                              // oder erwartet, dass die Referenz selbst diesen schon korrekt als Identifier-Teil hat.
                                                                              // StructureDefinition: patternIdentifier für den Slice.

        // Da die StructureDefinition für AuditEvent.entity:Versicherter.what.type ein patternUri: "Patient" hat,
        // und für AuditEvent.entity:Versicherter.what.identifier ein patternIdentifier,
        // ist es wichtig, dass die 'patientReference' diese Kriterien erfüllt oder so aufbereitet wird.
        // Das Setzen von .setType("Patient") auf der Referenz ist redundant wenn der Verweis selbst schon so ist, aber schadet nicht.

        // Das Profil für den Identifier wird implizit durch die Strukturdefinition gefordert.
        // Hier wird angenommen, dass der `patientReference` bereits eine KVNR im korrekten Format enthält
        // oder dass der aufrufende Service dies sicherstellt.
        // Die StructureDefinition erzwingt das Profil "http://fhir.de/StructureDefinition/identifier-kvid-10"
        // und den patternIdentifier mit KVZ10.
        
        // Name und Description könnten hier spezifischer für den Versicherten gesetzt werden, falls gewünscht.
        // z.B. versicherterEntity.setName("Betroffener Versicherter");
        // versicherterEntity.setDescription("KVNR: " + (patientReference.getIdentifier() != null ? patientReference.getIdentifier().getValue() : "unbekannt"));
    }

    /**
     * Versucht, einen KVID-10 konformen Identifier aus einer Patientenreferenz zu extrahieren oder zu erstellen.
     * Dies ist eine Hilfsmethode und muss ggf. an die tatsächliche Struktur der patientReference angepasst werden.
     * @param patientReference Die Patientenreferenz.
     * @return Ein Identifier-Objekt oder null.
     */
    private Identifier extractAndCreateKvnrIdentifier(Reference patientReference) {
        if (patientReference.hasIdentifier() && 
            IDENTIFIER_TYPE_DE_BASIS_SYSTEM.equals(patientReference.getIdentifier().getType().getCodingFirstRep().getSystem()) &&
            KVZ10_CODE.equals(patientReference.getIdentifier().getType().getCodingFirstRep().getCode())) {
            // Wenn die Referenz selbst den Identifier schon passend enthält
            return patientReference.getIdentifier();
        }
        
        // Fallback: Wenn die Referenz selbst den Identifier nicht direkt im korrekten Format hat,
        // aber die KVNR z.B. im reference String enthalten ist (nicht ideal) oder anderweitig bekannt ist.
        // Dieser Teil ist spekulativ und müsste robust implementiert werden.
        // Für dieses Beispiel wird angenommen, dass der Identifier direkt auf der Referenz ist oder
        // der Aufrufer dies sicherstellt.
        // Eine robustere Lösung würde die Patientenressource laden und den KVNR-Identifier suchen.
        // Hier wird für das Audit-Logging aber davon ausgegangen, dass die Information bereits vorliegt.
        
        // Für die StructureDefinition muss der Identifier dem KVID-10 Profil entsprechen.
        // Das bedeutet, der Identifier, der hier zurückgegeben wird, muss:
        // 1. system: "http://fhir.de/NamingSystem/gkv/kvid-10" (oder äquivalent über das Profil)
        // 2. type.coding[0].system: "http://fhir.de/CodeSystem/identifier-type-de-basis"
        // 3. type.coding[0].code: "KVZ10"
        // Wenn patientReference.getIdentifier() dies nicht erfüllt, muss hier eine Transformation stattfinden.
        // Fürs Erste geben wir zurück was da ist, oder null wenn nichts passendes da ist.
        if (patientReference.hasIdentifier()) {
             LOGGER.warn("Patientenreferenz-Identifier entspricht möglicherweise nicht dem KVID-10-Profil für AuditEvent.entity:Versicherter.what.identifier: {}", patientReference.getIdentifier().toString());
            return patientReference.getIdentifier(); // Gebe zurück, was da ist, Validierung erfolgt durch FHIR-Server anhand des Profils.
        }

        return null; 
    }


    /**
     * Speichert das AuditEvent.
     */
    private void saveAuditEvent(AuditEvent auditEvent, String subtypeCode, String eventTypeLabel) {
        try {
            daoRegistry.getResourceDao(AuditEvent.class).create(auditEvent);
            LOGGER.info("{} AuditEvent für Operation {} erfolgreich erstellt und gespeichert.", eventTypeLabel, subtypeCode);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Speichern des {} AuditEvents für Operation {}: {}", eventTypeLabel, subtypeCode, e.getMessage(), e);
            // Hier könnte eine spezifischere Fehlerbehandlung erfolgen, z.B. Retry oder Alarmierung.
        }
    }

    /**
     * Fügt generische Details zu der ersten Entität eines AuditEvents hinzu.
     * Diese Methode ist nützlich für zusätzliche, nicht durch Slices abgedeckte Informationen.
     *
     * @param auditEvent Das AuditEvent
     * @param detailType Der Typ des Details
     * @param detailValue Der Wert des Details
     */
    public void addEntityDetail(AuditEvent auditEvent, String detailType, String detailValue) {
        if (auditEvent != null && auditEvent.hasEntity() && !auditEvent.getEntity().isEmpty()) {
            // Fügt Detail zur ERSTEN Entity hinzu. Für spezifische Slices wie "Versicherter"
            // sollten die Felder direkt auf der jeweiligen Entity gesetzt werden.
            auditEvent.getEntityFirstRep().addDetail()
                .setType(detailType)
                .setValue(new StringType(detailValue));

            // Aktualisiere das AuditEvent nach dem Hinzufügen der Details
            try {
                daoRegistry.getResourceDao(AuditEvent.class).update(auditEvent);
                LOGGER.info("AuditEvent-Detail {} erfolgreich zu AuditEvent {} hinzugefügt und aktualisiert.", detailType, auditEvent.getIdElement().getIdPart());
            } catch (Exception e) {
                LOGGER.error("Fehler beim Aktualisieren des AuditEvents {} mit Detail {}: {}", auditEvent.getIdElement() != null ? auditEvent.getIdElement().getIdPart() : "UNSAVED", detailType, e.getMessage(), e);
            }
        } else {
            LOGGER.warn("Kann kein Entity-Detail hinzufügen: AuditEvent ist null oder hat keine Entities.");
        }
    }
} 