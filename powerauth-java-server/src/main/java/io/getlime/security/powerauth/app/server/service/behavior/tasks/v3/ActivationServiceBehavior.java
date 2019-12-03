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
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.app.server.configuration.PowerAuthServiceConfiguration;
import io.getlime.security.powerauth.app.server.converter.v3.ActivationStatusConverter;
import io.getlime.security.powerauth.app.server.converter.v3.RecoveryPukConverter;
import io.getlime.security.powerauth.app.server.converter.v3.ServerPrivateKeyConverter;
import io.getlime.security.powerauth.app.server.converter.v3.XMLGregorianCalendarConverter;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.database.model.RecoveryCodeStatus;
import io.getlime.security.powerauth.app.server.database.model.RecoveryPukStatus;
import io.getlime.security.powerauth.app.server.database.model.*;
import io.getlime.security.powerauth.app.server.database.model.entity.*;
import io.getlime.security.powerauth.app.server.database.repository.*;
import io.getlime.security.powerauth.app.server.service.behavior.ServiceBehaviorCatalogue;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ActivationRecovery;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.app.server.service.model.request.ActivationLayer2Request;
import io.getlime.security.powerauth.app.server.service.model.response.ActivationLayer2Response;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesDecryptor;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.EciesFactory;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.exception.EciesException;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.model.EciesCryptogram;
import io.getlime.security.powerauth.crypto.lib.encryptor.ecies.model.EciesSharedInfo1;
import io.getlime.security.powerauth.crypto.lib.generator.HashBasedCounter;
import io.getlime.security.powerauth.crypto.lib.generator.IdentifierGenerator;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.crypto.lib.model.ActivationStatusBlobInfo;
import io.getlime.security.powerauth.crypto.lib.model.RecoveryInfo;
import io.getlime.security.powerauth.crypto.lib.model.exception.GenericCryptoException;
import io.getlime.security.powerauth.crypto.lib.util.HashBasedCounterUtils;
import io.getlime.security.powerauth.crypto.lib.util.PasswordHash;
import io.getlime.security.powerauth.crypto.server.activation.PowerAuthServerActivation;
import io.getlime.security.powerauth.crypto.server.keyfactory.PowerAuthServerKeyFactory;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import io.getlime.security.powerauth.provider.exception.CryptoProviderException;
import io.getlime.security.powerauth.v3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.*;

/**
 * Behavior class implementing processes related with activations. Used to move the
 * implementation outside of the main service implementation.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component("activationServiceBehavior")
public class ActivationServiceBehavior {

    /**
     * Current PowerAuth protocol major version. Activations created with lower version will be upgraded to this version.
     */
    private static final byte POWERAUTH_PROTOCOL_VERSION = 0x3;

    private final RepositoryCatalogue repositoryCatalogue;
    private final ServiceBehaviorCatalogue behaviorCatalogue;

    private CallbackUrlBehavior callbackUrlBehavior;

    private ActivationHistoryServiceBehavior activationHistoryServiceBehavior;

    private LocalizationProvider localizationProvider;

    private final PowerAuthServiceConfiguration powerAuthServiceConfiguration;

    // Prepare converters
    private final ActivationStatusConverter activationStatusConverter = new ActivationStatusConverter();
    private ServerPrivateKeyConverter serverPrivateKeyConverter;
    private RecoveryPukConverter recoveryPukConverter;

    // Helper classes
    private final EciesFactory eciesFactory = new EciesFactory();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IdentifierGenerator identifierGenerator = new IdentifierGenerator();

    // Prepare logger
    private static final Logger logger = LoggerFactory.getLogger(ActivationServiceBehavior.class);

    @Autowired
    public ActivationServiceBehavior(RepositoryCatalogue repositoryCatalogue, ServiceBehaviorCatalogue behaviorCatalogue, PowerAuthServiceConfiguration powerAuthServiceConfiguration) {
        this.repositoryCatalogue = repositoryCatalogue;
        this.behaviorCatalogue = behaviorCatalogue;
        this.powerAuthServiceConfiguration = powerAuthServiceConfiguration;
    }

    @Autowired
    public void setCallbackUrlBehavior(CallbackUrlBehavior callbackUrlBehavior) {
        this.callbackUrlBehavior = callbackUrlBehavior;
    }

    @Autowired
    public void setLocalizationProvider(LocalizationProvider localizationProvider) {
        this.localizationProvider = localizationProvider;
    }

    @Autowired
    public void setActivationHistoryServiceBehavior(ActivationHistoryServiceBehavior activationHistoryServiceBehavior) {
        this.activationHistoryServiceBehavior = activationHistoryServiceBehavior;
    }

    @Autowired
    public void setServerPrivateKeyConverter(ServerPrivateKeyConverter serverPrivateKeyConverter) {
        this.serverPrivateKeyConverter = serverPrivateKeyConverter;
    }

    @Autowired
    public void setRecoveryPukConverter(RecoveryPukConverter recoveryPukConverter) {
        this.recoveryPukConverter = recoveryPukConverter;
    }

    private final PowerAuthServerKeyFactory powerAuthServerKeyFactory = new PowerAuthServerKeyFactory();
    private final PowerAuthServerActivation powerAuthServerActivation = new PowerAuthServerActivation();

    /**
     * Deactivate the activation in CREATED or OTP_USED if it's activation expiration timestamp
     * is below the given timestamp.
     *
     * @param timestamp  Timestamp to check activations against.
     * @param activation Activation to check.
     */
    private void deactivatePendingActivation(Date timestamp, ActivationRecordEntity activation, boolean isActivationLocked) {
        if ((activation.getActivationStatus().equals(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.CREATED) || activation.getActivationStatus().equals(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.OTP_USED)) && (timestamp.getTime() > activation.getTimestampActivationExpire().getTime())) {
            if (!isActivationLocked) {
                // Make sure activation is locked until the end of transaction in case it was not locked yet
                activation = repositoryCatalogue.getActivationRepository().findActivationWithLock(activation.getActivationId());
            }
            activation.setActivationStatus(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.REMOVED);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
        }
    }

    /**
     * Handle case when public key is invalid. Remove provided activation (mark as REMOVED),
     * notify callback listeners, and throw an exception.
     *
     * @param activation Activation to be removed.
     * @throws GenericServiceException Error caused by invalid public key.
     */
    private void handleInvalidPublicKey(ActivationRecordEntity activation) throws GenericServiceException {
        activation.setActivationStatus(ActivationStatus.REMOVED);
        activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
        callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
        logger.warn("Invalid public key, activation ID: {}", activation.getActivationId());
        throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
    }

    /**
     * Validate activation in prepare or create activation step: it should be in CREATED state, it should be linked to correct
     * application and the activation code should have valid length.
     *
     * @param activation Activation used in prepare activation step.
     * @param application Application used in prepare activation step.
     * @throws GenericServiceException In case activation state is invalid.
     */
    private void validateCreatedActivation(ActivationRecordEntity activation, ApplicationEntity application) throws GenericServiceException {
        // If there is no such activation or application does not match the activation application, fail validation
        if (activation == null
                || !ActivationStatus.CREATED.equals(activation.getActivationStatus())
                || !Objects.equals(activation.getApplication().getId(), application.getId())) {
            logger.info("Activation state is invalid, activation ID: {}", activation != null ? activation.getActivationId() : "unknown");
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }

        // Make sure activation code has 23 characters
        if (!identifierGenerator.validateActivationCode(activation.getActivationCode())) {
            logger.info("Activation code is invalid, activation ID: {}", activation.getActivationId());
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
        }
    }

    /**
     * Get activations for application ID and user ID
     *
     * @param applicationId Application ID
     * @param userId        User ID
     * @return Response with list of matching activations
     * @throws DatatypeConfigurationException If calendar conversion fails.
     */
    public GetActivationListForUserResponse getActivationList(Long applicationId, String userId) throws DatatypeConfigurationException {

        // Generate timestamp in advance
        Date timestamp = new Date();

        // Get the repository
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();

        List<ActivationRecordEntity> activationsList;
        if (applicationId == null) {
            activationsList = activationRepository.findByUserId(userId);
        } else {
            activationsList = activationRepository.findByApplicationIdAndUserId(applicationId, userId);
        }

        GetActivationListForUserResponse response = new GetActivationListForUserResponse();
        response.setUserId(userId);
        if (activationsList != null) {
            for (ActivationRecordEntity activation : activationsList) {

                deactivatePendingActivation(timestamp, activation, false);

                // Map between database object and service objects
                GetActivationListForUserResponse.Activations activationServiceItem = new GetActivationListForUserResponse.Activations();
                activationServiceItem.setActivationId(activation.getActivationId());
                activationServiceItem.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
                activationServiceItem.setBlockedReason(activation.getBlockedReason());
                activationServiceItem.setActivationName(activation.getActivationName());
                activationServiceItem.setExtras(activation.getExtras());
                activationServiceItem.setTimestampCreated(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampCreated()));
                activationServiceItem.setTimestampLastUsed(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastUsed()));
                activationServiceItem.setTimestampLastChange(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastChange()));
                activationServiceItem.setUserId(activation.getUserId());
                activationServiceItem.setApplicationId(activation.getApplication().getId());
                activationServiceItem.setApplicationName(activation.getApplication().getName());
                // Unknown version is converted to 0 in SOAP
                activationServiceItem.setVersion(activation.getVersion() == null ? 0L : activation.getVersion());
                response.getActivations().add(activationServiceItem);
            }
        }
        return response;
    }

    /**
     * Lookup activations using various query parameters.
     *
     * @param userIds User IDs to be used in the activations query.
     * @param applicationIds Application IDs to be used in the activations query (optional).
     * @param timestampLastUsedBefore Last used timestamp to be used in the activations query, return all records where timestampLastUsed &lt; timestampLastUsedBefore (optional).
     * @param timestampLastUsedAfter Last used timestamp to be used in the activations query, return all records where timestampLastUsed &gt;= timestampLastUsedAfter (optional).
     * @param activationStatus Activation status to be used in the activations query (optional).
     * @return Response with list of matching activations.
     * @throws DatatypeConfigurationException If calendar conversion fails.
     */
    public LookupActivationsResponse lookupActivations(List<String> userIds, List<Long> applicationIds, Date timestampLastUsedBefore, Date timestampLastUsedAfter, ActivationStatus activationStatus) throws DatatypeConfigurationException {
        final LookupActivationsResponse response = new LookupActivationsResponse();
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
        final ApplicationRepository applicationRepository = repositoryCatalogue.getApplicationRepository();
        if (applicationIds != null && applicationIds.isEmpty()) {
            // Make sure application ID list is null in case no application ID is specified
            applicationIds = null;
        }
        List<ActivationStatus> statuses = new ArrayList<>();
        if (activationStatus == null) {
            // In case activation status is not specified, consider all statuses
            statuses.addAll(Arrays.asList(ActivationStatus.values()));
        } else {
            statuses.add(activationStatus);
        }
        List<ActivationRecordEntity> activationsList = activationRepository.lookupActivations(userIds, applicationIds, timestampLastUsedBefore, timestampLastUsedAfter, statuses);

        if (activationsList != null) {
            for (ActivationRecordEntity activation : activationsList) {
                // Map between database object and service objects
                LookupActivationsResponse.Activations activationServiceItem = new LookupActivationsResponse.Activations();
                activationServiceItem.setActivationId(activation.getActivationId());
                activationServiceItem.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
                activationServiceItem.setBlockedReason(activation.getBlockedReason());
                activationServiceItem.setActivationName(activation.getActivationName());
                activationServiceItem.setExtras(activation.getExtras());
                activationServiceItem.setTimestampCreated(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampCreated()));
                activationServiceItem.setTimestampLastUsed(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastUsed()));
                activationServiceItem.setTimestampLastChange(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastChange()));
                activationServiceItem.setUserId(activation.getUserId());
                activationServiceItem.setApplicationId(activation.getApplication().getId());
                activationServiceItem.setApplicationName(activation.getApplication().getName());
                // Unknown version is converted to 0 in SOAP
                activationServiceItem.setVersion(activation.getVersion() == null ? 0L : activation.getVersion());
                response.getActivations().add(activationServiceItem);
            }
        }

        return response;
    }

    /**
     * Update status for activations.
     * @param activationIds Identifiers of activations to update.
     * @param activationStatus Activation status to use.
     * @return Response with indication whether status update succeeded.
     */
    public UpdateStatusForActivationsResponse updateStatusForActivation(List<String> activationIds, ActivationStatus activationStatus) {
        final UpdateStatusForActivationsResponse response = new UpdateStatusForActivationsResponse();
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();

        activationIds.forEach(activationId -> {
            ActivationRecordEntity activation = activationRepository.findActivationWithLock(activationId);
            if (!activation.getActivationStatus().equals(activationStatus)) {
                // Update activation status, persist change and notify callback listeners
                activation.setActivationStatus(activationStatus);
                activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
                callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
            }
        });

        response.setUpdated(true);

        return response;
    }

    /**
     * Get activation status for given activation ID
     *
     * @param activationId           Activation ID
     * @param challenge              Challenge for activation status blob encryption (since protocol V3.1)
     * @param keyConversionUtilities Key conversion utility class
     * @return Activation status response
     * @throws DatatypeConfigurationException Thrown when calendar conversion fails.
     * @throws GenericServiceException        Thrown when cryptography error occurs.
     */
    public GetActivationStatusResponse getActivationStatus(String activationId, String challenge, CryptoProviderUtil keyConversionUtilities) throws DatatypeConfigurationException, GenericServiceException {
        try {
            // Generate timestamp in advance
            Date timestamp = new Date();

            // Get the repository
            final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
            final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();

            // Prepare key generator
            final KeyGenerator keyGenerator = new KeyGenerator();

            ActivationRecordEntity activation = activationRepository.findActivationWithoutLock(activationId);

            // Check if the activation exists
            if (activation != null) {

                // Deactivate old pending activations first
                deactivatePendingActivation(timestamp, activation, false);

                // Handle CREATED activation
                if (activation.getActivationStatus() == io.getlime.security.powerauth.app.server.database.model.ActivationStatus.CREATED) {

                    // Created activations are not able to transfer valid status blob to the client
                    // since both keys were not exchanged yet and transport cannot be secured.
                    byte[] randomStatusBlob = keyGenerator.generateRandomBytes(32);
                    // Use random nonce in case that challenge was provided.
                    String randomStatusBlobNonce = challenge == null ? null : BaseEncoding.base64().encode(keyGenerator.generateRandomBytes(16));

                    // Activation signature
                    MasterKeyPairEntity masterKeyPairEntity = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(activation.getApplication().getId());
                    if (masterKeyPairEntity == null) {
                        logger.error("Missing key pair for application ID: {}", activation.getApplication().getId());
                        throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
                    }
                    String masterPrivateKeyBase64 = masterKeyPairEntity.getMasterKeyPrivateBase64();
                    byte[] masterPrivateKeyBytes = BaseEncoding.base64().decode(masterPrivateKeyBase64);
                    byte[] activationSignature = powerAuthServerActivation.generateActivationSignature(
                            activation.getActivationCode(),
                            keyConversionUtilities.convertBytesToPrivateKey(masterPrivateKeyBytes)
                    );

                    // return the data
                    GetActivationStatusResponse response = new GetActivationStatusResponse();
                    response.setActivationId(activationId);
                    response.setUserId(activation.getUserId());
                    response.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
                    response.setBlockedReason(activation.getBlockedReason());
                    response.setActivationName(activation.getActivationName());
                    response.setExtras(activation.getExtras());
                    response.setApplicationId(activation.getApplication().getId());
                    response.setTimestampCreated(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampCreated()));
                    response.setTimestampLastUsed(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastUsed()));
                    response.setTimestampLastChange(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastChange()));
                    response.setEncryptedStatusBlob(BaseEncoding.base64().encode(randomStatusBlob));
                    response.setEncryptedStatusBlobNonce(randomStatusBlobNonce);
                    response.setActivationCode(activation.getActivationCode());
                    response.setActivationSignature(BaseEncoding.base64().encode(activationSignature));
                    response.setDevicePublicKeyFingerprint(null);
                    // Unknown version is converted to 0 in SOAP
                    response.setVersion(activation.getVersion() == null ? 0L : activation.getVersion());
                    return response;

                } else {

                    // Get the server private and device public keys to compute the transport key
                    String devicePublicKeyBase64 = activation.getDevicePublicKeyBase64();

                    // Get the server public key for the fingerprint
                    String serverPublicKeyBase64 = activation.getServerPublicKeyBase64();

                    // Decrypt server private key (depending on encryption mode)
                    String serverPrivateKeyFromEntity = activation.getServerPrivateKeyBase64();
                    EncryptionMode serverPrivateKeyEncryptionMode = activation.getServerPrivateKeyEncryption();
                    final ServerPrivateKey serverPrivateKeyEncrypted = new ServerPrivateKey(serverPrivateKeyEncryptionMode, serverPrivateKeyFromEntity);
                    String serverPrivateKeyBase64 = serverPrivateKeyConverter.fromDBValue(serverPrivateKeyEncrypted, activation.getUserId(), activationId);

                    // If an activation was turned to REMOVED directly from CREATED state,
                    // there is not device public key in the database - we need to handle
                    // that case by defaulting the encryptedStatusBlob to random value...
                    byte[] encryptedStatusBlob = keyGenerator.generateRandomBytes(32);
                    String encryptedStatusBlobNonce = null;

                    // Prepare a value for the device public key fingerprint
                    String activationFingerPrint = null;

                    // There is a device public key available, therefore we can compute
                    // the real encryptedStatusBlob value.
                    if (devicePublicKeyBase64 != null) {

                        PrivateKey serverPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(BaseEncoding.base64().decode(serverPrivateKeyBase64));
                        PublicKey devicePublicKey = keyConversionUtilities.convertBytesToPublicKey(BaseEncoding.base64().decode(devicePublicKeyBase64));
                        PublicKey serverPublicKey = keyConversionUtilities.convertBytesToPublicKey(BaseEncoding.base64().decode(serverPublicKeyBase64));

                        SecretKey masterSecretKey = powerAuthServerKeyFactory.generateServerMasterSecretKey(serverPrivateKey, devicePublicKey);
                        SecretKey transportKey = powerAuthServerKeyFactory.generateServerTransportKey(masterSecretKey);

                        String ctrDataBase64 = activation.getCtrDataBase64();
                        byte[] ctrDataHashForStatusBlob;
                        if (ctrDataBase64 != null) {
                            // In crypto v3 counter data is stored with activation. We have to calculate hash from
                            // the counter value, before it's encoded into the status blob. The value might be replaced
                            // in `encryptedStatusBlob()` function that injects random data, depending on the version
                            // of the status blob encryption.
                            final byte[] ctrData = BaseEncoding.base64().decode(ctrDataBase64);
                            ctrDataHashForStatusBlob = powerAuthServerActivation.calculateHashFromHashBasedCounter(ctrData, transportKey);
                        } else {
                            // In crypto v2 counter data is not present, so use an array of zero bytes. This might be
                            // replaced in `encryptedStatusBlob()` function that injects random data automatically,
                            // depending on the version of the status blob encryption.
                            ctrDataHashForStatusBlob = new byte[16];
                        }
                        byte[] statusChallenge;
                        byte[] statusNonce;
                        if (challenge != null) {
                            // If challenge is present, then also generate a new nonce. Protocol V3.1+
                            statusChallenge = BaseEncoding.base64().decode(challenge);
                            statusNonce = keyGenerator.generateRandomBytes(16);
                            encryptedStatusBlobNonce = BaseEncoding.base64().encode(statusNonce);
                        } else {
                            // Older protocol versions, where IV derivation is not available.
                            statusChallenge = null;
                            statusNonce = null;
                        }

                        // Encrypt the status blob
                        ActivationStatusBlobInfo statusBlobInfo = new ActivationStatusBlobInfo();
                        statusBlobInfo.setActivationStatus(activation.getActivationStatus().getByte());
                        statusBlobInfo.setCurrentVersion(activation.getVersion().byteValue());
                        statusBlobInfo.setUpgradeVersion(POWERAUTH_PROTOCOL_VERSION);
                        statusBlobInfo.setFailedAttempts(activation.getFailedAttempts().byteValue());
                        statusBlobInfo.setMaxFailedAttempts(activation.getMaxFailedAttempts().byteValue());
                        statusBlobInfo.setCtrLookAhead((byte)powerAuthServiceConfiguration.getSignatureValidationLookahead());
                        statusBlobInfo.setCtrInfo(activation.getCounter().byteValue());
                        statusBlobInfo.setCtrDataHash(ctrDataHashForStatusBlob);
                        encryptedStatusBlob = powerAuthServerActivation.encryptedStatusBlob(statusBlobInfo, statusChallenge, statusNonce, transportKey);

                        // Assign the activation fingerprint
                        switch (activation.getVersion()) {
                            case 2:
                                activationFingerPrint = powerAuthServerActivation.computeActivationFingerprint(devicePublicKey);
                                break;

                            case 3:
                                activationFingerPrint = powerAuthServerActivation.computeActivationFingerprint(devicePublicKey, serverPublicKey, activation.getActivationId());
                                break;

                            default:
                                logger.error("Unsupported activation version: {}", activation.getVersion());
                                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
                        }

                    }

                    // return the data
                    GetActivationStatusResponse response = new GetActivationStatusResponse();
                    response.setActivationId(activationId);
                    response.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
                    response.setBlockedReason(activation.getBlockedReason());
                    response.setActivationName(activation.getActivationName());
                    response.setUserId(activation.getUserId());
                    response.setExtras(activation.getExtras());
                    response.setApplicationId(activation.getApplication().getId());
                    response.setTimestampCreated(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampCreated()));
                    response.setTimestampLastUsed(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastUsed()));
                    response.setTimestampLastChange(XMLGregorianCalendarConverter.convertFrom(activation.getTimestampLastChange()));
                    response.setEncryptedStatusBlob(BaseEncoding.base64().encode(encryptedStatusBlob));
                    response.setEncryptedStatusBlobNonce(encryptedStatusBlobNonce);
                    response.setActivationCode(null);
                    response.setActivationSignature(null);
                    response.setDevicePublicKeyFingerprint(activationFingerPrint);
                    // Unknown version is converted to 0 in SOAP
                    response.setVersion(activation.getVersion() == null ? 0L : activation.getVersion());
                    return response;

                }
            } else {

                // Activations that do not exist should return REMOVED state and
                // a random status blob
                byte[] randomStatusBlob = keyGenerator.generateRandomBytes(32);
                // Use random nonce in case that challenge was provided.
                String randomStatusBlobNonce = challenge == null ? null : BaseEncoding.base64().encode(keyGenerator.generateRandomBytes(16));

                // Generate date
                XMLGregorianCalendar zeroDate = XMLGregorianCalendarConverter.convertFrom(new Date(0));

                // return the data
                GetActivationStatusResponse response = new GetActivationStatusResponse();
                response.setActivationId(activationId);
                response.setActivationStatus(activationStatusConverter.convert(ActivationStatus.REMOVED));
                response.setBlockedReason(null);
                response.setActivationName("unknown");
                response.setUserId("unknown");
                response.setApplicationId(0L);
                response.setExtras(null);
                response.setTimestampCreated(zeroDate);
                response.setTimestampLastUsed(zeroDate);
                response.setTimestampLastChange(null);
                response.setEncryptedStatusBlob(BaseEncoding.base64().encode(randomStatusBlob));
                response.setEncryptedStatusBlobNonce(randomStatusBlobNonce);
                response.setActivationCode(null);
                response.setActivationSignature(null);
                response.setDevicePublicKeyFingerprint(null);
                // Use 0 as version when version is undefined
                response.setVersion(0L);
                return response;
            }
        } catch (InvalidKeySpecException | InvalidKeyException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (GenericCryptoException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
        } catch (CryptoProviderException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_CRYPTO_PROVIDER);
        }
    }

    /**
     * Init activation with given parameters
     *
     * @param applicationId             Application ID
     * @param userId                    User ID
     * @param maxFailureCount            Maximum failed attempt count (5)
     * @param activationExpireTimestamp Timestamp after which activation can no longer be completed
     * @param keyConversionUtilities    Utility class for key conversion
     * @return Response with activation initialization data
     * @throws GenericServiceException If invalid values are provided.
     */
    public InitActivationResponse initActivation(Long applicationId, String userId, Long maxFailureCount, Date activationExpireTimestamp, CryptoProviderUtil keyConversionUtilities) throws GenericServiceException {
        try {
            // Generate timestamp in advance
            Date timestamp = new Date();

            if (userId == null || userId.isEmpty() || userId.length() > 255) {
                logger.warn("User ID not specified or invalid");
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_USER_ID);
            }

            if (applicationId == 0L) {
                logger.warn("Application ID not specified");
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_APPLICATION_ID);
            }

            // Application version is not being checked in initActivation, it is checked later in prepareActivation or createActivation.

            // Get the repository
            final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
            final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();

            // Get number of max attempts from request or from constants, if not provided
            Long maxAttempt = maxFailureCount;
            if (maxAttempt == null) {
                maxAttempt = powerAuthServiceConfiguration.getSignatureMaxFailedAttempts();
            }

            // Get activation expiration date from request or from constants, if not provided
            Date timestampExpiration = activationExpireTimestamp;
            if (timestampExpiration == null) {
                timestampExpiration = new Date(timestamp.getTime() + powerAuthServiceConfiguration.getActivationValidityBeforeActive());
            }

            // Fetch the latest master private key
            MasterKeyPairEntity masterKeyPair = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(applicationId);
            if (masterKeyPair == null) {
                GenericServiceException ex = localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
                logger.error("No master key pair found for application ID: {}", applicationId);
                throw ex;
            }
            byte[] masterPrivateKeyBytes = BaseEncoding.base64().decode(masterKeyPair.getMasterKeyPrivateBase64());
            PrivateKey masterPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(masterPrivateKeyBytes);

            // Generate new activation data, generate a unique activation ID
            String activationId = null;
            for (int i = 0; i < powerAuthServiceConfiguration.getActivationGenerateActivationIdIterations(); i++) {
                String tmpActivationId = powerAuthServerActivation.generateActivationId();
                Long activationCount = activationRepository.getActivationCount(tmpActivationId);
                if (activationCount == 0) {
                    activationId = tmpActivationId;
                    break;
                } // ... else this activation ID has a collision, reset it and try to find another one
            }
            if (activationId == null) {
                logger.error("Unable to generate activation ID");
                throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_ACTIVATION_ID);
            }

            // Generate a unique activation code
            String activationCode = null;
            for (int i = 0; i < powerAuthServiceConfiguration.getActivationGenerateActivationCodeIterations(); i++) {
                String tmpActivationCode = powerAuthServerActivation.generateActivationCode();
                Long activationCount = activationRepository.getActivationCountByActivationCode(applicationId, tmpActivationCode);
                // Check that the temporary short activation ID is unique, otherwise generate a different activation code
                if (activationCount == 0) {
                    activationCode = tmpActivationCode;
                    break;
                }
            }
            if (activationCode == null) {
                logger.error("Unable to generate activation code");
                throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_ACTIVATION_CODE);
            }


            // Compute activation signature
            byte[] activationSignature = powerAuthServerActivation.generateActivationSignature(activationCode, masterPrivateKey);

            // Encode the signature
            String activationSignatureBase64 = BaseEncoding.base64().encode(activationSignature);

            // Generate server key pair
            KeyPair serverKeyPair = powerAuthServerActivation.generateServerKeyPair();
            byte[] serverKeyPrivateBytes = keyConversionUtilities.convertPrivateKeyToBytes(serverKeyPair.getPrivate());
            byte[] serverKeyPublicBytes = keyConversionUtilities.convertPublicKeyToBytes(serverKeyPair.getPublic());

            // Store the new activation
            ActivationRecordEntity activation = new ActivationRecordEntity();
            activation.setActivationId(activationId);
            activation.setActivationCode(activationCode);
            activation.setActivationName(null);
            activation.setActivationStatus(ActivationStatus.CREATED);
            activation.setCounter(0L);
            activation.setCtrDataBase64(null);
            activation.setDevicePublicKeyBase64(null);
            activation.setExtras(null);
            activation.setFailedAttempts(0L);
            activation.setApplication(masterKeyPair.getApplication());
            activation.setMasterKeyPair(masterKeyPair);
            activation.setMaxFailedAttempts(maxAttempt);
            activation.setServerPublicKeyBase64(BaseEncoding.base64().encode(serverKeyPublicBytes));
            activation.setTimestampActivationExpire(timestampExpiration);
            activation.setTimestampCreated(timestamp);
            activation.setTimestampLastUsed(timestamp);
            activation.setTimestampLastChange(null);
            // Activation version is not known yet
            activation.setVersion(null);
            activation.setUserId(userId);

            // Convert server private key to DB columns serverPrivateKeyEncryption specifying encryption mode and serverPrivateKey with base64-encoded key.
            ServerPrivateKey serverPrivateKey = serverPrivateKeyConverter.toDBValue(serverKeyPrivateBytes, userId, activationId);
            activation.setServerPrivateKeyEncryption(serverPrivateKey.getEncryptionMode());
            activation.setServerPrivateKeyBase64(serverPrivateKey.getServerPrivateKeyBase64());

            activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

            // Return the server response
            InitActivationResponse response = new InitActivationResponse();
            response.setActivationId(activationId);
            response.setActivationCode(activationCode);
            response.setUserId(userId);
            response.setActivationSignature(activationSignatureBase64);
            response.setApplicationId(activation.getApplication().getId());

            return response;
        } catch (InvalidKeySpecException | InvalidKeyException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INCORRECT_MASTER_SERVER_KEYPAIR_PRIVATE);
        } catch (GenericCryptoException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
        } catch (CryptoProviderException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_CRYPTO_PROVIDER);
        }
    }

    /**
     * Prepare activation with given parameters.
     *
     * <p><b>PowerAuth protocol versions:</b>
     * <ul>
     *     <li>3.0</li>
     * </ul>
     *
     * @param activationCode Activation code.
     * @param applicationKey Application key.
     * @param eciesCryptogram Ecies cryptogram.
     * @return ECIES encrypted activation information.
     * @throws GenericServiceException If invalid values are provided.
     */
    public PrepareActivationResponse prepareActivation(String activationCode, String applicationKey, EciesCryptogram eciesCryptogram, CryptoProviderUtil keyConversion) throws GenericServiceException {
        try {
            // Get current timestamp
            Date timestamp = new Date();

            // Get required repositories
            final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();
            final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();
            final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
            final RecoveryConfigRepository recoveryConfigRepository = repositoryCatalogue.getRecoveryConfigRepository();

            // Find application by application key
            ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);
            if (applicationVersion == null || !applicationVersion.getSupported()) {
                logger.warn("Application version is incorrect, activation code: {}", activationCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
            }
            ApplicationEntity application = applicationVersion.getApplication();
            if (application == null) {
                logger.warn("Application does not exist, activation code: {}", activationCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
            }

            // Get master server private key
            MasterKeyPairEntity masterKeyPairEntity = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(application.getId());
            if (masterKeyPairEntity == null) {
                logger.error("Missing key pair for application ID: {}", application.getId());
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
            }

            String masterPrivateKeyBase64 = masterKeyPairEntity.getMasterKeyPrivateBase64();
            PrivateKey privateKey = keyConversion.convertBytesToPrivateKey(BaseEncoding.base64().decode(masterPrivateKeyBase64));

            // Get application secret
            byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);

            // Get ecies decryptor
            EciesDecryptor eciesDecryptor = eciesFactory.getEciesDecryptorForApplication((ECPrivateKey) privateKey, applicationSecret, EciesSharedInfo1.ACTIVATION_LAYER_2);

            // Decrypt activation data
            byte[] activationData = eciesDecryptor.decryptRequest(eciesCryptogram);

            // Convert JSON data to activation layer 2 request object
            ActivationLayer2Request request;
            try {
                request = objectMapper.readValue(activationData, ActivationLayer2Request.class);
            } catch (IOException ex) {
                logger.warn("Invalid activation request, activation code: {}", activationCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_INPUT_FORMAT);
            }

            // Fetch the current activation by activation code
            Set<ActivationStatus> states = ImmutableSet.of(ActivationStatus.CREATED);
            // Search for activation without lock to avoid potential deadlocks
            ActivationRecordEntity activation = activationRepository.findCreatedActivationWithoutLock(application.getId(), activationCode, states, timestamp);

            // Make sure to deactivate the activation if it is expired
            if (activation == null) {
                logger.warn("Activation not found with activation ID: {}", activationCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
            }

            // Search for activation again to acquire PESSIMISTIC_WRITE lock for activation row
            activation = activationRepository.findActivationWithLock(activation.getActivationId());
            deactivatePendingActivation(timestamp, activation, true);

            // Validate that the activation is in correct state for the prepare step
            validateCreatedActivation(activation, application);

            // Extract the device public key from request
            byte[] devicePublicKeyBytes = BaseEncoding.base64().decode(request.getDevicePublicKey());
            PublicKey devicePublicKey = null;

            try {
                devicePublicKey = keyConversion.convertBytesToPublicKey(devicePublicKeyBytes);
            } catch (InvalidKeySpecException ex) {
                handleInvalidPublicKey(activation);
            }

            // Initialize hash based counter
            HashBasedCounter counter = new HashBasedCounter();
            byte[] ctrData = counter.init();
            String ctrDataBase64 = BaseEncoding.base64().encode(ctrData);

            // Update and persist the activation record
            activation.setActivationStatus(ActivationStatus.OTP_USED);
            // The device public key is converted back to bytes and base64 encoded so that the key is saved in normalized form
            activation.setDevicePublicKeyBase64(BaseEncoding.base64().encode(keyConversion.convertPublicKeyToBytes(devicePublicKey)));
            activation.setActivationName(request.getActivationName());
            activation.setExtras(request.getExtras());
            // PowerAuth protocol version 3.0 uses 0x3 as version in activation status
            activation.setVersion(3);
            // Set initial counter data
            activation.setCtrDataBase64(ctrDataBase64);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

            // Create a new recovery code and PUK for new activation if activation recovery is enabled
            ActivationRecovery activationRecovery = null;
            final RecoveryConfigEntity recoveryConfigEntity = recoveryConfigRepository.findByApplicationId(application.getId());
            if (recoveryConfigEntity != null && recoveryConfigEntity.getActivationRecoveryEnabled()) {
                activationRecovery = createRecoveryCodeForActivation(activation);
            }

            // Generate activation layer 2 response
            ActivationLayer2Response layer2Response = new ActivationLayer2Response();
            layer2Response.setActivationId(activation.getActivationId());
            layer2Response.setCtrData(ctrDataBase64);
            layer2Response.setServerPublicKey(activation.getServerPublicKeyBase64());
            if (activationRecovery != null) {
                layer2Response.setActivationRecovery(activationRecovery);
            }
            byte[] responseData = objectMapper.writeValueAsBytes(layer2Response);

            // Encrypt response data
            EciesCryptogram responseCryptogram = eciesDecryptor.encryptResponse(responseData);
            String encryptedData = BaseEncoding.base64().encode(responseCryptogram.getEncryptedData());
            String mac = BaseEncoding.base64().encode(responseCryptogram.getMac());

            // Generate encrypted response
            PrepareActivationResponse encryptedResponse = new PrepareActivationResponse();
            encryptedResponse.setActivationId(activation.getActivationId());
            encryptedResponse.setUserId(activation.getUserId());
            encryptedResponse.setEncryptedData(encryptedData);
            encryptedResponse.setMac(mac);
            return encryptedResponse;
        } catch (InvalidKeySpecException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (EciesException | JsonProcessingException ex) {
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
     * Create activation with given parameters.
     *
     * <p><b>PowerAuth protocol versions:</b>
     * <ul>
     *     <li>3.0</li>
     * </ul>
     *
     * @param userId                         User ID
     * @param activationExpireTimestamp      Timestamp after which activation can no longer be completed
     * @param maxFailureCount                Maximum failed attempt count (default = 5)
     * @param applicationKey                 Application key
     * @param eciesCryptogram                ECIES cryptogram
     * @param keyConversion                  Utility class for key conversion
     * @return ECIES encrypted activation information
     * @throws GenericServiceException       In case create activation fails
     */
    public CreateActivationResponse createActivation(
            String userId,
            Date activationExpireTimestamp,
            Long maxFailureCount,
            String applicationKey,
            EciesCryptogram eciesCryptogram,
            CryptoProviderUtil keyConversion) throws GenericServiceException {
        try {
            // Get current timestamp
            Date timestamp = new Date();

            // Get required repositories
            final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
            final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();
            final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();
            final RecoveryConfigRepository recoveryConfigRepository = repositoryCatalogue.getRecoveryConfigRepository();

            ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);
            // If there is no such activation version or activation version is unsupported, exit
            if (applicationVersion == null || !applicationVersion.getSupported()) {
                logger.warn("Application version is incorrect, application key: {}", applicationKey);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_APPLICATION);
            }

            ApplicationEntity application = applicationVersion.getApplication();
            // If there is no such application, exit
            if (application == null) {
                logger.warn("Application is incorrect, application key: {}", applicationKey);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
            }

            // Create an activation record and obtain the activation database record
            InitActivationResponse initResponse = this.initActivation(application.getId(), userId, maxFailureCount, activationExpireTimestamp, keyConversion);
            String activationId = initResponse.getActivationId();
            ActivationRecordEntity activation = activationRepository.findActivationWithLock(activationId);

            if (activation == null) { // should not happen, activation was just created above via "init" call
                logger.warn("Activation not found for activation ID: {}", activationId);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
            }

            // Make sure to deactivate the activation if it is expired
            deactivatePendingActivation(timestamp, activation, true);

            validateCreatedActivation(activation, application);

            // Get master server private key
            MasterKeyPairEntity masterKeyPairEntity = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(application.getId());
            if (masterKeyPairEntity == null) {
                logger.error("Missing key pair for application ID: {}", application.getId());
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
            }

            String masterPrivateKeyBase64 = masterKeyPairEntity.getMasterKeyPrivateBase64();
            PrivateKey privateKey = keyConversion.convertBytesToPrivateKey(BaseEncoding.base64().decode(masterPrivateKeyBase64));

            // Get application secret
            byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);

            // Get ecies decryptor
            EciesDecryptor eciesDecryptor = eciesFactory.getEciesDecryptorForApplication((ECPrivateKey) privateKey, applicationSecret, EciesSharedInfo1.ACTIVATION_LAYER_2);

            // Decrypt activation data
            byte[] activationData = eciesDecryptor.decryptRequest(eciesCryptogram);

            // Convert JSON data to activation layer 2 request object
            ActivationLayer2Request request;
            try {
                request = objectMapper.readValue(activationData, ActivationLayer2Request.class);
            } catch (IOException ex) {
                logger.warn("Invalid activation request, activation ID: {}", activationId);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_INPUT_FORMAT);
            }

            // Extract the device public key from request
            byte[] devicePublicKeyBytes = BaseEncoding.base64().decode(request.getDevicePublicKey());
            PublicKey devicePublicKey = null;

            try {
                devicePublicKey = keyConversion.convertBytesToPublicKey(devicePublicKeyBytes);
            } catch (InvalidKeySpecException ex) {
                handleInvalidPublicKey(activation);
            }

            // Initialize hash based counter
            HashBasedCounter counter = new HashBasedCounter();
            byte[] ctrData = counter.init();
            String ctrDataBase64 = BaseEncoding.base64().encode(ctrData);

            // Update and persist the activation record
            activation.setActivationStatus(ActivationStatus.OTP_USED);
            // The device public key is converted back to bytes and base64 encoded so that the key is saved in normalized form
            activation.setDevicePublicKeyBase64(BaseEncoding.base64().encode(keyConversion.convertPublicKeyToBytes(devicePublicKey)));
            activation.setActivationName(request.getActivationName());
            activation.setExtras(request.getExtras());
            // PowerAuth protocol version 3.0 uses 0x3 as version in activation status
            activation.setVersion(3);
            // Set initial counter data
            activation.setCtrDataBase64(ctrDataBase64);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

            // Create a new recovery code and PUK for new activation if activation recovery is enabled
            ActivationRecovery activationRecovery = null;
            final RecoveryConfigEntity recoveryConfigEntity = recoveryConfigRepository.findByApplicationId(application.getId());
            if (recoveryConfigEntity != null && recoveryConfigEntity.getActivationRecoveryEnabled()) {
                activationRecovery = createRecoveryCodeForActivation(activation);
            }

            // Generate activation layer 2 response
            ActivationLayer2Response layer2Response = new ActivationLayer2Response();
            layer2Response.setActivationId(activation.getActivationId());
            layer2Response.setCtrData(ctrDataBase64);
            layer2Response.setServerPublicKey(activation.getServerPublicKeyBase64());
            if (activationRecovery != null) {
                layer2Response.setActivationRecovery(activationRecovery);
            }
            byte[] responseData = objectMapper.writeValueAsBytes(layer2Response);

            // Encrypt response data
            EciesCryptogram responseCryptogram = eciesDecryptor.encryptResponse(responseData);
            String encryptedData = BaseEncoding.base64().encode(responseCryptogram.getEncryptedData());
            String mac = BaseEncoding.base64().encode(responseCryptogram.getMac());

            // Generate encrypted response
            CreateActivationResponse encryptedResponse = new CreateActivationResponse();
            encryptedResponse.setActivationId(activation.getActivationId());
            encryptedResponse.setEncryptedData(encryptedData);
            encryptedResponse.setMac(mac);
            return encryptedResponse;
        } catch (InvalidKeySpecException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (EciesException | JsonProcessingException ex) {
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
     * Commit activation with given ID.
     *
     * @param activationId Activation ID.
     * @param externalUserId User ID of user who committed the activation. Use null value if activation owner caused the change.
     * @return Response with activation commit confirmation.
     * @throws GenericServiceException In case invalid data is provided or activation is not found, in invalid state or already expired.
     */
    public CommitActivationResponse commitActivation(String activationId, String externalUserId) throws GenericServiceException {

        // Get the repository
        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();

        ActivationRecordEntity activation = activationRepository.findActivationWithLock(activationId);

        // Get current timestamp
        Date timestamp = new Date();

        // Does the activation exist?
        if (activation != null) {

            // Check already deactivated activation
            deactivatePendingActivation(timestamp, activation, true);
            if (activation.getActivationStatus().equals(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.REMOVED)) {
                logger.info("Activation is already REMOVED, activation ID: {}", activationId);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_EXPIRED);
            }

            // Activation is in correct state
            if (activation.getActivationStatus().equals(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.OTP_USED)) {
                activation.setActivationStatus(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.ACTIVE);
                activationHistoryServiceBehavior.saveActivationAndLogChange(activation, externalUserId);
                callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

                // Update recovery code status in case a related recovery code exists in CREATED state
                RecoveryCodeRepository recoveryCodeRepository = repositoryCatalogue.getRecoveryCodeRepository();
                List<RecoveryCodeEntity> recoveryCodeEntities = recoveryCodeRepository.findAllByApplicationIdAndActivationId(activation.getApplication().getId(), activation.getActivationId());
                for (RecoveryCodeEntity recoveryCodeEntity : recoveryCodeEntities) {
                    if (RecoveryCodeStatus.CREATED.equals(recoveryCodeEntity.getStatus())) {
                        recoveryCodeEntity.setStatus(RecoveryCodeStatus.ACTIVE);
                        recoveryCodeEntity.setTimestampLastChange(new Date());
                        recoveryCodeRepository.save(recoveryCodeEntity);
                    }
                }

                CommitActivationResponse response = new CommitActivationResponse();
                response.setActivationId(activationId);
                response.setActivated(true);
                return response;
            } else {
                logger.info("Activation is not in OTP_USED state during commit, activation ID: {}", activationId);
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
            }

        } else {
            // Activation does not exist
            logger.info("Activation does not exist, activation ID: {}", activationId);
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }
    }

    /**
     * Remove activation with given ID.
     *
     * @param activationId Activation ID.
     * @param externalUserId User ID of user who removed the activation. Use null value if activation owner caused the change.
     * @return Response with confirmation of removal.
     * @throws GenericServiceException In case activation does not exist.
     */
    public RemoveActivationResponse removeActivation(String activationId, String externalUserId) throws GenericServiceException {
        ActivationRecordEntity activation = repositoryCatalogue.getActivationRepository().findActivationWithLock(activationId);
        if (activation != null) { // does the record even exist?
            activation.setActivationStatus(io.getlime.security.powerauth.app.server.database.model.ActivationStatus.REMOVED);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation, externalUserId);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
            RemoveActivationResponse response = new RemoveActivationResponse();
            response.setActivationId(activationId);
            response.setRemoved(true);
            return response;
        } else {
            logger.info("Activation does not exist, activation ID: {}", activationId);
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }
    }

    /**
     * Block activation with given ID
     *
     * @param activationId Activation ID
     * @param reason Reason why activation is being blocked.
     * @param externalUserId User ID of user who blocked the activation. Use null value if activation owner caused the change.
     * @return Response confirming that activation was blocked
     * @throws GenericServiceException In case activation does not exist.
     */
    public BlockActivationResponse blockActivation(String activationId, String reason, String externalUserId) throws GenericServiceException {
        ActivationRecordEntity activation = repositoryCatalogue.getActivationRepository().findActivationWithLock(activationId);
        if (activation == null) {
            logger.info("Activation does not exist, activation ID: {}", activationId);
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }

        // does the record even exist, is it in correct state?
        // early null check done above, no null check needed here
        if (activation.getActivationStatus().equals(ActivationStatus.ACTIVE)) {
            activation.setActivationStatus(ActivationStatus.BLOCKED);
            if (reason == null) {
                activation.setBlockedReason(AdditionalInformation.BLOCKED_REASON_NOT_SPECIFIED);
            } else {
                activation.setBlockedReason(reason);
            }
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation, externalUserId);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
        } else if (!activation.getActivationStatus().equals(ActivationStatus.BLOCKED)) {
            // In case activation status is not ACTIVE or BLOCKED, throw an exception
            logger.info("Activation cannot be blocked due to invalid status, activation ID: {}, status: {}", activationId, activation.getActivationStatus());
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
        }
        BlockActivationResponse response = new BlockActivationResponse();
        response.setActivationId(activationId);
        response.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
        response.setBlockedReason(activation.getBlockedReason());
        return response;
    }

    /**
     * Unblock activation with given ID
     *
     * @param activationId Activation ID
     * @param externalUserId User ID of user who unblocked the activation. Use null value if activation owner caused the change.
     * @return Response confirming that activation was unblocked
     * @throws GenericServiceException In case activation does not exist.
     */
    public UnblockActivationResponse unblockActivation(String activationId, String externalUserId) throws GenericServiceException {
        ActivationRecordEntity activation = repositoryCatalogue.getActivationRepository().findActivationWithLock(activationId);
        if (activation == null) {
            logger.info("Activation does not exist, activation ID: {}", activationId);
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }

        // does the record even exist, is it in correct state?
        // early null check done above, no null check needed here
        if (activation.getActivationStatus().equals(ActivationStatus.BLOCKED)) {
            // Update and store new activation
            activation.setActivationStatus(ActivationStatus.ACTIVE);
            activation.setBlockedReason(null);
            activation.setFailedAttempts(0L);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation, externalUserId);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());
        } else if (!activation.getActivationStatus().equals(ActivationStatus.ACTIVE)) {
            // In case activation status is not BLOCKED or ACTIVE, throw an exception
            logger.info("Activation cannot be unblocked due to invalid status, activation ID: {}, status: {}", activationId, activation.getActivationStatus());
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
        }
        UnblockActivationResponse response = new UnblockActivationResponse();
        response.setActivationId(activationId);
        response.setActivationStatus(activationStatusConverter.convert(activation.getActivationStatus()));
        return response;
    }


    /**
     * Create activation using recovery code.
     * @param request Create activation using recovery code request.
     * @return Create activation using recovery code response.
     * @throws GenericServiceException In case of any error.
     */
    public RecoveryCodeActivationResponse createActivationUsingRecoveryCode(RecoveryCodeActivationRequest request, CryptoProviderUtil keyConversion) throws GenericServiceException {
        try {
            // Extract request data
            final String recoveryCode = request.getRecoveryCode();
            final String puk = request.getPuk();
            final String applicationKey = request.getApplicationKey();
            final Long maxFailureCount = request.getMaxFailureCount();
            final String ephemeralPublicKey = request.getEphemeralPublicKey();
            final String encryptedData = request.getEncryptedData();
            final String mac = request.getMac();

            // Prepare ECIES request cryptogram
            byte[] ephemeralPublicKeyBytes = BaseEncoding.base64().decode(ephemeralPublicKey);
            byte[] encryptedDataBytes = BaseEncoding.base64().decode(encryptedData);
            byte[] macBytes = BaseEncoding.base64().decode(mac);
            byte[] nonceBytes = request.getNonce() != null ? BaseEncoding.base64().decode(request.getNonce()) : null;
            final EciesCryptogram eciesCryptogram = new EciesCryptogram(ephemeralPublicKeyBytes, macBytes, encryptedDataBytes, nonceBytes);

            // Prepare repositories
            final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
            final RecoveryCodeRepository recoveryCodeRepository = repositoryCatalogue.getRecoveryCodeRepository();
            final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();
            final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();
            final RecoveryConfigRepository recoveryConfigRepository = repositoryCatalogue.getRecoveryConfigRepository();

            // Find application by application key
            final ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);
            if (applicationVersion == null || !applicationVersion.getSupported()) {
                logger.warn("Application version is incorrect, application key: {}", applicationKey);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }
            final ApplicationEntity application = applicationVersion.getApplication();
            if (application == null) {
                logger.warn("Application does not exist, application key: {}", applicationKey);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }

            // Check whether activation recovery is enabled
            final RecoveryConfigEntity recoveryConfigEntity = recoveryConfigRepository.findByApplicationId(application.getId());
            if (recoveryConfigEntity == null || !recoveryConfigEntity.getActivationRecoveryEnabled()) {
                logger.warn("Activation recovery is disabled");
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }

            // Get master server private key
            MasterKeyPairEntity masterKeyPairEntity = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(application.getId());
            if (masterKeyPairEntity == null) {
                logger.error("Missing key pair for application ID: {}", application.getId());
                throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
            }

            String masterPrivateKeyBase64 = masterKeyPairEntity.getMasterKeyPrivateBase64();
            PrivateKey privateKey = keyConversion.convertBytesToPrivateKey(BaseEncoding.base64().decode(masterPrivateKeyBase64));

            // Get application secret
            byte[] applicationSecret = applicationVersion.getApplicationSecret().getBytes(StandardCharsets.UTF_8);

            // Get ecies decryptor
            EciesDecryptor eciesDecryptor = eciesFactory.getEciesDecryptorForApplication((ECPrivateKey) privateKey, applicationSecret, EciesSharedInfo1.ACTIVATION_LAYER_2);

            // Decrypt activation data
            byte[] activationData = eciesDecryptor.decryptRequest(eciesCryptogram);

            // Convert JSON data to activation layer 2 request object
            ActivationLayer2Request layer2Request;
            try {
                layer2Request = objectMapper.readValue(activationData, ActivationLayer2Request.class);
            } catch (IOException ex) {
                logger.warn("Invalid activation request, recovery code: {}", recoveryCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_INPUT_FORMAT);
            }

            // Get recovery code entity
            RecoveryCodeEntity recoveryCodeEntity = recoveryCodeRepository.findByApplicationIdAndRecoveryCode(application.getId(), recoveryCode);
            if (recoveryCodeEntity == null) {
                logger.warn("Recovery code does not exist: {}", recoveryCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }
            if (!io.getlime.security.powerauth.app.server.database.model.RecoveryCodeStatus.ACTIVE.equals(recoveryCodeEntity.getStatus())) {
                logger.warn("Recovery code is not in ACTIVE state: {}", recoveryCode);
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }

            // Verify recovery PUK
            boolean pukValid = false;
            RecoveryPukEntity pukUsedDuringActivation = null;
            RecoveryPukEntity firstValidPuk = null;
            List<RecoveryPukEntity> recoveryPukEntities = recoveryCodeEntity.getRecoveryPuks();
            for (RecoveryPukEntity recoveryPukEntity: recoveryPukEntities) {
                if (RecoveryPukStatus.VALID.equals(recoveryPukEntity.getStatus())) {
                    if (firstValidPuk == null) {
                        firstValidPuk = recoveryPukEntity;
                        // First valid PUK found, verify PUK hash
                        byte[] pukBytes = puk.getBytes(StandardCharsets.UTF_8);
                        String pukValueFromDB = recoveryPukEntity.getPuk();
                        EncryptionMode encryptionMode = recoveryPukEntity.getPukEncryption();
                        RecoveryPuk recoveryPuk = new RecoveryPuk(encryptionMode, pukValueFromDB);
                        String pukHash = recoveryPukConverter.fromDBValue(recoveryPuk, application.getId(), recoveryCodeEntity.getUserId(), recoveryCode, recoveryPukEntity.getPukIndex());
                        try {
                            if (PasswordHash.verify(pukBytes, pukHash)) {
                                pukValid = true;
                                pukUsedDuringActivation = recoveryPukEntity;
                                break;
                            }
                        } catch (IOException ex) {
                            logger.warn("Invalid PUK hash for recovery code: {}", recoveryCode);
                            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
                        }
                    }
                }
            }
            if (!pukValid) {
                // Log invalid PUK on info level, this may be a common user error
                logger.info("Received invalid recovery PUK for recovery code: {}", recoveryCodeEntity.getRecoveryCodeMasked());
                // Increment failed count
                recoveryCodeEntity.setFailedAttempts(recoveryCodeEntity.getFailedAttempts() + 1);
                recoveryCodeEntity.setTimestampLastChange(new Date());
                if (recoveryCodeEntity.getFailedAttempts() >= recoveryCodeEntity.getMaxFailedAttempts()) {
                    if (firstValidPuk != null) {
                        // In case max failed count is reached and valid PUK exists, block the recovery code and invalidate the PUK
                        recoveryCodeEntity.setStatus(RecoveryCodeStatus.BLOCKED);
                        recoveryCodeEntity.setTimestampLastChange(new Date());
                        firstValidPuk.setStatus(RecoveryPukStatus.INVALID);
                        firstValidPuk.setTimestampLastChange(new Date());
                    }
                }
                recoveryCodeRepository.save(recoveryCodeEntity);
                if (firstValidPuk != null && !RecoveryPukStatus.INVALID.equals(firstValidPuk.getStatus())) {
                    // Provide current recovery PUK index in error response in case PUK in VALID state exists
                    throw localizationProvider.buildActivationRecoveryExceptionForCode(ServiceError.INVALID_RECOVERY_CODE, firstValidPuk.getPukIndex().intValue());
                } else {
                    throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_RECOVERY_CODE);
                }
            }

            // Reset failed count, PUK was valid
            recoveryCodeEntity.setFailedAttempts(0L);

            // Change status of PUK which was used for recovery to USED
            pukUsedDuringActivation.setStatus(RecoveryPukStatus.USED);
            pukUsedDuringActivation.setTimestampLastChange(new Date());

            // If recovery code is bound to an existing activation, remove this activation
            if (recoveryCodeEntity.getActivationId() != null) {
                removeActivation(recoveryCodeEntity.getActivationId(), null);
                // If there are no PUKs in VALID state left, set the recovery code status to REVOKED
                boolean validPukExists = false;
                for (RecoveryPukEntity recoveryPukEntity : recoveryCodeEntity.getRecoveryPuks()) {
                    if (RecoveryPukStatus.VALID.equals(recoveryPukEntity.getStatus())) {
                        validPukExists = true;
                        break;
                    }
                }
                if (!validPukExists) {
                    recoveryCodeEntity.setStatus(RecoveryCodeStatus.REVOKED);
                    recoveryCodeEntity.setTimestampLastChange(new Date());
                }
            }

            // Persist recovery code changes
            recoveryCodeRepository.save(recoveryCodeEntity);

            // Initialize version 3 activation entity.
            // Parameter maxFailureCount can be customized, activationExpireTime is null because activation is committed immediately.
            InitActivationResponse initResponse = initActivation(application.getId(), recoveryCodeEntity.getUserId(), maxFailureCount, null, keyConversion);
            String activationId = initResponse.getActivationId();
            ActivationRecordEntity activation = activationRepository.findActivationWithLock(activationId);

            // Validate created activation
            validateCreatedActivation(activation, application);

            // Extract the device public key from request
            byte[] devicePublicKeyBytes = BaseEncoding.base64().decode(layer2Request.getDevicePublicKey());
            PublicKey devicePublicKey = null;

            try {
                devicePublicKey = keyConversion.convertBytesToPublicKey(devicePublicKeyBytes);
            } catch (InvalidKeySpecException ex) {
                handleInvalidPublicKey(activation);
            }

            // Initialize hash based counter
            HashBasedCounter counter = new HashBasedCounter();
            byte[] ctrData = counter.init();
            String ctrDataBase64 = BaseEncoding.base64().encode(ctrData);

            // Update and persist the activation record, activation is automatically committed in the next step
            activation.setActivationStatus(ActivationStatus.OTP_USED);
            // The device public key is converted back to bytes and base64 encoded so that the key is saved in normalized form
            activation.setDevicePublicKeyBase64(BaseEncoding.base64().encode(keyConversion.convertPublicKeyToBytes(devicePublicKey)));
            activation.setActivationName(layer2Request.getActivationName());
            activation.setExtras(layer2Request.getExtras());
            // PowerAuth protocol version 3.0 uses 0x3 as version in activation status
            activation.setVersion(3);
            // Set initial counter data
            activation.setCtrDataBase64(ctrDataBase64);
            activationHistoryServiceBehavior.saveActivationAndLogChange(activation);
            callbackUrlBehavior.notifyCallbackListeners(activation.getApplication().getId(), activation.getActivationId());

            // Activation has been successfully committed, set PUK state to USED and persist the change
            pukUsedDuringActivation.setStatus(RecoveryPukStatus.USED);
            pukUsedDuringActivation.setTimestampLastChange(new Date());
            recoveryCodeRepository.save(recoveryCodeEntity);

            // Create a new recovery code and PUK for new activation
            ActivationRecovery activationRecovery = createRecoveryCodeForActivation(activation);

            // Generate activation layer 2 response
            ActivationLayer2Response layer2Response = new ActivationLayer2Response();
            layer2Response.setActivationId(activation.getActivationId());
            layer2Response.setCtrData(ctrDataBase64);
            layer2Response.setServerPublicKey(activation.getServerPublicKeyBase64());
            layer2Response.setActivationRecovery(activationRecovery);
            byte[] responseData = objectMapper.writeValueAsBytes(layer2Response);

            // Encrypt response data
            final EciesCryptogram responseCryptogram = eciesDecryptor.encryptResponse(responseData);
            final String encryptedDataResponse = BaseEncoding.base64().encode(responseCryptogram.getEncryptedData());
            final String macResponse = BaseEncoding.base64().encode(responseCryptogram.getMac());

            final RecoveryCodeActivationResponse encryptedResponse = new RecoveryCodeActivationResponse();
            encryptedResponse.setActivationId(activation.getActivationId());
            encryptedResponse.setUserId(activation.getUserId());
            encryptedResponse.setEncryptedData(encryptedDataResponse);
            encryptedResponse.setMac(macResponse);
            return encryptedResponse;
        } catch (InvalidKeySpecException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (EciesException | JsonProcessingException ex) {
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
     * Create recovery code for given activation and set its status to ACTIVE.
     * @param activationEntity Activation entity.
     * @return Activation recovery code and PUK.
     * @throws GenericServiceException In case of any error.
     */
    private ActivationRecovery createRecoveryCodeForActivation(ActivationRecordEntity activationEntity) throws GenericServiceException {
        final RecoveryConfigRepository recoveryConfigRepository = repositoryCatalogue.getRecoveryConfigRepository();

        try {
            // Check whether activation recovery is enabled
            final RecoveryConfigEntity recoveryConfigEntity = recoveryConfigRepository.findByApplicationId(activationEntity.getApplication().getId());
            if (recoveryConfigEntity == null || !recoveryConfigEntity.getActivationRecoveryEnabled()) {
                logger.warn("Activation recovery is disabled");
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_REQUEST);
            }

            // Note: the code below expects that application version for given activation has been verified.
            // We want to avoid checking application version twice (once during activation and second time in this method).
            // It is also expected that the activation is a valid activation which has just been created.
            // Prepare repositories
            final RecoveryCodeRepository recoveryCodeRepository = repositoryCatalogue.getRecoveryCodeRepository();

            final long applicationId = activationEntity.getApplication().getId();
            final String activationId = activationEntity.getActivationId();
            final String userId = activationEntity.getUserId();

            // Verify activation state
            if (!ActivationStatus.OTP_USED.equals(activationEntity.getActivationStatus()) && !ActivationStatus.ACTIVE.equals(activationEntity.getActivationStatus())) {
                logger.warn("Create recovery code failed because of invalid activation state, application ID: {}, activation ID: {}, activation state: {}", applicationId, activationId, activationEntity.getActivationStatus());
                throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_INCORRECT_STATE);
            }

            // Check whether user has any recovery code in state CREATED or ACTIVE, in this case the recovery code needs to be revoked first
            List<RecoveryCodeEntity> existingRecoveryCodes = recoveryCodeRepository.findAllByApplicationIdAndActivationId(applicationId, activationId);
            for (RecoveryCodeEntity recoveryCodeEntity: existingRecoveryCodes) {
                if (recoveryCodeEntity.getStatus() == io.getlime.security.powerauth.app.server.database.model.RecoveryCodeStatus.CREATED || recoveryCodeEntity.getStatus() == io.getlime.security.powerauth.app.server.database.model.RecoveryCodeStatus.ACTIVE) {
                    logger.warn("Create recovery code failed because of existing recovery codes, application ID: {}, activation ID: {}", applicationId, activationId);
                    throw localizationProvider.buildExceptionForCode(ServiceError.RECOVERY_CODE_ALREADY_EXISTS);
                }
            }

            // Generate random secret key
            String recoveryCode = null;
            Map<Integer, String> puks = null;

            for (int i = 0; i < powerAuthServiceConfiguration.getGenerateRecoveryCodeIterations(); i++) {
                RecoveryInfo recoveryInfo = identifierGenerator.generateRecoveryCode();
                // Check that recovery code is unique
                boolean recoveryCodeExists = recoveryCodeRepository.getRecoveryCodeCount(applicationId, recoveryInfo.getRecoveryCode()) > 0;
                if (!recoveryCodeExists) {
                    recoveryCode = recoveryInfo.getRecoveryCode();
                    puks = recoveryInfo.getPuks();
                    break;
                }
            }

            // In case recovery code generation failed, throw an exception
            if (recoveryCode == null || puks == null || puks.size() != 1) {
                logger.error("Unable to generate recovery code");
                throw localizationProvider.buildExceptionForCode(ServiceError.UNABLE_TO_GENERATE_RECOVERY_CODE);
            }

            // Create and persist recovery code entity with PUK
            final RecoveryCodeEntity recoveryCodeEntity = new RecoveryCodeEntity();
            recoveryCodeEntity.setUserId(userId);
            recoveryCodeEntity.setApplicationId(applicationId);
            recoveryCodeEntity.setActivationId(activationId);
            recoveryCodeEntity.setFailedAttempts(0L);
            recoveryCodeEntity.setMaxFailedAttempts(powerAuthServiceConfiguration.getRecoveryMaxFailedAttempts());
            recoveryCodeEntity.setRecoveryCode(recoveryCode);
            recoveryCodeEntity.setStatus(RecoveryCodeStatus.CREATED);
            recoveryCodeEntity.setTimestampCreated(new Date());

            // Only one PUK was generated
            final String puk = puks.values().iterator().next();

            final RecoveryPukEntity recoveryPukEntity = new RecoveryPukEntity();
            recoveryPukEntity.setPukIndex(1L);
            String pukHash = PasswordHash.hash(puk.getBytes(StandardCharsets.UTF_8));
            RecoveryPuk recoveryPuk = recoveryPukConverter.toDBValue(pukHash, applicationId, userId, recoveryCode, recoveryPukEntity.getPukIndex());
            recoveryPukEntity.setPuk(recoveryPuk.getPukHash());
            recoveryPukEntity.setPukEncryption(recoveryPuk.getEncryptionMode());
            recoveryPukEntity.setStatus(RecoveryPukStatus.VALID);
            recoveryPukEntity.setRecoveryCode(recoveryCodeEntity);
            recoveryCodeEntity.getRecoveryPuks().add(recoveryPukEntity);

            recoveryCodeRepository.save(recoveryCodeEntity);

            return new ActivationRecovery(recoveryCode, puk);
        } catch (InvalidKeyException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        } catch (GenericCryptoException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.GENERIC_CRYPTOGRAPHY_ERROR);
        } catch (CryptoProviderException ex) {
            logger.error(ex.getMessage(), ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_CRYPTO_PROVIDER);
        }
    }

}
