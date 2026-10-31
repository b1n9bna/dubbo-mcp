package com.mcp.dubbo.driver;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import okhttp3.*;
import okhttp3.tls.*;
import org.junit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/** Real loopback TLS handshakes; no external endpoint or trust-store changes. */
public class TlsCompatibilityTest {
    private HttpsServer server;
    private OkHttpClient trusted, untrusted;
    @Before public void setup() throws Exception {
        HeldCertificate certificate=new HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverCertificates=new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCertificates.sslContext()));
        server.createContext("/",exchange->{
            byte[] body="{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        HandshakeCertificates clientCertificates=new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        trusted=base().sslSocketFactory(clientCertificates.sslSocketFactory(),clientCertificates.trustManager()).build();
        untrusted=base().build();
    }
    private OkHttpClient.Builder base() {
        return new OkHttpClient.Builder().dns(host->Collections.singletonList(InetAddress.getByName("127.0.0.1")))
                .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
    }
    private String url(String hostname) { return "https://"+hostname+":"+server.getAddress().getPort()+"/"; }
    @After public void cleanup() {
        if(server!=null)server.stop(0);
        for(OkHttpClient client:new OkHttpClient[]{trusted,untrusted})if(client!=null){client.connectionPool().evictAll();client.dispatcher().executorService().shutdownNow();}
    }
    @Test public void trustedCertificateAndMatchingHostSucceed() throws Exception {
        try(Response result=trusted.newCall(new Request.Builder().url(url("localhost")).build()).execute()) {
            Assert.assertTrue(result.isSuccessful());Assert.assertTrue(result.body().string().contains("true"));
        }
    }
    @Test public void trustedCertificateForWrongHostIsRejected() throws Exception {
        try(Response ignored=trusted.newCall(new Request.Builder().url(url("127.0.0.1")).build()).execute()){Assert.fail("Hostname mismatch accepted");}
        catch(javax.net.ssl.SSLPeerUnverifiedException expected){Assert.assertTrue(expected.getMessage().contains("127.0.0.1"));}
    }
    @Test public void untrustedCertificateIsRejected() throws Exception {
        try(Response ignored=untrusted.newCall(new Request.Builder().url(url("localhost")).build()).execute()){Assert.fail("Untrusted certificate accepted");}
        catch(javax.net.ssl.SSLException expected){Assert.assertNotNull(expected.getMessage());}
    }
}
