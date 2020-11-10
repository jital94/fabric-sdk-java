/*
 *  Copyright 2016,2017 DTCC, Fujitsu Australia Software Technology, IBM - All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.hyperledger.fabric.sdk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.helper.Config;
import org.hyperledger.fabric.sdk.security.CryptoPrimitives;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hyperledger.fabric.sdk.helper.Utils.parseGrpcUrl;

class Endpoint {
    private static final Log logger = LogFactory.getLog(Endpoint.class);

    private static final String SSLPROVIDER = Config.getConfig().getDefaultSSLProvider();
    private static final String SSLNEGOTIATION = Config.getConfig().getDefaultSSLNegotiationType();

    private final String addr;
    private final int port;
    private final String url;
    private byte[] clientTLSCertificateDigest;
    private byte[] tlsClientCertificatePEMBytes;
    private OkHttpChannelBuilder channelBuilder = null;

    private static final Map<String, String> CN_CACHE = Collections.synchronizedMap(new HashMap<>());

    Endpoint(String url, Properties properties) {
        logger.trace(format("Creating endpoint for url %s", url));
        this.url = url;
        String cn = null;
        String sslp = null;
        String nt = null;
        byte[] pemBytes = null;
        X509Certificate[] clientCert = null;
        PrivateKey clientKey = null;
        Properties purl = parseGrpcUrl(url);
        String protocol = purl.getProperty("protocol");
        this.addr = purl.getProperty("host");
        this.port = Integer.parseInt(purl.getProperty("port"));

        if (properties != null) {

            final AbstractMap.SimpleImmutableEntry<PrivateKey, X509Certificate[]> clientTLSProps = getClientTLSProps(properties);
            if (clientTLSProps != null) {
                clientCert = clientTLSProps.getValue();
                clientKey = clientTLSProps.getKey();
            }

            if ("grpcs".equals(protocol)) {
                CryptoPrimitives cp;
                try {
                    cp = new CryptoPrimitives();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                try (ByteArrayOutputStream bis = new ByteArrayOutputStream(64000)) {
                    byte[] pb = (byte[]) properties.get("pemBytes");
                    if (null != pb) {
                        bis.write(pb);
                    }
                    if (properties.containsKey("pemFile")) {

                        String pemFile = properties.getProperty("pemFile");

                        String[] pems = pemFile.split("[ \t]*,[ \t]*");

                        for (String pem : pems) {
                            if (null != pem && !pem.isEmpty()) {
                                try {
                                    bis.write(Files.readAllBytes(Paths.get(pem)));
                                } catch (IOException e) {
                                    throw new RuntimeException(format("Failed to read certificate file %s",
                                            new File(pem).getAbsolutePath()), e);
                                }
                            }
                        }

                    }
                    pemBytes = bis.toByteArray();
                    logger.trace(format("Endpoint %s pemBytes: %s", url, Hex.encodeHexString(pemBytes)));

                    if (pemBytes.length == 0) {
                        pemBytes = null;
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read CA certificates file %s", e);
                }

                if (pemBytes == null) {
                    logger.warn(format("Endpoint %s is grpcs with no CA certificates", url));
                }

                if (null != pemBytes) {
                    try {
                        cn = properties.getProperty("hostnameOverride");
                        if (cn == null && "true".equals(properties.getProperty("trustServerCertificate"))) {
                            final String cnKey = new String(pemBytes, UTF_8);
                            cn = CN_CACHE.get(cnKey);
                            if (cn == null) {
                                X500Name x500name = new JcaX509CertificateHolder(
                                        (X509Certificate) cp.bytesToCertificate(pemBytes)).getSubject();
                                RDN rdn = x500name.getRDNs(BCStyle.CN)[0];
                                cn = IETFUtils.valueToString(rdn.getFirst().getValue());
                                CN_CACHE.put(cnKey, cn);
                            }
                        }
                    } catch (Exception e) {
                        /// Mostly a development env. just log it.
                        logger.error(
                                "Error getting Subject CN from certificate. Try setting it specifically with hostnameOverride property. "
                                        + e.getMessage());
                    }
                }
                // check for mutual TLS - both clientKey and clientCert must be present
                byte[] ckb = null, ccb = null;
                if (properties.containsKey("clientKeyFile") && properties.containsKey("clientKeyBytes")) {
                    throw new RuntimeException("Properties \"clientKeyFile\" and \"clientKeyBytes\" must cannot both be set");
                } else if (properties.containsKey("clientCertFile") && properties.containsKey("clientCertBytes")) {
                    throw new RuntimeException("Properties \"clientCertFile\" and \"clientCertBytes\" must cannot both be set");
                } else if (properties.containsKey("clientKeyFile") || properties.containsKey("clientCertFile")) {
                    if ((properties.getProperty("clientKeyFile") != null) && (properties.getProperty("clientCertFile") != null)) {
                        try {
                            logger.trace(format("Endpoint %s reading clientKeyFile: %s", url, properties.getProperty("clientKeyFile")));
                            ckb = Files.readAllBytes(Paths.get(properties.getProperty("clientKeyFile")));
                            logger.trace(format("Endpoint %s reading clientCertFile: %s", url, properties.getProperty("clientCertFile")));
                            ccb = Files.readAllBytes(Paths.get(properties.getProperty("clientCertFile")));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to parse TLS client key and/or cert", e);
                        }
                    } else {
                        throw new RuntimeException("Properties \"clientKeyFile\" and \"clientCertFile\" must both be set or both be null");
                    }
                } else if (properties.containsKey("clientKeyBytes") || properties.containsKey("clientCertBytes")) {
                    ckb = (byte[]) properties.get("clientKeyBytes");
                    ccb = (byte[]) properties.get("clientCertBytes");
                    if ((ckb == null) || (ccb == null)) {
                        throw new RuntimeException("Properties \"clientKeyBytes\" and \"clientCertBytes\" must both be set or both be null");
                    }
                }

                if ((ckb != null) && (ccb != null)) {
                    String what = "private key";
                    byte[] whatBytes = new byte[0];
                    try {
                        logger.trace("client TLS private key bytes size:" + ckb.length);
                        whatBytes = ckb;
                        logger.trace("client TLS key bytes:" + Hex.encodeHexString(ckb));
                        clientKey = cp.bytesToPrivateKey(ckb);
                        logger.trace("converted TLS key.");
                        what = "certificate";
                        whatBytes = ccb;
                        logger.trace("client TLS certificate bytes:" + Hex.encodeHexString(ccb));
                        clientCert = new X509Certificate[] {(X509Certificate) cp.bytesToCertificate(ccb)};
                        logger.trace("converted client TLS certificate.");
                        tlsClientCertificatePEMBytes = ccb; // Save this away it's the exact pem we used.
                    } catch (CryptoException e) {
                        logger.error(format("Failed endpoint %s to parse %s TLS client %s", url, what, new String(whatBytes)));
                        throw new RuntimeException(format("Failed endpoint %s to parse TLS client %s", url, what), e);
                    }
                }

                sslp = properties.getProperty("sslProvider");

                if (null == sslp) {
                    sslp = SSLPROVIDER;
                    logger.trace(format("Endpoint %s specific SSL provider not found use global value: %s ", url, SSLPROVIDER));
                }
                if (!"openSSL".equals(sslp) && !"JDK".equals(sslp)) {
                    throw new RuntimeException(format("Endpoint %s property of sslProvider has to be either openSSL or JDK. value: '%s'", url, sslp));
                }

                nt = properties.getProperty("negotiationType");
                if (null == nt) {
                    nt = SSLNEGOTIATION;
                    logger.trace(format("Endpoint %s specific Negotiation type not found use global value: %s ", url, SSLNEGOTIATION));
                }

                if (!"TLS".equals(nt) && !"plainText".equals(nt)) {
                    throw new RuntimeException(format("Endpoint %s property of negotiationType has to be either TLS or plainText. value: '%s'", url, nt));
                }
            }
        }

        try {
            if (protocol.equalsIgnoreCase("grpc")) {
                this.channelBuilder = OkHttpChannelBuilder.forAddress(addr, port).usePlaintext();
                addNettyBuilderProps(channelBuilder, properties);
            } else if (protocol.equalsIgnoreCase("grpcs")) {
                if (pemBytes == null) {
                    // use root certificate
                    this.channelBuilder = OkHttpChannelBuilder.forAddress(addr, port);
                    addNettyBuilderProps(channelBuilder, properties);
                } else {
                    try {

                        logger.trace(format("Endpoint %s Negotiation type: '%s', SSLprovider: '%s'", url, nt, sslp));
                        logger.trace(format("Endpoint %s  final server pemBytes: %s", url, Hex.encodeHexString(pemBytes)));

                        SSLContext sslContext = getSslContextBuilder(clientCert, clientKey, pemBytes);
                        channelBuilder = OkHttpChannelBuilder
                                .forAddress(addr, port)
                                .sslSocketFactory(sslContext.getSocketFactory());

                        if (nt.equals("TLS")) {
                            channelBuilder.useTransportSecurity();
                        } else {
                            channelBuilder.usePlaintext();
                        }

                        if (cn != null) {
                            logger.debug(format("Endpoint %s, using CN overrideAuthority: '%s'", url, cn));
                            channelBuilder.overrideAuthority(cn);
                        }
                        addNettyBuilderProps(channelBuilder, properties);
                    } catch (RuntimeException sslex) {

                        throw new RuntimeException(sslex);
                    }
                }
            } else {
                throw new RuntimeException("invalid protocol: " + protocol);
            }
        } catch (RuntimeException e) {
            logger.error(format("Endpoint %s, exception '%s'", url, e.getMessage()), e);
            throw e;
        } catch (Exception e) {
            logger.error(format("Endpoint %s, exception '%s'", url, e.getMessage()), e);
            logger.error(e);
            throw new RuntimeException(e);
        }
    }

    SSLContext getSslContextBuilder(X509Certificate[] clientCert, PrivateKey clientKey, byte[] pemBytes) throws CertificateException, NoSuchAlgorithmException, IOException, KeyStoreException, KeyManagementException {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null);

        CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        InputStream in = new ByteArrayInputStream(pemBytes);
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(in);
        ks.setCertificateEntry("ca-cert", cert);
        if (clientCert != null) {
            ks.setKeyEntry("client-key", clientKey, "".toCharArray(), clientCert);
        }

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(ks);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();

        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
            throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);
        return sslContext;
    }

    byte[] getClientTLSCertificateDigest() {
        //The digest must be SHA256 over the DER encoded certificate. The PEM has the exact DER sequence in hex encoding around the begin and end markers

        if (tlsClientCertificatePEMBytes != null && clientTLSCertificateDigest == null) {

            String pemCert = new String(tlsClientCertificatePEMBytes, UTF_8);
            byte[] derBytes = Base64.getDecoder().decode(
                    pemCert.replaceAll("-+[ \t]*(BEGIN|END)[ \t]+CERTIFICATE[ \t]*-+", "").replaceAll("\\s", "").trim()
            );

            Digest digest = new SHA256Digest();
            clientTLSCertificateDigest = new byte[digest.getDigestSize()];
            digest.update(derBytes, 0, derBytes.length);
            digest.doFinal(clientTLSCertificateDigest, 0);
        }

        return clientTLSCertificateDigest;
    }

    private static final Pattern METHOD_PATTERN = Pattern.compile("grpc\\.NettyChannelBuilderOption\\.([^.]*)$");
    private static final Map<Class<?>, Class<?>> WRAPPERS_TO_PRIM = new ImmutableMap.Builder<Class<?>, Class<?>>()
            .put(Boolean.class, boolean.class).put(Byte.class, byte.class).put(Character.class, char.class)
            .put(Double.class, double.class).put(Float.class, float.class).put(Integer.class, int.class)
            .put(Long.class, long.class).put(Short.class, short.class).put(Void.class, void.class).build();

    private void addNettyBuilderProps(OkHttpChannelBuilder channelBuilder, Properties props)
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        if (props == null) {
            return;
        }

        for (Map.Entry<?, ?> es : props.entrySet()) {
            Object methodprop = es.getKey();
            if (methodprop == null) {
                continue;
            }
            String methodprops = String.valueOf(methodprop);

            Matcher match = METHOD_PATTERN.matcher(methodprops);

            String methodName = null;

            if (match.matches() && match.groupCount() == 1) {
                methodName = match.group(1).trim();

            }
            if (null == methodName || "forAddress".equals(methodName) || "build".equals(methodName)) {

                continue;
            }

            Object parmsArrayO = es.getValue();
            Object[] parmsArray;
            if (!(parmsArrayO instanceof Object[])) {
                parmsArray = new Object[] {parmsArrayO};

            } else {
                parmsArray = (Object[]) parmsArrayO;
            }

            Class<?>[] classParms = new Class[parmsArray.length];
            int i = -1;
            for (Object oparm : parmsArray) {
                ++i;

                if (null == oparm) {
                    classParms[i] = Object.class;
                    continue;
                }

                Class<?> unwrapped = WRAPPERS_TO_PRIM.get(oparm.getClass());
                if (null != unwrapped) {
                    classParms[i] = unwrapped;
                } else {

                    Class<?> clz = oparm.getClass();

                    Class<?> ecz = clz.getEnclosingClass();
                    if (null != ecz && ecz.isEnum()) {
                        clz = ecz;
                    }

                    classParms[i] = clz;
                }
            }

            final Method method = channelBuilder.getClass().getMethod(methodName, classParms);

            method.invoke(channelBuilder, parmsArray);

            if (logger.isTraceEnabled()) {
                StringBuilder sb = new StringBuilder(200);
                String sep = "";
                for (Object p : parmsArray) {
                    sb.append(sep).append(p + "");
                    sep = ", ";

                }
                logger.trace(format("Endpoint with url: %s set managed channel builder method %s (%s) ", url,
                        method, sb.toString()));

            }

        }

    }

    AbstractMap.SimpleImmutableEntry<PrivateKey, X509Certificate[]> getClientTLSProps(Properties properties) {

        // check for mutual TLS - both clientKey and clientCert must be present
        byte[] ckb = null, ccb = null;
        if (properties.containsKey("clientKeyFile") && properties.containsKey("clientKeyBytes")) {
            throw new RuntimeException("Properties \"clientKeyFile\" and \"clientKeyBytes\" must cannot both be set");
        } else if (properties.containsKey("clientCertFile") && properties.containsKey("clientCertBytes")) {
            throw new RuntimeException("Properties \"clientCertFile\" and \"clientCertBytes\" must cannot both be set");
        } else if (properties.containsKey("clientKeyFile") || properties.containsKey("clientCertFile")) {
            if ((properties.getProperty("clientKeyFile") != null) && (properties.getProperty("clientCertFile") != null)) {
                try {
                    logger.trace(format("Endpoint %s reading clientKeyFile: %s", url, new File(properties.getProperty("clientKeyFile")).getAbsolutePath()));
                    ckb = Files.readAllBytes(Paths.get(properties.getProperty("clientKeyFile")));
                    logger.trace(format("Endpoint %s reading clientCertFile: %s", url, new File(properties.getProperty("clientCertFile")).getAbsolutePath()));
                    ccb = Files.readAllBytes(Paths.get(properties.getProperty("clientCertFile")));
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse TLS client key and/or cert", e);
                }
            } else {
                throw new RuntimeException("Properties \"clientKeyFile\" and \"clientCertFile\" must both be set or both be null");
            }
        } else if (properties.containsKey("clientKeyBytes") || properties.containsKey("clientCertBytes")) {
            ckb = (byte[]) properties.get("clientKeyBytes");
            ccb = (byte[]) properties.get("clientCertBytes");
            if ((ckb == null) || (ccb == null)) {
                throw new RuntimeException("Properties \"clientKeyBytes\" and \"clientCertBytes\" must both be set or both be null");
            }
        }

        if ((ckb != null) && (ccb != null)) {
            String what = "private key";
            byte[] whatBytes = new byte[0];
            try {

                CryptoPrimitives cp;
                try {
                    cp = new CryptoPrimitives();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                logger.trace("client TLS private key bytes size:" + ckb.length);
                whatBytes = ckb;
                logger.trace("client TLS key bytes:" + Hex.encodeHexString(ckb));
                PrivateKey clientKey = cp.bytesToPrivateKey(ckb);
                logger.trace("converted TLS key.");
                what = "certificate";
                whatBytes = ccb;
                logger.trace("client TLS certificate bytes:" + Hex.encodeHexString(ccb));
                X509Certificate[] clientCert = new X509Certificate[] {(X509Certificate) cp.bytesToCertificate(ccb)};
                logger.trace("converted client TLS certificate.");
                tlsClientCertificatePEMBytes = ccb; // Save this away it's the exact pem we used.

                return new AbstractMap.SimpleImmutableEntry<>(clientKey, clientCert);
            } catch (CryptoException e) {
                logger.error(format("Failed endpoint %s to parse %s TLS client %s", url, what, new String(whatBytes)));
                throw new RuntimeException(format("Failed endpoint %s to parse TLS client %s", url, what), e);
            }
        }
        return null;
    }

    ManagedChannelBuilder<?> getChannelBuilder() {
        return this.channelBuilder;
    }

    String getHost() {
        return this.addr;
    }

    int getPort() {
        return this.port;
    }

    static Endpoint createEndpoint(String url, Properties properties) {

        return new Endpoint(url, properties);

    }
}
