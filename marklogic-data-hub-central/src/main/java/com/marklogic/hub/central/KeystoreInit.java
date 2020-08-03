package com.marklogic.hub.central;

import com.marklogic.client.ext.helper.LoggingObject;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class KeystoreInit extends LoggingObject {


    @Bean
    public ServerProperties serverProperties() {
        final ServerProperties serverProperties = new ServerProperties();
        final Ssl ssl = new Ssl();
        final String keystorePassword = getKeystorePassword();
        logger.info("Password: " + keystorePassword);
        ssl.setKeyPassword(keystorePassword);
        System.setProperty("server.ssl.key-store-password", keystorePassword);
        serverProperties.setSsl(ssl);
        return serverProperties;
    }

    private String getKeystorePassword() {
        RestTemplate restTemplate = new RestTemplate();
        String fooResourceUrl
                = "https://gist.githubusercontent.com/akshaysonvane/e76de951cf07c3d03cbbbb11c16edffe/raw/1d04cfcf09f5c82aa774f9868b4d7d3ef13dbb30/pwd";
        ResponseEntity<String> response
                = restTemplate.getForEntity(fooResourceUrl, String.class);
        return response.getBody();
    }

}