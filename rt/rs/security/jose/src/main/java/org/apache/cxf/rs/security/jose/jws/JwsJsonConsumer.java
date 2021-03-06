/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.rs.security.jose.jws;

import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.jaxrs.provider.json.JsonMapObject;
import org.apache.cxf.jaxrs.provider.json.JsonMapObjectReaderWriter;
import org.apache.cxf.rs.security.jose.JoseHeaders;
import org.apache.cxf.rs.security.jose.JoseUtils;
import org.apache.cxf.rs.security.jose.jwk.JsonWebKey;

public class JwsJsonConsumer {
    protected static final Logger LOG = LogUtils.getL7dLogger(JwsJsonConsumer.class);
    private String jwsSignedDocument;
    private String encodedJwsPayload;
    private List<JwsJsonSignatureEntry> signatureEntries = new LinkedList<JwsJsonSignatureEntry>();
    
    /**
     * @param jwsSignedDocument
     *            signed JWS Document
     */
    public JwsJsonConsumer(String jwsSignedDocument) {
        this(jwsSignedDocument, null);
    }
    public JwsJsonConsumer(String jwsSignedDocument, String encodedDetachedPayload) {
        this.jwsSignedDocument = jwsSignedDocument;
        prepare(encodedDetachedPayload);
    }

    private void prepare(String encodedDetachedPayload) {
        JsonMapObject jsonObject = new JsonMapObject();
        new JsonMapObjectReaderWriter().fromJson(jsonObject, jwsSignedDocument);
        
        Map<String, Object> jsonObjectMap = jsonObject.asMap(); 
        encodedJwsPayload = (String)jsonObjectMap.get("payload");
        if (encodedJwsPayload == null) {
            encodedJwsPayload = encodedDetachedPayload;
        } else if (encodedDetachedPayload != null) {
            LOG.warning("JSON JWS includes a payload expected to be detached");
            throw new JwsException(JwsException.Error.INVALID_JSON_JWS);
        }
        if (encodedJwsPayload == null) {
            LOG.warning("JSON JWS has no payload");
            throw new JwsException(JwsException.Error.INVALID_JSON_JWS);
        }
        
        List<Map<String, Object>> signatureArray = CastUtils.cast((List<?>)jsonObjectMap.get("signatures"));
        if (signatureArray != null) {
            if (jsonObjectMap.containsKey("signature")) {
                LOG.warning("JSON JWS has a flattened 'signature' element and a 'signatures' object");
                throw new JwsException(JwsException.Error.INVALID_JSON_JWS);
            }
            for (Map<String, Object> signatureEntry : signatureArray) {
                this.signatureEntries.add(getSignatureObject(signatureEntry));
            }
        } else {
            this.signatureEntries.add(getSignatureObject(jsonObjectMap));
        }
        if (signatureEntries.isEmpty()) {
            LOG.warning("JSON JWS has no signatures");
            throw new JwsException(JwsException.Error.INVALID_JSON_JWS);
        }
    }
    protected JwsJsonSignatureEntry getSignatureObject(Map<String, Object> signatureEntry) {
        String protectedHeader = (String)signatureEntry.get("protected");
        Map<String, Object> header = CastUtils.cast((Map<?, ?>)signatureEntry.get("header"));
        String signature = (String)signatureEntry.get("signature");
        return 
            new JwsJsonSignatureEntry(encodedJwsPayload, 
                                      protectedHeader, 
                                      signature, 
                                      header != null ? new JoseHeaders(header) : null);
    }
    public String getSignedDocument() {
        return this.jwsSignedDocument;
    }
    public String getEncodedJwsPayload() {
        return this.encodedJwsPayload;
    }
    public String getDecodedJwsPayload() {
        return JoseUtils.decodeToString(encodedJwsPayload);
    }
    public byte[] getDecodedJwsPayloadBytes() {
        return StringUtils.toBytesUTF8(getDecodedJwsPayload());
    }
    public List<JwsJsonSignatureEntry> getSignatureEntries() {
        return Collections.unmodifiableList(signatureEntries);
    }
    public MultivaluedMap<String, JwsJsonSignatureEntry> getSignatureEntryMap() {
        return JwsUtils.getJwsJsonSignatureMap(signatureEntries);
    }
    public boolean verifySignatureWith(JwsSignatureVerifier validator) {
        List<JwsJsonSignatureEntry> theSignatureEntries = 
            getSignatureEntryMap().get(validator.getAlgorithm().getJwaName());
        if (theSignatureEntries != null) {
            for (JwsJsonSignatureEntry signatureEntry : theSignatureEntries) {
                if (signatureEntry.verifySignatureWith(validator)) {
                    return true;
                }
            }
        }
        return false;
    }
    public boolean verifySignatureWith(PublicKey key, String algo) {
        return verifySignatureWith(JwsUtils.getPublicKeySignatureVerifier(key, algo));
    }
    public boolean verifySignatureWith(byte[] key, String algo) {
        return verifySignatureWith(JwsUtils.getHmacSignatureVerifier(key, algo));
    }
    public boolean verifySignatureWith(List<JwsSignatureVerifier> validators) {
        try {
            if (verifyAndGetNonValidated(validators).isEmpty()) {
                return true;
            }
        } catch (JwsException ex) {
            // ignore
        }
        LOG.warning("One of JSON JWS signatures is invalid");
        return false;
    }
    public List<JwsJsonSignatureEntry> verifyAndGetNonValidated(List<JwsSignatureVerifier> validators) {
        // TODO: more effective approach is needed
        List<JwsJsonSignatureEntry> validatedSignatures = new LinkedList<JwsJsonSignatureEntry>();
        for (JwsSignatureVerifier validator : validators) {
            List<JwsJsonSignatureEntry> theSignatureEntries = 
                getSignatureEntryMap().get(validator.getAlgorithm().getJwaName());
            if (theSignatureEntries != null) {
                for (JwsJsonSignatureEntry sigEntry : theSignatureEntries) {
                    if (sigEntry.verifySignatureWith(validator)) {     
                        validatedSignatures.add(sigEntry);
                        break;
                    }
                }
            }
        }
        List<JwsJsonSignatureEntry> nonValidatedSignatures = new LinkedList<JwsJsonSignatureEntry>();
        for (JwsJsonSignatureEntry sigEntry : signatureEntries) {
            if (!validatedSignatures.contains(sigEntry)) {        
                nonValidatedSignatures.add(sigEntry);
            }
        }
        return nonValidatedSignatures;
    }
    
    public boolean verifySignatureWith(JsonWebKey key) {
        return verifySignatureWith(JwsUtils.getSignatureVerifier(key));
    }
    public boolean verifySignatureWith(JsonWebKey key, String algo) {
        return verifySignatureWith(JwsUtils.getSignatureVerifier(key, algo));
    }
    public JwsJsonProducer toProducer() {
        JwsJsonProducer p = new JwsJsonProducer(getDecodedJwsPayload());
        p.getSignatureEntries().addAll(signatureEntries);
        return p;
    }
}
