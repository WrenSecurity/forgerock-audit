/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2015 ForgeRock AS.
 */
package org.forgerock.audit.events.handlers.csv;

import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.forgerock.audit.events.handlers.csv.CsvSecureConstants.ENTRY_CURRENT_KEY;
import static org.forgerock.audit.events.handlers.csv.CsvSecureConstants.ENTRY_CURRENT_SIGNATURE;
import static org.forgerock.audit.events.handlers.csv.CsvSecureConstants.HEADER_HMAC;
import static org.forgerock.audit.events.handlers.csv.CsvSecureConstants.HEADER_SIGNATURE;
import static org.forgerock.audit.events.handlers.csv.CsvSecureConstants.SIGNATURE_ALGORITHM;
import static org.forgerock.audit.events.handlers.csv.CsvSecureUtils.dataToSign;
import static org.forgerock.audit.events.handlers.csv.CsvSecureUtils.readPrivateKeyFromKeyStore;
import static org.forgerock.audit.events.handlers.csv.CsvSecureUtils.readSecretKeyFromKeyStore;
import static org.forgerock.audit.events.handlers.csv.CsvSecureUtils.writeToKeyStore;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.forgerock.util.annotations.VisibleForTesting;
import org.forgerock.util.encode.Base64;
import org.forgerock.util.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.ICsvMapWriter;

/**
 * This class wraps an ICsvMapWriter and silently adds 2 last columns : HMAC and SIGNATURE.
 * The column HMAC is filled with the HMAC calculation of the current row and a key.
 * The column SIGNATURE is filled with the signature calculation of the last HMAC and the last signature if any.
 */
public class CsvSecureMapWriter implements ICsvMapWriter {

    private static final Logger logger = LoggerFactory.getLogger(CsvSecureMapWriter.class);

    private final ICsvMapWriter delegate;
    private final HmacCalculator hmacCalculator;
    private final ScheduledExecutorService scheduler;
    private final ReentrantLock signatureLock = new ReentrantLock();
    private final Signature signer;
    private final Runnable signatureTask;
    private final Duration signatureInterval;
    private final String keystoreFilename;
    private final String keystorePassword;
    private ScheduledFuture<?> scheduledSignature;

    private String lastHMAC;
    private String[] header;
    private byte[] lastSignature;

    /**
     * Constructs a new CsvSecureMapWriter.
     *
     * The keystore pointed by {@code keystoreFilename} has to contain a {@code SecretKey} stored with the alias
     * {@plain InitialKey}.
     * In case {@code resume} is true, that means we are resuming an existing CSV file, so that will lookup into the
     * keystore the current key used for the HMAC hashing instead of the initial key.
     *
     * @param delegate the real CsvMapWriter to write to.
     * @param keystoreFilename a path to the keystore filename
     * @param keystorePassword the password to unlock the keystore
     * @param signatureInterval the interval to insert a signature
     */
    CsvSecureMapWriter(ICsvMapWriter delegate, String keystoreFilename, String keystorePassword,
            Duration signatureInterval) {
        this(delegate, keystoreFilename, keystorePassword, signatureInterval, true);
    }

    /**
     * Constructs a new CsvSecureMapWriter.
     *
     * The keystore pointed by {@code keystoreFilename} has to contain a {@code SecretKey} stored with the alias
     * {@plain InitialKey}.
     * In case {@code resume} is true, that means we are resuming an existing CSV file, so that will lookup into the
     * keystore the current key used for the HMAC hashing instead of the initial key.
     *
     * @param delegate the real CsvMapWriter to write to.
     * @param keystoreFilename a path to the keystore filename
     * @param keystorePassword the password to unlock the keystore
     * @param signatureInterval the interval to insert a signature
     * @param resume check if we are resuming an existing file.
     */
    CsvSecureMapWriter(ICsvMapWriter delegate, String keystoreFilename, String keystorePassword,
            Duration signatureInterval, boolean resume) {
        this.delegate = delegate;
        this.signatureInterval = signatureInterval;
        this.keystoreFilename = keystoreFilename;
        this.keystorePassword = keystorePassword;

        try {
            signer = Signature.getInstance(CsvSecureConstants.SIGNATURE_ALGORITHM);

            // Init signer
            PrivateKey privateKey = readPrivateKeyFromKeyStore(keystoreFilename, CsvSecureConstants.ENTRY_SIGNATURE,
                    keystorePassword);
            signer.initSign(privateKey);
        } catch (IOException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException(e);
        }

        try {
            SecretKey currentKey;
            if (resume) {
                currentKey = readSecretKeyFromKeyStore(CsvSecureConstants.ENTRY_CURRENT_KEY, keystoreFilename,
                        keystorePassword);
                if (currentKey == null) {
                    throw new IllegalStateException("We are supposed to resume but there is not entry for CurrentKey.");
                }
                logger.info("Resuming the writer verifier with the key " + Base64.encode(currentKey.getEncoded()));
            } else {
                // Is it a fresh new keystore ?
                currentKey = readSecretKeyFromKeyStore(CsvSecureConstants.ENTRY_INITIAL_KEY, keystoreFilename,
                        keystorePassword);
                if (currentKey == null) {
                    throw new IllegalStateException("Expecting to find an initial key into the keystore.");
                }
                logger.info("Starting the writer with the key " + Base64.encode(currentKey.getEncoded()));

                // As we start to work, store the current key too
                writeToKeyStore(currentKey, CsvSecureConstants.ENTRY_CURRENT_KEY, keystoreFilename, keystorePassword);
            }
            this.hmacCalculator = new HmacCalculator(currentKey, CsvSecureConstants.HMAC_ALGORITHM);
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }

        scheduler = Executors.newScheduledThreadPool(1);

        signatureTask = new Runnable() {
            @Override
            public void run() {
                logger.info("Writing a signature.");

                try {
                    writeSignature();
                } catch (Exception ex) {
                    logger.error("An error occured while writing the signature", ex);
                }
            }
        };
    }

    @Override
    public void flush() throws IOException {
        delegate.flush();
    }

    @Override
    public void close() throws IOException {
        flush();
        scheduler.shutdown();
        try {
            while (!scheduler.awaitTermination(500, MILLISECONDS)) {
                logger.debug("Waiting to terminate the scheduler.");
            }
        } catch (InterruptedException ex) {
            logger.error("Unable to terminate the scheduler", ex);
            Thread.currentThread().interrupt();
        }
        signatureLock.lock();
        try {
            if (scheduledSignature != null && scheduledSignature.cancel(false)) {
                // We were able to cancel it before it starts, so let's generate the signature now.
                writeSignature();
            }
        } finally {
            signatureLock.unlock();
        }
        delegate.close();
    }

    @Override
    public int getLineNumber() {
        return delegate.getLineNumber();
    }

    @Override
    public int getRowNumber() {
        return delegate.getRowNumber();
    }

    @Override
    public void write(Map<String, ?> values, String... nameMapping) throws IOException {
        write(values, nameMapping, nameMapping == null ? null : new CellProcessor[nameMapping.length]);
    }

    @Override
    public void writeComment(String comment) throws IOException {
        delegate.writeComment(comment);
    }

    @Override
    public void writeHeader(String... header) throws IOException {
        this.header = header;
        String[] newHeader = addExtraColumns(header);
        delegate.writeHeader(newHeader);
    }

    @VisibleForTesting
    void writeSignature() throws IOException {
        // We have to prevent from writing another line between the signature calculation
        // and the signature's row write, as the calculation uses the lastHMAC.
        signatureLock.lock();
        try {
            lastSignature = calculateSignature();
            Map<String, String> values = singletonMap(HEADER_SIGNATURE, Base64.encode(lastSignature));
            write(values, header);

            // Store the current signature into the Keystore
            writeToKeyStore(new SecretKeySpec(lastSignature, SIGNATURE_ALGORITHM), ENTRY_CURRENT_SIGNATURE,
                    keystoreFilename, keystorePassword);
        } catch (IOException | GeneralSecurityException ex) {
            logger.error(ex.getMessage(), ex);
            throw new IOException(ex);
        } finally {
            signatureLock.unlock();
            flush();
        }
    }

    private byte[] calculateSignature() throws SignatureException {
        signer.update(dataToSign(lastSignature, lastHMAC));
        return signer.sign();
    }

    @Override
    public void write(Map<String, ?> values, String[] nameMapping, CellProcessor[] processors) throws IOException {
        signatureLock.lock();
        try {
            logger.info("Writing data : " + values + " for " + Arrays.toString(nameMapping));
            String[] newNameMapping = addExtraColumns(nameMapping);

            Map<String, Object> newValues = new HashMap<>(values);
            if (!values.containsKey(CsvSecureConstants.HEADER_SIGNATURE)) {
                insertHMACSignature(newValues, nameMapping);
            }

            CellProcessor[] newProcessors = new CellProcessor[newNameMapping.length];
            System.arraycopy(processors, 0, newProcessors, 0, processors.length);
            newProcessors[processors.length] = null;

            delegate.write(newValues, newNameMapping, newProcessors);
            delegate.flush();
            // Store the current key
            writeToKeyStore(hmacCalculator.getCurrentKey(), ENTRY_CURRENT_KEY, keystoreFilename, keystorePassword);

            // Schedule a signature task only if needed.
            if (!values.containsKey(HEADER_SIGNATURE)
                    && (scheduledSignature == null || scheduledSignature.isDone())) {
                logger.info("Triggering a new signature task to be executed in " + signatureInterval);
                try {
                    scheduledSignature = scheduler.schedule(signatureTask, signatureInterval.getValue(),
                            signatureInterval.getUnit());
                } catch (RejectedExecutionException e) {
                    logger.error(e.getMessage(), e);
                }
            }
        } catch (IOException | KeyStoreException | NoSuchAlgorithmException | CertificateException ex) {
            throw new IOException(ex);
        } finally {
            signatureLock.unlock();
        }
    }

    private void insertHMACSignature(Map<String, Object> values, String[] nameMapping) throws IOException {
        try {
            lastHMAC = hmacCalculator.calculate(dataToSign(logger, values, nameMapping));
            values.put(CsvSecureConstants.HEADER_HMAC, lastHMAC);
        } catch (SignatureException ex) {
            logger.error(ex.getMessage(), ex);
            throw new IOException(ex);
        }
    }

    private String[] addExtraColumns(String... header) {
        String[] newHeader = new String[header.length + 2];
        System.arraycopy(header, 0, newHeader, 0, header.length);
        newHeader[header.length] = HEADER_HMAC;
        newHeader[header.length + 1] = HEADER_SIGNATURE;
        return newHeader;
    }

    void setHeader(String[] header) {
        this.header = header;
    }

    void setLastHMAC(String lastHMac) {
        this.lastHMAC = lastHMac;
    }

    void setLastSignature(byte[] lastSignature) {
        this.lastSignature = lastSignature;
    }

}