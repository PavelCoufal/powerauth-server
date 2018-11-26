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

import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.app.server.converter.v3.ServerPrivateKeyConverter;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.database.model.KeyEncryptionMode;
import io.getlime.security.powerauth.app.server.database.model.entity.ActivationRecordEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationVersionEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.MasterKeyPairEntity;
import io.getlime.security.powerauth.app.server.service.behavior.util.KeyDerivationUtil;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.crypto.lib.config.PowerAuthConfiguration;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesDecryptor;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesEnvelopeKey;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesFactory;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.exception.EciesException;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.model.EciesSharedInfo1;
import io.getlime.security.powerauth.crypto.lib.model.exception.GenericCryptoException;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import io.getlime.security.powerauth.provider.exception.CryptoProviderException;
import io.getlime.security.powerauth.v3.GetEciesDecryptorRequest;
import io.getlime.security.powerauth.v3.GetEciesDecryptorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;

/**
 * Behavior class implementing the ECIES service logic.
 *
 * <h5>PowerAuth protocol versions:</h5>
 * <ul>
 *     <li>3.0</li>
 * </ul>
 *
 * @author Roman Strobl, roman.strobl@wultra.com
 */
@Component
public class EciesEncryptionBehavior {

    private final RepositoryCatalogue repositoryCatalogue;
    private final LocalizationProvider localizationProvider;
    private final ServerPrivateKeyConverter serverPrivateKeyConverter;

    // Helper classes
    private final EciesFactory eciesFactory = new EciesFactory();
    private final KeyDerivationUtil keyDerivationUtil = new KeyDerivationUtil();
    private final CryptoProviderUtil keyConvertor = PowerAuthConfiguration.INSTANCE.getKeyConvertor();

    // Prepare logger
    private static final Logger logger = LoggerFactory.getLogger(EciesEncryptionBehavior.class);

    @Autowired
    public EciesEncryptionBehavior(RepositoryCatalogue repositoryCatalogue, LocalizationProvider localizationProvider, ServerPrivateKeyConverter serverPrivateKeyConverter) {
        this.repositoryCatalogue = repositoryCatalogue;
        this.localizationProvider = localizationProvider;
        this.serverPrivateKeyConverter = serverPrivateKeyConverter;
    }

    /**
     * Obtain ECIES decryptor parameters to allow decryption of ECIES-encrypted messages on intermediate server.
     * This interface doesn't allow keys derivation, it only provides ECIES decryptor parameters used for generic
     * encryption (sharedInfo1 = /pa/generic/**).
     *
     * If activationId is not present, then it creates ECIES decryptor for application scope.
     * If activationId is present, then it creates ECIES decryptor for activation scope.
     *
     * @return ECIES decryptor parameters.
     */
    public GetEciesDecryptorResponse getEciesDecryptorParameters(GetEciesDecryptorRequest request) throws GenericServiceException {
        if (request.getActivationId() == null) {
            // Application scope
            return getEciesDecryptorParametersForApplication(request);
        } else {
            // Activation scope
            return getEciesDecryptorParametersForActivation(request);
        }
    }

    /**
     * Get ECIES decryptor parameters for application scope.
     *
     * @param request Request to get ECIES decryptor parameters.
     * @return ECIES decryptor parameters for application scope.
     * @throws GenericServiceException In case ECIES decryptor parameters could not be extracted.
     */
    private GetEciesDecryptorResponse getEciesDecryptorParametersForApplication(GetEciesDecryptorRequest request) throws GenericServiceException {
        if (request.getApplicationKey() == null || request.getEphemeralPublicKey() == null) {
            logger.warn("Invalid request for ECIES decryptor");
            throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
        }

        try {
            // Lookup the application version and check that it is supported
            final ApplicationVersionEntity applicationVersion = repositoryCatalogue.getApplicationVersionRepository().findByApplicationKey(request.getApplicationKey());
            if (applicationVersion == null || !applicationVersion.getSupported()) {
                logger.warn("Application version is incorrect, application key: {}", request.getApplicationKey());
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
            }

            // Get master private key
            final ApplicationEntity application = applicationVersion.getApplication();
            final MasterKeyPairEntity masterKeyPairEntity = repositoryCatalogue.getMasterKeyPairRepository().findFirstByApplicationIdOrderByTimestampCreatedDesc(application.getId());
            if (masterKeyPairEntity == null) {
                logger.error("Missing key pair for application ID: {}", application.getId());
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
            }

            final String masterPrivateKeyBase64 = masterKeyPairEntity.getMasterKeyPrivateBase64();
            final PrivateKey privateKey = keyConvertor.convertBytesToPrivateKey(BaseEncoding.base64().decode(masterPrivateKeyBase64));

            // Get application secret
            final byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);

            // Get decryptor for the application
            final EciesDecryptor decryptor = eciesFactory.getEciesDecryptorForApplication((ECPrivateKey) privateKey, applicationSecret, EciesSharedInfo1.APPLICATION_SCOPE_GENERIC);

            // Initialize decryptor with ephemeral public key
            byte[] ephemeralPublicKeyBytes = BaseEncoding.base64().decode(request.getEphemeralPublicKey());
            decryptor.initEnvelopeKey(ephemeralPublicKeyBytes);

            // Extract envelope key and sharedInfo2 parameters to allow decryption on intermediate server
            final EciesEnvelopeKey envelopeKey = decryptor.getEnvelopeKey();
            GetEciesDecryptorResponse response = new GetEciesDecryptorResponse();
            response.setSecretKey(BaseEncoding.base64().encode(envelopeKey.getSecretKey()));
            response.setSharedInfo2(BaseEncoding.base64().encode(decryptor.getSharedInfo2()));
            return response;
        } catch (InvalidKeySpecException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INCORRECT_MASTER_SERVER_KEYPAIR_PRIVATE);
        } catch (EciesException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
        } catch (GenericCryptoException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
        } catch (CryptoProviderException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_CRYPTO_PROVIDER);
        }
    }

    /**
     * Get ECIES decryptor parameters for activation scope.
     *
     * @param request Request to get ECIES decryptor parameters.
     * @return ECIES decryptor parameters for activation scope.
     * @throws GenericServiceException In case ECIES decryptor parameters could not be extracted.
     */
    private GetEciesDecryptorResponse getEciesDecryptorParametersForActivation(GetEciesDecryptorRequest request) throws GenericServiceException {
        if (request.getApplicationKey() == null || request.getEphemeralPublicKey() == null) {
            logger.warn("Invalid request for ECIES decryptor");
            throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
        }

        try {
            // Lookup the activation
            final ActivationRecordEntity activation = repositoryCatalogue.getActivationRepository().findActivationWithoutLock(request.getActivationId());
            if (activation == null) {
                logger.info("Activation does not exist, activation ID: {}", request.getActivationId());
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
            }

            // Check if the activation is in correct state
            if (!ActivationStatus.ACTIVE.equals(activation.getActivationStatus())) {
                logger.info("Activation is not ACTIVE, activation ID: {}", request.getActivationId());
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
            }

            // Lookup the application version and check that it is supported
            final ApplicationVersionEntity applicationVersion = repositoryCatalogue.getApplicationVersionRepository().findByApplicationKey(request.getApplicationKey());
            if (applicationVersion == null || !applicationVersion.getSupported()) {
                logger.warn("Application version is incorrect, application key: {}", request.getApplicationKey());
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
            }

            // Check that application key from request belongs to same application as activation ID from request
            if (!applicationVersion.getApplication().getId().equals(activation.getApplication().getId())) {
                logger.warn("Application version is does not match, application key: {}", request.getApplicationKey());
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
            }

            // Get the server private key, decrypt it if required
            final String serverPrivateKeyFromEntity = activation.getServerPrivateKeyBase64();
            final KeyEncryptionMode serverPrivateKeyEncryptionMode = activation.getServerPrivateKeyEncryption();
            final String serverPrivateKeyBase64 = serverPrivateKeyConverter.fromDBValue(serverPrivateKeyEncryptionMode, serverPrivateKeyFromEntity, activation.getUserId(), activation.getActivationId());
            final byte[] serverPrivateKey = BaseEncoding.base64().decode(serverPrivateKeyBase64);
            final PrivateKey privateKey = keyConvertor.convertBytesToPrivateKey(serverPrivateKey);

            // Get application secret and transport key used in sharedInfo2 parameter of ECIES
            final byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);
            final byte[] devicePublicKey = BaseEncoding.base64().decode(activation.getDevicePublicKeyBase64());
            final byte[] transportKey = keyDerivationUtil.deriveTransportKey(serverPrivateKey, devicePublicKey);

            // Get decryptor for the activation
            final EciesDecryptor decryptor = eciesFactory.getEciesDecryptorForActivation((ECPrivateKey) privateKey, applicationSecret, transportKey, EciesSharedInfo1.ACTIVATION_SCOPE_GENERIC);

            // Initialize decryptor with ephemeral public key
            byte[] ephemeralPublicKeyBytes = BaseEncoding.base64().decode(request.getEphemeralPublicKey());
            decryptor.initEnvelopeKey(ephemeralPublicKeyBytes);

            // Extract envelope key and sharedInfo2 parameters to allow decryption on intermediate server
            final EciesEnvelopeKey envelopeKey = decryptor.getEnvelopeKey();
            GetEciesDecryptorResponse response = new GetEciesDecryptorResponse();
            response.setSecretKey(BaseEncoding.base64().encode(envelopeKey.getSecretKey()));
            response.setSharedInfo2(BaseEncoding.base64().encode(decryptor.getSharedInfo2()));
            return response;
        } catch (InvalidKeyException | InvalidKeySpecException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (EciesException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.DECRYPTION_FAILED);
        } catch (GenericCryptoException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
        } catch (CryptoProviderException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_CRYPTO_PROVIDER);
        }
    }

}