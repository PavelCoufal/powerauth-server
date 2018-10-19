/*
 * PowerAuth Server and related software components
 * Copyright (C) 2018 Wultra s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.getlime.security.powerauth.app.server.service.behavior.tasks.v3;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.app.server.configuration.PowerAuthServiceConfiguration;
import io.getlime.security.powerauth.app.server.converter.v3.ServerPrivateKeyConverter;
import io.getlime.security.powerauth.app.server.converter.v3.SignatureTypeConverter;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.database.model.KeyEncryptionMode;
import io.getlime.security.powerauth.app.server.database.model.entity.ActivationRecordEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationVersionEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.TokenEntity;
import io.getlime.security.powerauth.app.server.service.behavior.util.KeyDerivationUtil;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.app.server.service.model.TokenInfo;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesDecryptor;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesFactory;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.exception.EciesException;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.model.EciesCryptogram;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.model.EciesSharedInfo1;
import io.getlime.security.powerauth.crypto.server.token.ServerTokenGenerator;
import io.getlime.security.powerauth.crypto.server.token.ServerTokenVerifier;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import io.getlime.security.powerauth.v3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Calendar;
import java.util.Optional;

/**
 * Behavior that contains methods related to simple token-based authentication.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component("TokenBehavior")
public class TokenBehavior {

    private final RepositoryCatalogue repositoryCatalogue;
    private final LocalizationProvider localizationProvider;
    private final PowerAuthServiceConfiguration powerAuthServiceConfiguration;
    private final ServerPrivateKeyConverter serverPrivateKeyConverter;

    // Business logic implementation classes
    private final ServerTokenGenerator tokenGenerator = new ServerTokenGenerator();
    private final ServerTokenVerifier tokenVerifier = new ServerTokenVerifier();
    private final EciesFactory eciesFactory = new EciesFactory();

    // Helper classes
    private final SignatureTypeConverter signatureTypeConverter = new SignatureTypeConverter();
    private final KeyDerivationUtil keyDerivationUtil = new KeyDerivationUtil();

    @Autowired
    public TokenBehavior(RepositoryCatalogue repositoryCatalogue, LocalizationProvider localizationProvider, PowerAuthServiceConfiguration powerAuthServiceConfiguration, ServerPrivateKeyConverter serverPrivateKeyConverter) {
        this.repositoryCatalogue = repositoryCatalogue;
        this.localizationProvider = localizationProvider;
        this.powerAuthServiceConfiguration = powerAuthServiceConfiguration;
        this.serverPrivateKeyConverter = serverPrivateKeyConverter;
    }

    /**
     * Method that creates a new token provided activation.
     *
     * <h5>PowerAuth protocol versions:</h5>
     * <ul>
     *     <li>3.0</li>
     * </ul>
     *
     * @param request Request with the activation ID, signature type and ephemeral public key.
     * @param keyConversion Key conversion utility class.
     * @return Response with a newly created token information (ECIES encrypted).
     * @throws GenericServiceException In case a business error occurs.
     */
    public CreateTokenResponse createToken(CreateTokenRequest request, CryptoProviderUtil keyConversion) throws GenericServiceException {
        final String activationId = request.getActivationId();
        final String applicationKey = request.getApplicationKey();
        final byte[] ephemeralPublicKey = BaseEncoding.base64().decode(request.getEphemeralPublicKey());
        final byte[] encryptedData = BaseEncoding.base64().decode(request.getEncryptedData());
        final byte[] mac = BaseEncoding.base64().decode(request.getMac());
        final SignatureType signatureType = request.getSignatureType();

        // Convert received ECIES request data to cryptogram
        final EciesCryptogram cryptogram = new EciesCryptogram(ephemeralPublicKey, mac, encryptedData);

        EciesCryptogram encryptedCryptogram = createToken(activationId, applicationKey, cryptogram, signatureType.value(), keyConversion);

        final CreateTokenResponse response = new CreateTokenResponse();
        response.setMac(BaseEncoding.base64().encode(encryptedCryptogram.getMac()));
        response.setEncryptedData(BaseEncoding.base64().encode(encryptedCryptogram.getEncryptedData()));
        return response;
    }

    /**
     * Create a new token implementation.
     *
     * @param activationId Activation ID.
     * @param applicationKey Application key.
     * @param cryptogram ECIES cryptogram.
     * @param signatureType Signature type.
     * @param keyConversion Key conversion utility class.
     * @return Response with a newly created token information (ECIES encrypted).
     * @throws GenericServiceException In case a business error occurs.
     */
    private EciesCryptogram createToken(String activationId, String applicationKey, EciesCryptogram cryptogram, String signatureType, CryptoProviderUtil keyConversion) throws GenericServiceException {
        try {
            // Lookup the activation
            final ActivationRecordEntity activation = repositoryCatalogue.getActivationRepository().findActivation(activationId);
            if (activation == null) {
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
            }

            // Check if the activation is in correct state
            if (!ActivationStatus.ACTIVE.equals(activation.getActivationStatus())) {
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
            }

            // Get the server private key, decrypt it if required
            final String serverPrivateKeyFromEntity = activation.getServerPrivateKeyBase64();
            final KeyEncryptionMode serverPrivateKeyEncryptionMode = activation.getServerPrivateKeyEncryption();
            final String serverPrivateKeyBase64 = serverPrivateKeyConverter.fromDBValue(serverPrivateKeyEncryptionMode, serverPrivateKeyFromEntity, activation.getUserId(), activation.getActivationId());
            byte[] serverPrivateKey = BaseEncoding.base64().decode(serverPrivateKeyBase64);

            // KEY_SERVER_PRIVATE is used in Crypto version 3.0 for ECIES, note that in version 2.0 KEY_SERVER_MASTER_PRIVATE is used
            final PrivateKey privateKey = keyConversion.convertBytesToPrivateKey(serverPrivateKey);

            // Get application secret and transport key used in sharedInfo2 parameter of ECIES
            final ApplicationVersionEntity applicationVersion = repositoryCatalogue.getApplicationVersionRepository().findByApplicationKey(applicationKey);
            byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);
            byte[] devicePublicKey = BaseEncoding.base64().decode(activation.getDevicePublicKeyBase64());
            byte[] transportKey = keyDerivationUtil.deriveTransportKey(serverPrivateKey, devicePublicKey);

            // Get decryptor for the activation
            final EciesDecryptor decryptor = eciesFactory.getEciesDecryptorForActivation((ECPrivateKey) privateKey,
                    applicationSecret, transportKey, EciesSharedInfo1.CREATE_TOKEN);

            // Try to decrypt request data, the data must not be empty. Currently only '{}' is sent in request data.
            final byte[] decryptedData = decryptor.decryptRequest(cryptogram);
            if (decryptedData.length == 0) {
                throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
            }

            // Generate unique token ID.
            String tokenId = null;
            for (int i = 0; i < powerAuthServiceConfiguration.getGenerateTokenIdIterations(); i++) {
                String tmpTokenId = tokenGenerator.generateTokenId();
                final Optional<TokenEntity> tmpTokenOptional = repositoryCatalogue.getTokenRepository().findById(tmpTokenId);
                if (!tmpTokenOptional.isPresent()) {
                    tokenId = tmpTokenId;
                    break;
                } // ... else this token ID has a collision, reset it and try to find another one
            }
            if (tokenId == null) {
                throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_TOKEN);
            }

            // Create a new token
            TokenEntity token = new TokenEntity();
            token.setTokenId(tokenId);
            token.setTokenSecret(BaseEncoding.base64().encode(tokenGenerator.generateTokenSecret()));
            token.setActivation(activation);
            token.setTimestampCreated(Calendar.getInstance().getTime());
            token.setSignatureTypeCreated(signatureType);
            token = repositoryCatalogue.getTokenRepository().save(token);

            final TokenInfo tokenInfo = new TokenInfo();
            tokenInfo.setTokenId(token.getTokenId());
            tokenInfo.setTokenSecret(token.getTokenSecret());

            final ObjectMapper mapper = new ObjectMapper();
            final byte[] tokenBytes = mapper.writeValueAsBytes(tokenInfo);

            // Encrypt response using previously created ECIES decryptor
            return decryptor.encryptResponse(tokenBytes);
        } catch (InvalidKeySpecException e) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INCORRECT_MASTER_SERVER_KEYPAIR_PRIVATE);
        } catch (EciesException | InvalidKeyException e) {
            throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
        } catch (JsonProcessingException e) {
            throw localizationProvider.buildExceptionForCode(ServiceError.UNKNOWN_ERROR);
        }
    }

    /**
     * Method that validates provided token-based authentication credentials.
     *
     * @param request Request with the token-based authentication credentials.
     * @return Response with the validation results.
     * @throws GenericServiceException In case of the business logic error.
     */
    public ValidateTokenResponse validateToken(ValidateTokenRequest request) throws GenericServiceException {

        final String tokenId = request.getTokenId();
        final byte[] nonce = BaseEncoding.base64().decode(request.getNonce());
        final byte[] timestamp = tokenVerifier.convertTokenTimestamp(request.getTimestamp());
        final byte[] tokenDigest = BaseEncoding.base64().decode(request.getTokenDigest());

        // Lookup the token.
        final Optional<TokenEntity> tokenEntityOptional = repositoryCatalogue.getTokenRepository().findById(tokenId);
        if (!tokenEntityOptional.isPresent()) {
            final ValidateTokenResponse response = new ValidateTokenResponse();
            response.setTokenValid(false);
            return response;
        }
        final TokenEntity token = tokenEntityOptional.get();

        // Check if the activation is in correct state
        final ActivationRecordEntity activation = token.getActivation();
        if (!ActivationStatus.ACTIVE.equals(activation.getActivationStatus())) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
        }

        final byte[] tokenSecret = BaseEncoding.base64().decode(token.getTokenSecret());

        final boolean isTokenValid = tokenVerifier.validateTokenDigest(nonce, timestamp, tokenSecret, tokenDigest);

        if (isTokenValid) {
            final ValidateTokenResponse response = new ValidateTokenResponse();
            response.setTokenValid(true);
            response.setActivationId(activation.getActivationId());
            response.setApplicationId(activation.getApplication().getId());
            response.setUserId(activation.getUserId());
            response.setSignatureType(signatureTypeConverter.convertFrom(token.getSignatureTypeCreated()));
            return response;
        } else {
            final ValidateTokenResponse response = new ValidateTokenResponse();
            response.setTokenValid(false);
            return response;
        }

    }

    /**
     * Remove token with provided ID.
     *
     * @param request Request with token ID.
     * @return Token removal response.
     */
    public RemoveTokenResponse removeToken(RemoveTokenRequest request) {
        String tokenId = request.getTokenId();
        boolean removed = false;

        final Optional<TokenEntity> tokenEntityOptional = repositoryCatalogue.getTokenRepository().findById(tokenId);

        // Token was found and activation ID corresponds to the correct user.
        if (tokenEntityOptional.isPresent()) {
            final TokenEntity token = tokenEntityOptional.get();
            if (token.getActivation().getActivationId().equals(request.getActivationId())) {
                repositoryCatalogue.getTokenRepository().delete(token);
                removed = true;
            }
        }

        RemoveTokenResponse response = new RemoveTokenResponse();
        response.setRemoved(removed);

        return response;
    }
}
