package ca.uhn.fhir.jpa.starter.custom.operation;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Service für Patient-Benachrichtigungen
 */
@Service
public class NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    @Autowired
    private DaoRegistry daoRegistry;

    /**
     * Erstellt eine Benachrichtigung für einen Patienten über einen neuen ERG-Token
     */
    public Communication createErgTokenNotification(String patientId, String ergToken, String leistungserbringerName) {
        Communication notification = new Communication();
        
        // Meta-Information
        Meta meta = new Meta();
        meta.addProfile("http://gematik.de/fhir/erg/StructureDefinition/erg-patient-notification");
        notification.setMeta(meta);
        
        // Status
        notification.setStatus(Communication.CommunicationStatus.COMPLETED);
        
        // Kategorien
        CodeableConcept category = new CodeableConcept();
        category.addCoding()
            .setSystem("http://gematik.de/fhir/erg/CodeSystem/notification-type")
            .setCode("erg-token-available")
            .setDisplay("ERG-Token verfügbar");
        notification.addCategory(category);
        
        // Empfänger (Patient)
        notification.addRecipient(new Reference("Patient/" + patientId));
        
        // Absender (Fachdienst)
        notification.setSender(new Reference().setDisplay("E-Rechnung Fachdienst"));
        
        // Zeitstempel
        notification.setSent(new Date());
        
        // Nachrichteninhalt
        Communication.CommunicationPayloadComponent payload = notification.addPayload();
        payload.getContentStringType().setValue(createNotificationText(ergToken, leistungserbringerName));
        
        // Priority
        notification.setPriority(Communication.CommunicationPriority.ROUTINE);
        
        // Zusätzliche Informationen als Extensions
        notification.addExtension()
            .setUrl("http://gematik.de/fhir/erg/StructureDefinition/erg-token")
            .setValue(new StringType(ergToken));
            
        notification.addExtension()
            .setUrl("http://gematik.de/fhir/erg/StructureDefinition/notification-action")
            .setValue(new CodeableConcept().addCoding()
                .setSystem("http://gematik.de/fhir/erg/CodeSystem/notification-actions")
                .setCode("retrieve-document")
                .setDisplay("Dokument abrufen"));

        // Speichern
        try {
            daoRegistry.getResourceDao(Communication.class).create(notification);
            LOGGER.info("Benachrichtigung für Patient {} über ERG-Token {} erfolgreich erstellt.", patientId, ergToken);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen der Benachrichtigung für Patient {}: {}", patientId, e.getMessage(), e);
        }
        
        return notification;
    }
    
    /**
     * Erstellt eine Benachrichtigung über Statusänderungen
     */
    public Communication createStatusChangeNotification(String patientId, String ergToken, String oldStatus, String newStatus) {
        Communication notification = new Communication();
        
        Meta meta = new Meta();
        meta.addProfile("http://gematik.de/fhir/erg/StructureDefinition/erg-patient-notification");
        notification.setMeta(meta);
        
        notification.setStatus(Communication.CommunicationStatus.COMPLETED);
        
        CodeableConcept category = new CodeableConcept();
        category.addCoding()
            .setSystem("http://gematik.de/fhir/erg/CodeSystem/notification-type")
            .setCode("status-change")
            .setDisplay("Statusänderung");
        notification.addCategory(category);
        
        notification.addRecipient(new Reference("Patient/" + patientId));
        notification.setSender(new Reference().setDisplay("E-Rechnung Fachdienst"));
        notification.setSent(new Date());
        
        String statusText = String.format(
            "Der Status Ihrer E-Rechnung (ERG-Token: %s) wurde von '%s' zu '%s' geändert.", 
            ergToken, oldStatus, newStatus
        );
        
        notification.addPayload().getContentStringType().setValue(statusText);
        notification.setPriority(Communication.CommunicationPriority.ROUTINE);
        
        notification.addExtension()
            .setUrl("http://gematik.de/fhir/erg/StructureDefinition/erg-token")
            .setValue(new StringType(ergToken));
            
        notification.addExtension()
            .setUrl("http://gematik.de/fhir/erg/StructureDefinition/old-status")
            .setValue(new StringType(oldStatus));
            
        notification.addExtension()
            .setUrl("http://gematik.de/fhir/erg/StructureDefinition/new-status")
            .setValue(new StringType(newStatus));

        try {
            daoRegistry.getResourceDao(Communication.class).create(notification);
            LOGGER.info("Status-Benachrichtigung für Patient {} über ERG-Token {} erfolgreich erstellt.", patientId, ergToken);
        } catch (Exception e) {
            LOGGER.error("Fehler beim Erstellen der Status-Benachrichtigung für Patient {}: {}", patientId, e.getMessage(), e);
        }
        
        return notification;
    }
    
    private String createNotificationText(String ergToken, String leistungserbringerName) {
        return String.format(
            "Neue E-Rechnung verfügbar!\n\n" +
            "Von: %s\n" +
            "ERG-Token: %s\n" +
            "Datum: %s\n\n" +
            "Sie können die Rechnung mit dem ERG-Token über die $retrieve Operation abrufen.\n" +
            "Alternativ rufen Sie Patient/$get-erg-tokens auf, um alle verfügbaren Token zu sehen.",
            leistungserbringerName != null ? leistungserbringerName : "Unbekannt",
            ergToken,
            new Date().toString()
        );
    }
} 