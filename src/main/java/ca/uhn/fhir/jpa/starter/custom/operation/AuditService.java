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

    @Autowired
    private DaoRegistry daoRegistry;

    /**
     * Erstellt ein AuditEvent für eine REST-Operation
     * 
     * @param action Die durchgeführte Aktion (C, R, U, D)
     * @param subtypeCode Der Subtyp-Code der Operation
     * @param resourceReference Referenz auf die betroffene Ressource
     * @param resourceName Name der Ressource
     * @param description Beschreibung der Operation
     * @param actorName Name des Akteurs
     * @param actorId ID des Akteurs
     * @return Das erstellte AuditEvent
     */
    public AuditEvent createRestAuditEvent(
            AuditEvent.AuditEventAction action,
            String subtypeCode,
            Reference resourceReference,
            String resourceName,
            String description,
            String actorName,
            String actorId) {
        
        LOGGER.debug("Erstelle REST-AuditEvent für Operation {} auf Ressource {}", subtypeCode, resourceReference.getReference());
        
        AuditEvent auditEvent = new AuditEvent();
        
        // Meta mit Profil setzen
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll");
        auditEvent.setMeta(meta);
        
        // Type setzen (A_25836)
        Coding type = new Coding()
            .setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type")
            .setCode("rest");
        auditEvent.setType(type);
        
        // Subtype setzen
        auditEvent.addSubtype()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs")
            .setCode(subtypeCode);
                
        auditEvent.setAction(action);
        auditEvent.setRecorded(new Date());
        auditEvent.setOutcome(AuditEvent.AuditEventOutcome._0);
        
        // Entity setzen
        AuditEvent.AuditEventEntityComponent entity = auditEvent.addEntity()
            .setWhat(resourceReference)
            .setName(resourceName)
            .setDescription(description);
                
        // Agent setzen (A_25836)
        AuditEvent.AuditEventAgentComponent agent = auditEvent.addAgent();
        CodeableConcept agentType = new CodeableConcept();
        agentType.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/extra-security-role-type")
            .setCode("humanuser");
        agent.setType(agentType)
            .setName(actorName)
            .setRequestor(true);
        
        if (actorId != null) {
            agent.getWho().setIdentifier(new Identifier().setValue(actorId))
                .setDisplay(actorName);
        }
            
        // Source Observer setzen
        auditEvent.getSource().getObserver().setDisplay("FdV");
        
        // AuditEvent speichern
        try {
            daoRegistry.getResourceDao(AuditEvent.class).create(auditEvent);
            LOGGER.info("AuditEvent für Operation {} erfolgreich erstellt", subtypeCode);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen des AuditEvents: {}", e.getMessage(), e);
        }
        
        return auditEvent;
    }
    
    /**
     * Erstellt ein AuditEvent für eine automatische Verarbeitung
     * 
     * @param action Die durchgeführte Aktion (C, R, U, D)
     * @param subtypeCode Der Subtyp-Code der Operation
     * @param resourceReference Referenz auf die betroffene Ressource
     * @param resourceName Name der Ressource
     * @param description Beschreibung der Operation
     * @return Das erstellte AuditEvent
     */
    public AuditEvent createSystemAuditEvent(
            AuditEvent.AuditEventAction action,
            String subtypeCode,
            Reference resourceReference,
            String resourceName,
            String description) {
        
        LOGGER.debug("Erstelle System-AuditEvent für Operation {} auf Ressource {}", 
                   subtypeCode, 
                   resourceReference != null ? resourceReference.getReference() : "keine direkte Referenz");
        
        AuditEvent auditEvent = new AuditEvent();
        
        // Meta mit Profil setzen
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-nutzungsprotokoll");
        auditEvent.setMeta(meta);
        
        // Type setzen (A_25837)
        Coding type = new Coding()
            .setSystem("http://dicom.nema.org/resources/ontology/DCM")
            .setCode("110100");
        auditEvent.setType(type);
        
        // Subtype setzen
        auditEvent.addSubtype()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-operationen-cs")
            .setCode(subtypeCode);
                
        auditEvent.setAction(action);
        auditEvent.setRecorded(new Date());
        auditEvent.setOutcome(AuditEvent.AuditEventOutcome._0);
        
        // Entity setzen
        AuditEvent.AuditEventEntityComponent entity = auditEvent.addEntity()
            .setName(resourceName)
            .setDescription(description);
            
        // Referenz nur setzen, wenn vorhanden
        if (resourceReference != null) {
            entity.setWhat(resourceReference);
        }
                
        // Agent setzen (A_25837)
        AuditEvent.AuditEventAgentComponent agent = auditEvent.addAgent();
        CodeableConcept agentType = new CodeableConcept();
        agentType.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/extra-security-role-type")
            .setCode("dataprocessor");
        agent.setType(agentType)
            .setName("E-Rechnung-Fachdienst")
            .setRequestor(false);
        
        agent.getWho().setDisplay("E-Rechnung-Fachdienst");
            
        // Source Observer setzen
        auditEvent.getSource().getObserver().setDisplay("FdV");
        
        // AuditEvent speichern
        try {
            daoRegistry.getResourceDao(AuditEvent.class).create(auditEvent);
            LOGGER.info("System-AuditEvent für Operation {} erfolgreich erstellt", subtypeCode);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen des System-AuditEvents: {}", e.getMessage(), e);
        }
        
        return auditEvent;
    }
    
    /**
     * Fügt Details zu einem AuditEvent hinzu
     * 
     * @param auditEvent Das AuditEvent
     * @param detailType Der Typ des Details
     * @param detailValue Der Wert des Details
     */
    public void addEntityDetail(AuditEvent auditEvent, String detailType, String detailValue) {
        if (auditEvent != null && auditEvent.hasEntity() && !auditEvent.getEntity().isEmpty()) {
            auditEvent.getEntity().get(0).addDetail()
                .setType(detailType)
                .setValue(new StringType(detailValue));
            
            // AuditEvent nach dem Hinzufügen der Details speichern
            try {
                daoRegistry.getResourceDao(AuditEvent.class).update(auditEvent);
                LOGGER.info("AuditEvent-Detail {} erfolgreich hinzugefügt", detailType);
            } catch (Exception e) {
                LOGGER.error("Fehler beim Aktualisieren des AuditEvents mit Detail {}: {}", detailType, e.getMessage(), e);
            }
        }
    }
} 