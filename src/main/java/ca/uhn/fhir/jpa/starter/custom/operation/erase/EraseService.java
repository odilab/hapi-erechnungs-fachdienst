package ca.uhn.fhir.jpa.starter.custom.operation.erase;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.custom.interceptor.auth.AccessToken;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class EraseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EraseService.class);
    private static final String RECHNUNGSSTATUS_SYSTEM = "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs";
    // Der genaue Code für "Papierkorb" sollte hier basierend auf der Spezifikation stehen.
    // Annahme aus erase.txt: "papierkorb"
    private static final String RECHNUNGSSTATUS_CODE_PAPIERKORB = "papierkorb";
    private static final String RELATES_TO_CODE_TRANSFORMS = "transforms";

    private final DaoRegistry daoRegistry;

    @Autowired
    public EraseService(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    private static class ResourcesToDelete {
        Set<IdType> documentReferenceIds = new HashSet<>();
        Set<IdType> binaryIds = new HashSet<>();
        Set<IdType> invoiceIds = new HashSet<>();

        void add(IdType id) {
            if (id == null || !id.hasResourceType() || !id.hasIdPart()) return;
            switch (id.getResourceType()) {
                case "DocumentReference": documentReferenceIds.add(id.toUnqualifiedVersionless()); break;
                case "Binary": binaryIds.add(id.toUnqualifiedVersionless()); break;
                case "Invoice": invoiceIds.add(id.toUnqualifiedVersionless()); break;
                default: LOGGER.warn("Sammeln zum Löschen: Unbehandelter Ressourcentyp {} für ID {}", id.getResourceType(), id.getValue()); break;
            }
        }
    }

    /**
     * Führt die Löschung der DocumentReference und aller assoziierten Daten durch.
     * Diese Methode sollte transaktional aufgerufen werden.
     *
     * @param documentReferenceToExpunge Die zu löschende DocumentReference-Ressource.
     * @param accessToken               Der AccessToken des aufrufenden Benutzers (für Audit-Zwecke oder feinere Logik).
     */
    @Transactional
    public void eraseDocumentReferenceAndAssociations(DocumentReference documentReferenceToExpunge, AccessToken accessToken) {
        IdType docRefIdToExpunge = documentReferenceToExpunge.getIdElement().toUnqualifiedVersionless();
        LOGGER.info("EraseService: Beginn der Löschoperation für DocumentReference ID: {} durch Nutzer mit KVNR (aus Token): {}",
                docRefIdToExpunge.getValue(), accessToken.getKvnr().orElse("nicht vorhanden"));

        // 1. Statusprüfung (muss "PAPIERKORB" sein)
        checkDocumentStatus(documentReferenceToExpunge);

        // 2. Sammle alle zu löschenden Ressourcen-IDs
        ResourcesToDelete allResourcesToDelete = new ResourcesToDelete();
        collectAllAssociatedResourceIds(documentReferenceToExpunge, allResourcesToDelete, true);

        // 3. Lösche die Papierkorb-DocumentReference zuerst, um Referenzkonflikte aufzulösen
        LOGGER.debug("Lösche Haupt-DocumentReference (Papierkorb-Version) ID: {}", docRefIdToExpunge.getValue());
        try {
            daoRegistry.getResourceDao(DocumentReference.class).delete(docRefIdToExpunge);
            LOGGER.info("Haupt-DocumentReference (Papierkorb-Version) ID: {} erfolgreich gelöscht.", docRefIdToExpunge.getValue());
        } catch (Exception e) {
            LOGGER.error("Fehler beim Löschen der Haupt-DocumentReference (Papierkorb-Version) ID {}: {}", docRefIdToExpunge.getValue(), e.getMessage(), e);
            throw new InternalErrorException("Konnte Haupt-DocumentReference (Papierkorb-Version) " + docRefIdToExpunge.getValue() + " nicht löschen: " + e.getMessage(), e);
        }
        // Entferne die soeben gelöschte Haupt-DR aus der Liste, falls sie gesammelt wurde (sollte nicht, da wir sie separat behandeln)
        allResourcesToDelete.documentReferenceIds.remove(docRefIdToExpunge);

        // 4. Lösche alle gesammelten assoziierten Ressourcen
        deleteCollectedResources(allResourcesToDelete);

        LOGGER.info("Erase-Operation für ursprüngliche DocumentReference ID: {} und alle assoziierten Ressourcen erfolgreich abgeschlossen.", docRefIdToExpunge.getValue());
    }

    private void collectAllAssociatedResourceIds(DocumentReference currentDocRef, ResourcesToDelete collector, boolean isPapierkorbIteration) {
        if (currentDocRef == null) return;
        IdType currentDocRefId = currentDocRef.getIdElement().toUnqualifiedVersionless();
        LOGGER.debug("Sammle Ressourcen für DocumentReference ID: {}", currentDocRefId.getValue());

        // Assoziierte Binaries und Invoices aus currentDocRef.content
        if (currentDocRef.hasContent()) {
            for (DocumentReference.DocumentReferenceContentComponent content : currentDocRef.getContent()) {
                if (content.hasAttachment() && content.getAttachment().hasUrl()) {
                    collector.add(new IdType(content.getAttachment().getUrl()));
                }
            }
        }

        // Verlinkte Anhang-DocumentReferences aus currentDocRef.context.related (rekursiv)
        if (currentDocRef.hasContext() && currentDocRef.getContext().hasRelated()) {
            for (Reference relatedRef : currentDocRef.getContext().getRelated()) {
                if ("DocumentReference".equals(relatedRef.getReferenceElement().getResourceType()) && relatedRef.getReferenceElement().hasIdPart()) {
                    IdType anhangDocRefId = new IdType(relatedRef.getReferenceElement().getValue());
                    if (!collector.documentReferenceIds.contains(anhangDocRefId)) { // Verhindere Endlosschleifen und doppeltes Sammeln
                        collector.add(anhangDocRefId);
                        try {
                            DocumentReference anhangDocRef = daoRegistry.getResourceDao(DocumentReference.class).read(anhangDocRefId);
                            if (anhangDocRef != null) {
                                collectAllAssociatedResourceIds(anhangDocRef, collector, false); // Nicht mehr die Papierkorb-Iteration
                            }
                        } catch (ResourceNotFoundException e) {
                            LOGGER.warn("Anhang DocumentReference {} beim Sammeln nicht gefunden.", anhangDocRefId.getValue());
                        }
                    }
                }
            }
        }

        // Originale DocumentReference aus currentDocRef.relatesTo (rekursiv, aber nur einmal für die initiale Papierkorb-DR)
        if (isPapierkorbIteration && currentDocRef.hasRelatesTo()) {
            for (DocumentReference.DocumentReferenceRelatesToComponent relatesTo : currentDocRef.getRelatesTo()) {
                if (RELATES_TO_CODE_TRANSFORMS.equals(relatesTo.getCode().toCode()) && relatesTo.hasTarget()) {
                    Reference targetRef = relatesTo.getTarget();
                    if ("DocumentReference".equals(targetRef.getReferenceElement().getResourceType()) && targetRef.getReferenceElement().hasIdPart()) {
                        IdType originalDocRefId = new IdType(targetRef.getReferenceElement().getValue());
                        if (!collector.documentReferenceIds.contains(originalDocRefId)) { // Verhindere Endlosschleifen
                            collector.add(originalDocRefId);
                            try {
                                DocumentReference originalDocRef = daoRegistry.getResourceDao(DocumentReference.class).read(originalDocRefId);
                                if (originalDocRef != null) {
                                    collectAllAssociatedResourceIds(originalDocRef, collector, false); // Nicht mehr die Papierkorb-Iteration
                                }
                            } catch (ResourceNotFoundException e) {
                                LOGGER.warn("Original DocumentReference {} (via relatesTo) beim Sammeln nicht gefunden.", originalDocRefId.getValue());
                            }
                        }
                    }
                }
            }
        }
    }

    private void deleteCollectedResources(ResourcesToDelete collector) {
        // Lösche zuerst Binaries und Invoices, dann DocumentReferences, um Abhängigkeiten aufzulösen
        for (IdType binaryId : collector.binaryIds) {
            try {
                daoRegistry.getResourceDao(Binary.class).delete(binaryId);
                LOGGER.info("Gesammelte Binary {} erfolgreich gelöscht.", binaryId.getValue());
            } catch (ResourceNotFoundException e) {
                LOGGER.warn("Gesammelte Binary {} beim Löschen nicht gefunden.", binaryId.getValue());
            } catch (Exception e) {
                LOGGER.error("Fehler beim Löschen der gesammelten Binary {}: {}", binaryId.getValue(), e.getMessage(), e);
                throw new InternalErrorException("Fehler beim Löschen einer gesammelten Binary " + binaryId.getValue() + ": " + e.getMessage(), e);
            }
        }
        for (IdType invoiceId : collector.invoiceIds) {
            try {
                daoRegistry.getResourceDao(Invoice.class).delete(invoiceId);
                LOGGER.info("Gesammelte Invoice {} erfolgreich gelöscht.", invoiceId.getValue());
            } catch (ResourceNotFoundException e) {
                LOGGER.warn("Gesammelte Invoice {} beim Löschen nicht gefunden.", invoiceId.getValue());
            } catch (Exception e) {
                LOGGER.error("Fehler beim Löschen der gesammelten Invoice {}: {}", invoiceId.getValue(), e.getMessage(), e);
                throw new InternalErrorException("Fehler beim Löschen einer gesammelten Invoice " + invoiceId.getValue() + ": " + e.getMessage(), e);
            }
        }
        for (IdType docRefId : collector.documentReferenceIds) {
            try {
                daoRegistry.getResourceDao(DocumentReference.class).delete(docRefId);
                LOGGER.info("Gesammelte DocumentReference {} erfolgreich gelöscht.", docRefId.getValue());
            } catch (ResourceNotFoundException e) {
                LOGGER.warn("Gesammelte DocumentReference {} beim Löschen nicht gefunden.", docRefId.getValue());
            } catch (Exception e) {
                LOGGER.error("Fehler beim Löschen der gesammelten DocumentReference {}: {}", docRefId.getValue(), e.getMessage(), e);
                // Wenn hier ein Fehler auftritt (z.B. wegen noch bestehender Referenz), wird die Transaktion zurückgerollt.
                throw new InternalErrorException("Fehler beim Löschen einer gesammelten DocumentReference " + docRefId.getValue() + ": " + e.getMessage(), e);
            }
        }
    }

    private void checkDocumentStatus(DocumentReference documentReference) {
        boolean isInPapierkorb = false;
        if (documentReference.hasMeta() && documentReference.getMeta().hasTag()) {
            for (Coding tag : documentReference.getMeta().getTag()) {
                if (RECHNUNGSSTATUS_SYSTEM.equals(tag.getSystem()) && RECHNUNGSSTATUS_CODE_PAPIERKORB.equals(tag.getCode())) {
                    isInPapierkorb = true;
                    break;
                }
            }
        }

        if (!isInPapierkorb) {
            String message = "DocumentReference mit ID " + documentReference.getIdElement().getIdPart() +
                             " befindet sich nicht im Status 'PAPIERKORB' (erwarteter Code: '" + RECHNUNGSSTATUS_CODE_PAPIERKORB +
                             "' im System '" + RECHNUNGSSTATUS_SYSTEM + "') und kann daher nicht über $erase gelöscht werden.";
            LOGGER.warn(message);
            throw new InvalidRequestException(message);
        }
        LOGGER.info("Statusprüfung für DocumentReference ID {} erfolgreich (Status ist 'PAPIERKORB').", documentReference.getIdElement().getIdPart());
    }
}
