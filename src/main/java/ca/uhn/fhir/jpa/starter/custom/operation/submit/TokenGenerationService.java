package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;
import org.hl7.fhir.r4.model.DocumentReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.security.NoSuchAlgorithmException;

@Service
public class TokenGenerationService {

    private final DaoRegistry daoRegistry;

    @Autowired
    public TokenGenerationService(DaoRegistry daoRegistry) {
        this.daoRegistry = daoRegistry;
    }

    public String generateUniqueToken() {
        while (true) {
            // 32 Bytes für 64 Hex-Zeichen
            byte[] randomBytes = new byte[32];
            try {
                SecureRandom.getInstanceStrong().nextBytes(randomBytes);
            } catch (NoSuchAlgorithmException e) {
                // Consider logging the error
                throw new RuntimeException("Konnte keinen kryptographisch sicheren Zufallsgenerator initialisieren", e);
            }

            // Zu Hex-String konvertieren
            StringBuilder hexString = new StringBuilder();
            for (byte b : randomBytes) {
                hexString.append(String.format("%02x", b));
            }

            String token = hexString.toString();

            // Prüfe ob Token bereits verwendet wird
            SearchParameterMap paramMap = new SearchParameterMap();
            paramMap.add(DocumentReference.SP_IDENTIFIER,
                    new TokenParam("https://gematik.de/fhir/sid/erg-token", token));

            IBundleProvider results = daoRegistry.getResourceDao(DocumentReference.class)
                    .search(paramMap);

            if (results.size() == 0) {
                return token;
            }
        }
    }
} 